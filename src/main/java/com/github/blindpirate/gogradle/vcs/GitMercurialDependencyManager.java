/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.github.blindpirate.gogradle.vcs;

import com.github.blindpirate.gogradle.core.cache.GlobalCacheManager;
import com.github.blindpirate.gogradle.core.cache.ProjectCacheManager;
import com.github.blindpirate.gogradle.core.dependency.GolangDependency;
import com.github.blindpirate.gogradle.core.dependency.GolangDependencySet;
import com.github.blindpirate.gogradle.core.dependency.NotationDependency;
import com.github.blindpirate.gogradle.core.dependency.ResolveContext;
import com.github.blindpirate.gogradle.core.dependency.ResolvedDependency;
import com.github.blindpirate.gogradle.core.dependency.resolve.AbstractVcsDependencyManager;
import com.github.blindpirate.gogradle.core.exceptions.DependencyResolutionException;
import com.github.blindpirate.gogradle.util.Assert;
import com.github.blindpirate.gogradle.util.IOUtils;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.github.blindpirate.gogradle.util.StringUtils.isNotBlank;

public abstract class GitMercurialDependencyManager extends AbstractVcsDependencyManager<GitMercurialCommit> {
    protected static final Logger LOGGER = Logging.getLogger(GitMercurialDependencyManager.class);

    public GitMercurialDependencyManager(GlobalCacheManager globalCacheManager,
                                         ProjectCacheManager projectCacheManager) {
        super(globalCacheManager, projectCacheManager);
    }

    protected abstract GitMercurialAccessor getAccessor();

    @Override
    protected void doReset(ResolvedDependency dependency, File repoRoot) {
        VcsResolvedDependency vcsResolvedDependency = (VcsResolvedDependency) dependency;

        getAccessor().checkout(repoRoot, vcsResolvedDependency.getVersion());
    }

    @Override
    protected ResolvedDependency createResolvedDependency(NotationDependency dependency,
                                                          File repoRoot,
                                                          GitMercurialCommit commit,
                                                          ResolveContext context) {
        VcsResolvedDependency ret = VcsResolvedDependency.builder()
                .withNotationDependency(dependency)
                .withCommitId(commit.getId())
                .withCommitTime(commit.getCommitTime())
                .build();

        GolangDependencySet dependencies = context.produceTransitiveDependencies(ret, repoRoot);
        ret.setDependencies(dependencies);

        return ret;
    }

    protected abstract VcsType getVcsType();

    @Override
    protected boolean versionExistsInRepo(File repoRoot, GolangDependency dependency) {
        if (dependency instanceof VcsNotationDependency) {
            VcsNotationDependency d = (VcsNotationDependency) dependency;
            if (d.isLatest()) {
                return false;
            } else if (d.getCommit() != null) {
                return getAccessor().findCommit(repoRoot, d.getCommit()).isPresent();
            } else if (d.getTag() != null) {
                return findMatchingTag(repoRoot, d.getTag()).isPresent();
            } else if (d.getBranch() != null) {
                // we see branch as volatile, always perform pull when a branch was specified
                return false;
            } else {
                throw new IllegalStateException("Shouldn't be here: " + dependency);
            }
        } else {
            VcsResolvedDependency d = (VcsResolvedDependency) dependency;
            return getAccessor().findCommit(repoRoot, d.getVersion()).isPresent();
        }
    }

    @Override
    protected void resetToSpecificVersion(File repository, GitMercurialCommit commit) {
        getAccessor().checkout(repository, commit.getId());
    }

    private Optional<GitMercurialCommit> findMatchingTag(File repository, String tag) {
        Optional<GitMercurialCommit> commit = getAccessor()
                .findCommitByTagOrBranch(repository, tag);
        if (commit.isPresent()) {
            return commit;
        }

        return findCommitBySemVersion(repository, tag);
    }

    @Override
    protected GitMercurialCommit determineVersion(File repoDir, NotationDependency dependency) {
        VcsNotationDependency notationDep = (VcsNotationDependency) dependency;
        if (notationDep.isLatest()) {
            // use HEAD of master branch
            return getAccessor().headCommitOfBranch(repoDir, getAccessor().getDefaultBranch(repoDir));
        }
        if (isNotBlank(notationDep.getCommit())) {
            Optional<GitMercurialCommit> commit = getAccessor().findCommit(repoDir, notationDep.getCommit());
            if (commit.isPresent()) {
                return commit.get();
            } else {
                throw DependencyResolutionException.cannotFindGitCommit(notationDep);
            }
        }

        if (isNotBlank(notationDep.getTag())) {
            Optional<GitMercurialCommit> commit = findMatchingTag(repoDir, notationDep.getTag());
            if (commit.isPresent()) {
                return commit.get();
            } else {
                throw DependencyResolutionException.cannotFindGitTag(dependency, notationDep.getTag(), repoDir);
            }
        }

        return getAccessor().headCommitOfBranch(repoDir, notationDep.getBranch());
    }

    private Optional<GitMercurialCommit> findCommitBySemVersion(File repository, String semVersionExpression) {
        List<GitMercurialCommit> tags = getAccessor().getAllTags(repository);

        List<GitMercurialCommit> satisfiedTags = tags.stream()
                .filter(tag -> tag.satisfies(semVersionExpression))
                .collect(Collectors.toList());

        if (satisfiedTags.isEmpty()) {
            return Optional.empty();
        }

        satisfiedTags.sort((tag1, tag2) -> tag2.getSemVersion().compareTo(tag1.getSemVersion()));

        return Optional.of(satisfiedTags.get(0));
    }

    @Override
    protected void updateRepository(GolangDependency dependency, File repoRoot) {
        String url = getAccessor().getRemoteUrl(repoRoot);

        if (dependency == null) {
            LOGGER.info("Fetching from {}", url);
        } else {
            LOGGER.info("Fetching {} from {}", dependency, url);
        }

        getAccessor().update(repoRoot);
    }

    @Override
    protected void initRepository(String dependencyName, List<String> urls, File repoRoot) {
        tryCloneWithUrls(dependencyName, urls, repoRoot);
        // https://github.com/gogradle/gogradle/issues/184#issuecomment-355498314
        updateRepository(null, repoRoot);
    }

    private void tryCloneWithUrls(String name, List<String> urls, File directory) {
        Assert.isNotEmpty(urls, "Urls of " + name + " should not be empty!");
        for (int i = 0; i < urls.size(); ++i) {
            IOUtils.clearDirectory(directory);

            String url = urls.get(i);
            try {
                getAccessor().clone(url, directory);
                return;
            } catch (Throwable e) {
                LOGGER.debug("Cloning with url {} failed, the cause is {}", url, e.getMessage());
                if (i == urls.size() - 1) {
                    throw DependencyResolutionException.cannotCloneRepository(name, e);
                }
            }
        }
    }

}
