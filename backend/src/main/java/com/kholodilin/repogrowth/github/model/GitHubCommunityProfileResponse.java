package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommunityProfileResponse(
        GitHubCommunityFiles files
) {
    public static GitHubCommunityProfileResponse empty() {
        return new GitHubCommunityProfileResponse(GitHubCommunityFiles.empty());
    }

    public GitHubCommunityFiles filesOrEmpty() {
        return files == null ? GitHubCommunityFiles.empty() : files;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommunityFiles(
            Object readme,
            Object license,
            @JsonProperty("code_of_conduct") Object codeOfConduct,
            @JsonProperty("code_of_conduct_file") Object codeOfConductFile,
            Object contributing,
            @JsonProperty("issue_template") Object issueTemplate,
            @JsonProperty("pull_request_template") Object pullRequestTemplate
    ) {
        public static GitHubCommunityFiles empty() {
            return new GitHubCommunityFiles(null, null, null, null, null, null, null);
        }

        public boolean hasReadme() {
            return present(readme);
        }

        public boolean hasLicense() {
            return present(license);
        }

        public boolean hasCodeOfConduct() {
            return present(codeOfConduct) || present(codeOfConductFile);
        }

        public boolean hasContributing() {
            return present(contributing);
        }

        public boolean hasIssueTemplate() {
            return present(issueTemplate);
        }

        public boolean hasPullRequestTemplate() {
            return present(pullRequestTemplate);
        }

        private static boolean present(Object value) {
            return value != null;
        }
    }
}
