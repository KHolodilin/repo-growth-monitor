package com.kholodilin.repogrowth.event.detect;

import com.kholodilin.repogrowth.github.model.GitHubContributorItem;
import com.kholodilin.repogrowth.github.model.GitHubIssueItem;
import com.kholodilin.repogrowth.github.model.GitHubPullItem;
import com.kholodilin.repogrowth.github.model.GitHubReleaseItem;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;

import java.util.List;

public record GitHubActivitySnapshot(
        GitHubRepositoryResponse repository,
        String readmeText,
        String readmeSha,
        List<GitHubIssueItem> issues,
        List<GitHubPullItem> pulls,
        List<GitHubReleaseItem> releases,
        List<GitHubContributorItem> contributors
) {
    public List<GitHubIssueItem> issuesOrEmpty() {
        return issues == null ? List.of() : issues;
    }

    public List<GitHubPullItem> pullsOrEmpty() {
        return pulls == null ? List.of() : pulls;
    }

    public List<GitHubReleaseItem> releasesOrEmpty() {
        return releases == null ? List.of() : releases;
    }

    public List<GitHubContributorItem> contributorsOrEmpty() {
        return contributors == null ? List.of() : contributors;
    }
}
