package com.kholodilin.collector.integration.github;

import com.kholodilin.collector.model.IncompleteObservationException;
import com.kholodilin.collector.model.github.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts GitHub {@link JsonNode} responses into typed observation models. No HTTP, queue,
 * checkpoint, service or repository access.
 */
@RequiredArgsConstructor
public final class GitHubObservationMapper {
  private final JsonMapper json;

  public RepositoryObservation repository(JsonNode remote) {
    requireObject(remote);
    Long watchers =
        remote.hasNonNull("subscribers_count")
            ? requiredNonNegativeLong(remote, "subscribers_count")
            : null;
    return new RepositoryObservation(
        requiredNonNegativeLong(remote, "stargazers_count"),
        requiredNonNegativeLong(remote, "forks_count"),
        watchers,
        json.writeValueAsString(remote));
  }

  public ContributorPage contributors(JsonNode data, int requestedPageSize) {
    requirePositivePageSize(requestedPageSize);
    JsonNode array = requireArrayNode(data);
    List<ContributorPage.ContributorEntry> items = new ArrayList<>();
    for (JsonNode row : array) {
      requireObject(row);
      items.add(new ContributorPage.ContributorEntry(optionalContributorLogin(row)));
    }
    // Full page ⇒ another page may exist; not a guarantee that more contributors are present.
    return new ContributorPage(
        items, array.size() == requestedPageSize, json.writeValueAsString(array));
  }

  public ContributorTotal contributorTotal(JsonNode count) {
    requireObject(count);
    return new ContributorTotal(requiredNonNegativeLong(count, "total"));
  }

  public OpaqueDocument optionalDocument(JsonNode node) {
    if (isAbsentJson(node)) return OpaqueDocument.absent();
    String name =
        node.hasNonNull("name") && node.get("name").isString() ? node.get("name").asText() : null;
    return OpaqueDocument.of(json.writeValueAsString(node), name);
  }

  public SocialPreview socialPreview(JsonNode preview) {
    if (isAbsentJson(preview) || preview.hasNonNull("errors")) return SocialPreview.unused();
    requireObject(preview);
    JsonNode repository = preview.path("data").path("repository");
    if (repository.isMissingNode() || repository.isNull()) return SocialPreview.unused();
    requireObject(repository);
    return new SocialPreview(true, json.writeValueAsString(repository));
  }

  /**
   * Presence probe for content checks. Expected inputs from {@link GitHubSourceReader}:
   *
   * <ul>
   *   <li>absent resource — {@code null} / JSON null (optional 404/403);
   *   <li>present resource — source marker {@code {"exists":true}} for {@code security:} / {@code
   *       issue-template:} keys.
   * </ul>
   */
  public boolean contentPresent(JsonNode node) {
    if (isAbsentJson(node)) return false;
    if (!node.isObject()
        || !node.hasNonNull("exists")
        || !node.get("exists").isBoolean()
        || !node.get("exists").asBoolean()) throw new IncompleteObservationException();
    return true;
  }

  public JsonArrayPage arrayPage(JsonNode data, int requestedPageSize) {
    requirePositivePageSize(requestedPageSize);
    JsonNode array = requireArrayNode(data);
    // Full page ⇒ another page may exist; not a guarantee that more items are present.
    return new JsonArrayPage(
        json.writeValueAsString(array), array.size(), array.size() == requestedPageSize);
  }

  public String ownedRepositoriesMetadata(JsonArrayPage page, String ownerLogin) {
    JsonNode array = requireArrayNode(json.readTree(page.rawJson()));
    List<JsonNode> owned = new ArrayList<>();
    for (JsonNode item : array) {
      requireObject(item);
      if (item.path("owner").path("login").asText().equalsIgnoreCase(ownerLogin)) owned.add(item);
    }
    return json.writeValueAsString(owned);
  }

  /** Concatenate opaque JSON array pages into one array string for Snapshot metadata. */
  public String mergeJsonArrays(List<String> pages) {
    if (pages == null || pages.isEmpty()) return "[]";
    var merged = json.createArrayNode();
    for (String page : pages) {
      JsonNode node = requireArrayNode(json.readTree(page));
      node.forEach(merged::add);
    }
    return json.writeValueAsString(merged);
  }

  public TrafficSeries views(JsonNode root) {
    return trafficSeries(root, "views");
  }

  public TrafficSeries clones(JsonNode root) {
    return trafficSeries(root, "clones");
  }

  public PopularSources referrers(JsonNode data) {
    return popular(data, "referrer");
  }

  public PopularSources popularPaths(JsonNode data) {
    return popular(data, "path");
  }

