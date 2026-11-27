package com.kholodilin.repogrowth.search.model;

import java.time.LocalDate;

/** Successful search-run position observation for portfolio/highlight consumers. */
public record SuccessfulRank(
    long repositoryId, String fullName, String queryName, LocalDate date, int position) {}
