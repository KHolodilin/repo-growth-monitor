package com.kholodilin.repogrowth.collection.model;

import com.kholodilin.collector.contracts.CollectorProtocol.JobRequest;

/** Claimed pending collector dispatch ready for transport. */
public record DispatchClaim(
    long id,
    String owner,
    long generation,
    String nodeKey,
    String endpoint,
    JobRequest request,
    int sendCount) {}
