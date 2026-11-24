package com.kholodilin.collector.model.github;

/**
 * Opaque JSON array page (issues/pulls/releases/repos).
 *
 * <p>{@code hasNext} means the page looks full so another page <em>may</em> exist; it is not a
 * guarantee that more items are present.
 */
public record JsonArrayPage(String rawJson, int size, boolean hasNext) {}
