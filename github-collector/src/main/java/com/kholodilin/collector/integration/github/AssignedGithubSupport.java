package com.kholodilin.collector.integration.github;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.model.ExecutionSettings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub URL/GraphQL builders and response-shaped helpers for assigned collection. No checkpoint or
 * service types.
 */
@RequiredArgsConstructor
public final class AssignedGithubSupport {
  private final ExecutionSettings properties;
  private final JsonMapper json;

  public static final class Incomplete extends RuntimeException {
    public Incomplete() {
      super("Incomplete GitHub observation");
    }
  }

  public int maximumPages() {
    return properties.maximumPages();
  }

  public String repositoryPath(JobRequest job) {
    return "/repos/" + part(job.repository().owner()) + "/" + part(job.repository().name());
  }

  public String pagePath(String path, int page) {
    return path + (path.contains("?") ? "&" : "?") + "per_page=100&page=" + page;
  }

  public String socialPreviewBody(JobRequest job) {
    return json.writeValueAsString(
        Map.of(
            "query",
            "query($owner:String!,$name:String!){repository(owner:$owner,name:$name){usesCustomOpenGraphImage openGraphImageUrl}}",
            "variables",
            Map.of("owner", job.repository().owner(), "name", job.repository().name())));
  }

  public String searchPath(JobRequest job) {
    int limit = job.parameters().resultLimit() == null ? 50 : job.parameters().resultLimit();
    String query = job.parameters().query();
    if (job.parameters().publicOnly()) query = query + " is:public";
    String path =
        "/search/repositories?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&per_page="
            + limit;
    String sort = job.parameters().sort();
    if (sort != null && !sort.isBlank())
      path +=
          "&sort="
              + URLEncoder.encode(sort, StandardCharsets.UTF_8)
              + "&order="
              + URLEncoder.encode(
                  Objects.requireNonNullElse(job.parameters().order(), "desc"),
                  StandardCharsets.UTF_8);
    return path;
  }

  public String readmeCommitPath(String repo, String readmeName) {
    return repo
        + "/commits?per_page=1&path="
        + URLEncoder.encode(readmeName, StandardCharsets.UTF_8);
  }

  public void keep(Map<String, String> metadata, String key, JsonNode value) {
    if (value != null && !value.isNull() && !value.isMissingNode())
      metadata.put(key, json.writeValueAsString(value));
  }

  public long number(JsonNode node, String field) {
    if (node == null
        || !node.hasNonNull(field)
        || !node.get(field).isIntegralNumber()
        || node.get(field).asLong() < 0) throw new Incomplete();
    return node.get(field).asLong();
  }

  public Integer findPosition(JsonNode items, String id) {
    int position = 1;
    for (JsonNode item : items) {
      if (item.path("id").asText().equals(id)) return position;
      position++;
    }
    return null;
  }

  public List<String> contributorLogins(List<JsonNode> contributors) {
    return contributors.stream()
        .filter(c -> c.hasNonNull("login") && !c.get("login").asText().isBlank())
        .map(c -> c.get("login").asText().trim())
        .distinct()
        .sorted()
        .toList();
  }

  public List<TrafficDay> trafficDays(JsonNode views, JsonNode clones) {
    Map<LocalDate, Long[]> days = new TreeMap<>();
    for (JsonNode view : views.path("views")) {
      LocalDate date =
          Instant.parse(view.get("timestamp").asText()).atZone(ZoneOffset.UTC).toLocalDate();
      Long[] d = days.computeIfAbsent(date, k -> new Long[4]);
      d[0] = number(view, "count");
      d[1] = number(view, "uniques");
    }
    for (JsonNode clone : clones.path("clones")) {
      LocalDate date =
          Instant.parse(clone.get("timestamp").asText()).atZone(ZoneOffset.UTC).toLocalDate();
      Long[] d = days.computeIfAbsent(date, k -> new Long[4]);
      d[2] = number(clone, "count");
      d[3] = number(clone, "uniques");
    }
    List<TrafficDay> traffic = new ArrayList<>();
    days.forEach((date, d) -> traffic.add(new TrafficDay(date, d[0], d[1], d[2], d[3])));
    return traffic;
  }

  public List<SourceObservation> sourceRows(JsonNode data, boolean paths) {
    if (!data.isArray()) throw new Incomplete();
    List<SourceObservation> sources = new ArrayList<>();
    for (JsonNode row : data)
      sources.add(
          new SourceObservation(
              row.get(paths ? "path" : "referrer").asText(),
              row.hasNonNull("title") ? row.get("title").asText() : null,
              number(row, "count"),
              number(row, "uniques")));
    return sources;
  }

  public List<RankedRepository> rankings(JsonNode items) {
    List<RankedRepository> rankings = new ArrayList<>();
    int rank = 1;
    for (JsonNode row : items)
      rankings.add(
          new RankedRepository(
              row.get("id").asText(),
              row.get("full_name").asText(),
              rank++,
              number(row, "stargazers_count"),
              number(row, "forks_count")));
    return rankings;
  }

  public boolean allPublic(JsonNode items) {
    for (var item : items)
      if (!item.hasNonNull("private")
          || !item.get("private").isBoolean()
          || item.get("private").asBoolean()) return false;
    return true;
  }

  public JsonNode ownedRepositories(List<JsonNode> repositories, String ownerLogin) {
    return json.valueToTree(
        repositories.stream()
            .filter(item -> item.path("owner").path("login").asText().equalsIgnoreCase(ownerLogin))
            .toList());
  }

  public String writeArray(List<JsonNode> rows) {
    return json.writeValueAsString(rows);
  }

  private String part(String value) {
    return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
  }
}
