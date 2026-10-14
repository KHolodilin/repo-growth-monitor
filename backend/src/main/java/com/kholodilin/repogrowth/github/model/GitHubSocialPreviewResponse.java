package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubSocialPreviewResponse(Data data, List<GraphqlError> errors) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(RepositoryNode repository) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RepositoryNode(boolean usesCustomOpenGraphImage, String openGraphImageUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GraphqlError(String message) {
    }
}
