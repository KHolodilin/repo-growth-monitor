package com.kholodilin.collector.model;

/** A single GitHub response exceeded the payload bound; the assignment must be narrowed. */
public final class GitHubResponseTooLargeException extends GitHubSourceException {
  public GitHubResponseTooLargeException() {
    super("GitHub response exceeds the payload limit");
  }
}
