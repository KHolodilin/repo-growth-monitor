package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubReadmeResponse(
        String content,
        String encoding,
        String name
) {
    public String decodedText() {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (encoding == null || encoding.isBlank() || "utf-8".equalsIgnoreCase(encoding)) {
            return content;
        }
        if ("base64".equalsIgnoreCase(encoding)) {
            byte[] decoded = Base64.getMimeDecoder().decode(content);
            return new String(decoded, StandardCharsets.UTF_8);
        }
        return content;
    }
}
