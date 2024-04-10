package com.io.gittracker.model;

import com.google.gson.*;
import com.io.gittracker.services.GithubService;
import com.io.gittracker.utils.GHMapper;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import javafx.concurrent.Task;
import org.kohsuke.github.*;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestQueryBuilder;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GithubRepository implements Refreshable, Serializable {
    /** id used by GitHub api */
    private final long id;

    private URL htmlUrl;
    private String name;
    private Map<Long, PullRequest> pullRequestsMap = new HashMap<>();
    private Optional<PullRequest> latestPullRequest = Optional.empty();
    private List<String> labels = new ArrayList<>();
    private Group workspaceGroup; // TODO: remove
    transient Logger logger = LoggerFactory.getLogger(GithubRepository.class);

    public GithubRepository(long id) {
        this.id = id;
    }

    public Optional<PullRequest> getLatestPullRequest() {
        if (latestPullRequest == null) return Optional.empty();
        return latestPullRequest;
    }

    public List<PullRequest> getPullRequests() {
        return pullRequestsMap.values().stream().toList();
    }

    void mergePullRequests(List<PullRequest> newPullRequests) {
        for (PullRequest pr : newPullRequests) {
            Long prId = pr.getId();
            if (pullRequestsMap.containsKey(prId)) {
                pullRequestsMap.get(prId).update(pr);
            } else {
                pullRequestsMap.put(prId, pr);
            }
        }
        latestPullRequest = pullRequestsMap.values().stream().max(Comparator.comparing(PullRequest::getUpdatedAtDate));
    }

    public void setWorkspaceGroup(Group workspaceGroup) {
        this.workspaceGroup = workspaceGroup;
    }

    List<PullRequest> fetchPullRequests(GithubService githubService) {
        try {
            List<GHPullRequest> ghPullRequests =
                    //                    githubService.getRepositoryById(id).getPullRequests(GHIssueState.ALL);
                    githubService
                            .getRepositoryById(id)
                            .queryPullRequests()
                            .state(GHIssueState.ALL)
                            .sort(GHPullRequestQueryBuilder.Sort.UPDATED)
                            .direction(GHDirection.DESC)
                            .list()
                            .toList();
            return ghPullRequests.stream()
                    .map(pr -> GHMapper.mapToPullRequest(githubService, pr))
                    .toList();
        } catch (IOException e) {
            logger.error("Failed to fetch repository: {}", name, e);
        }
        return Collections.emptyList();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (GithubRepository) obj;
        return Objects.equals(this.name, that.name) && Objects.equals(this.htmlUrl, that.htmlUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Repository[" + "githubName="
                + name + ", " + "url="
                + htmlUrl + ", " + "grade="
                + labels + ", " + "group=";
    }

    public String getName() {
        return name;
    }

    public URL getHtmlUrl() {
        return htmlUrl;
    }

    public long getId() {
        return id;
    }

    public Group getWorkspaceGroup() {
        return workspaceGroup;
    }

    public List<String> getLabels() {
        return labels;
    }

    @Override
    public void refresh(GithubService githubService, ExecutorService executorService) {
        // TODO FIX :)
        //        mergePullRequests(fetchPullRequests(githubService));
        RefreshRepoTask task = new RefreshRepoTask(this, githubService);
        task.setOnSucceeded(event -> {
            Optional<GithubRepository> optional = task.valueProperty().get();
            if (optional.isEmpty()) return;
            GithubRepository new_values = optional.get();
            this.htmlUrl = new_values.htmlUrl;
            this.name = new_values.name;
            this.pullRequestsMap = new_values.pullRequestsMap;
            this.latestPullRequest = new_values.latestPullRequest;
        });
        executorService.execute(task);
    }

    static class RefreshRepoTask extends Task<Optional<GithubRepository>> {
        GithubRepository parent;
        GithubService githubService;

        RefreshRepoTask(GithubRepository parent, GithubService githubService) {
            this.parent = parent;
            this.githubService = githubService;
        }

        @Override
        protected Optional<GithubRepository> call() {
            System.out.println("Refreshing repo");
            GithubRepository ret = new GithubRepository(parent.id);
            ret.workspaceGroup = parent.workspaceGroup;
            ret.labels = parent.labels;
            ret.logger = parent.logger;

            GHRepository repository = githubService.getRepositoryById(ret.id);
            ret.htmlUrl = repository.getHtmlUrl();
            ret.name = repository.getName();
            var pullRequests = ret.fetchPullRequests(githubService);
            ret.mergePullRequests(pullRequests);
            System.out.println("Refreshing repo finished");

            return Optional.of(ret);
        }
    }
}