  public SearchObservation search(JsonNode data, String trackedGithubId) {
    requireObject(data);
    long total = requiredNonNegativeLong(data, "total_count");
    boolean incomplete = optionalIncompleteResults(data);
    JsonNode itemsNode = requiredArray(data, "items");
    List<SearchObservation.Hit> items = new ArrayList<>();
    boolean allPublic = true;
    Integer position = null;
    int rank = 1;
    for (JsonNode row : itemsNode) {
      requireObject(row);
      String id = requiredGithubId(row, "id");
      if (id.equals(trackedGithubId)) position = rank;
      // Unknown / invalid private must not confirm publicity in Hit or allPublic.
      PrivateVisibility visibility = privateVisibility(row);
      boolean isPrivate = visibility != PrivateVisibility.PUBLIC;
      if (visibility != PrivateVisibility.PUBLIC) allPublic = false;
      items.add(
          new SearchObservation.Hit(
              id,
              requiredText(row, "full_name"),
              requiredNonNegativeLong(row, "stargazers_count"),
              requiredNonNegativeLong(row, "forks_count"),
              isPrivate));
      rank++;
    }
    return new SearchObservation(
        total, incomplete, items, position, allPublic, json.writeValueAsString(data));
  }

  private TrafficSeries trafficSeries(JsonNode root, String arrayField) {
    requireObject(root);
    JsonNode series = requiredArray(root, arrayField);
    List<TrafficSeries.Bucket> buckets = new ArrayList<>();
    for (JsonNode row : series) {
      requireObject(row);
      LocalDate date = requiredUtcDate(row, "timestamp");
      buckets.add(
          new TrafficSeries.Bucket(
              date,
              requiredNonNegativeLong(row, "count"),
              requiredNonNegativeLong(row, "uniques")));
    }
    return new TrafficSeries(buckets);
  }

  private PopularSources popular(JsonNode data, String keyField) {
    JsonNode array = requireArrayNode(data);
    List<PopularSources.Entry> entries = new ArrayList<>();
    for (JsonNode row : array) {
      requireObject(row);
      String title =
          row.hasNonNull("title") && row.get("title").isString() ? row.get("title").asText() : null;
      if (row.has("title") && !row.get("title").isNull() && !row.get("title").isString())
        throw new IncompleteObservationException();
      entries.add(
          new PopularSources.Entry(
              requiredText(row, keyField),
              title,
              requiredNonNegativeLong(row, "count"),
              requiredNonNegativeLong(row, "uniques")));
    }
    return new PopularSources(entries);
  }

  private static void requirePositivePageSize(int requestedPageSize) {
    if (requestedPageSize <= 0) throw new IncompleteObservationException();
  }

  private static boolean isAbsentJson(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode();
  }

  private static void requireObject(JsonNode node) {
    if (isAbsentJson(node) || !node.isObject()) throw new IncompleteObservationException();
  }

  private static JsonNode requireArrayNode(JsonNode node) {
    if (isAbsentJson(node) || !node.isArray()) throw new IncompleteObservationException();
    return node;
  }

  private static JsonNode requiredArray(JsonNode node, String field) {
    if (node == null || !node.has(field)) throw new IncompleteObservationException();
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || !value.isArray())
      throw new IncompleteObservationException();
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) throw new IncompleteObservationException();
    JsonNode value = node.get(field);
    if (!value.isString()) throw new IncompleteObservationException();
    String text = value.asText().trim();
    if (text.isBlank()) throw new IncompleteObservationException();
    return text;
  }

  private static String requiredGithubId(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) throw new IncompleteObservationException();
    JsonNode value = node.get(field);
    if (value.isIntegralNumber()) {
      if (!value.canConvertToLong()) throw new IncompleteObservationException();
      return Long.toString(value.asLong());
    }
    if (value.isString()) {
      String text = value.asText().trim();
      if (text.isBlank()) throw new IncompleteObservationException();
      return text;
    }
    throw new IncompleteObservationException();
  }

  private static long requiredNonNegativeLong(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) throw new IncompleteObservationException();
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong())
      throw new IncompleteObservationException();
    long number = value.asLong();
    if (number < 0) throw new IncompleteObservationException();
    return number;
  }

  private static LocalDate requiredUtcDate(JsonNode node, String field) {
    String timestamp = requiredText(node, field);
    try {
      return Instant.parse(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    } catch (DateTimeParseException error) {
      throw new IncompleteObservationException();
    }
  }

  private static boolean optionalIncompleteResults(JsonNode data) {
    if (!data.has("incomplete_results")) return false;
    JsonNode value = data.get("incomplete_results");
    if (value == null || value.isNull() || !value.isBoolean())
      throw new IncompleteObservationException();
    return value.asBoolean();
  }

  /**
   * Compatible Hit mapping sets {@code isPrivate=true} for unknown visibility so no returned field
   * claims a confirmed public repository.
   */
  private static PrivateVisibility privateVisibility(JsonNode row) {
    if (!row.has("private") || row.get("private").isNull()) return PrivateVisibility.UNKNOWN;
    JsonNode value = row.get("private");
    if (!value.isBoolean()) return PrivateVisibility.UNKNOWN;
    return value.asBoolean() ? PrivateVisibility.PRIVATE : PrivateVisibility.PUBLIC;
  }

  private enum PrivateVisibility {
    PUBLIC,
    PRIVATE,
    UNKNOWN
  }

  /** Anonymous contributors may omit {@code login}; blank or missing login becomes {@code null}. */
  private static String optionalContributorLogin(JsonNode row) {
    if (!row.has("login") || row.get("login").isNull()) return null;
    JsonNode login = row.get("login");
    if (!login.isString()) throw new IncompleteObservationException();
    String text = login.asText().trim();
    return text.isBlank() ? null : text;
  }
}
