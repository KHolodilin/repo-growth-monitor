package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTrafficDay(
        Instant timestamp,
        int count,
        int uniques
) {
    public LocalDate date() {
        return timestamp.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
