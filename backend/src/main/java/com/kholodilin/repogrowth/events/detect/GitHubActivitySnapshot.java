package com.kholodilin.repogrowth.events.detect;

import com.kholodilin.repogrowth.collection.integration.github.GitHubContributorItem;
import com.kholodilin.repogrowth.collection.integration.github.GitHubIssueItem;
import com.kholodilin.repogrowth.collection.integration.github.GitHubPullItem;
import com.kholodilin.repogrowth.collection.integration.github.GitHubReleaseItem;
import com.kholodilin.repogrowth.collection.integration.github.GitHubRepositoryResponse;
import java.time.Instant;
import java.util.List;

public record GitHubActivitySnapshot(
    GitHubRepositoryResponse repository,
    String readmeText,
    String readmeSha,
    Instant readmeCommittedAt,
    List<GitHubIssueItem> issues,
    List<GitHubPullItem> pulls,
    List<GitHubReleaseItem> releases,
    List<GitHubContributorItem> contributors,
    /** Null when this collection could not read the social preview. */
    Boolean customSocialPreview,
    String socialPreviewUrl) {
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
