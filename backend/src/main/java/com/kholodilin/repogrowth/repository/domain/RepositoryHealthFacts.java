package com.kholodilin.repogrowth.repository.domain;

public record RepositoryHealthFacts(
        String homepage,
        boolean hasReadme,
        boolean readmeHasH1,
        boolean readmeHasName,
        boolean hasLicense,
        boolean hasCodeOfConduct,
        boolean hasContributing,
        boolean hasSecurityPolicy,
        boolean hasIssueTemplate,
        boolean hasPullRequestTemplate
) {
    public static RepositoryHealthFacts empty() {
        return new RepositoryHealthFacts(null, false, false, false, false, false, false, false, false, false);
    }
}
