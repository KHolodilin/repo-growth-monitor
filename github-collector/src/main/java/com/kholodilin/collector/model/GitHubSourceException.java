package com.kholodilin.collector.model;

/**
 * Semantic GitHub collection failure. Integration code maps transport/HTTP outcomes to a subclass;
 * the service layer decides retry, complete or dead-letter without seeing a status code.
 */
public abstract class GitHubSourceException extends RuntimeException {
  protected GitHubSourceException(String message) {
    super(message);
  }
}
