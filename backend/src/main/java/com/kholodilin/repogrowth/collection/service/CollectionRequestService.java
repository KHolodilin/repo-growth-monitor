package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.collector.contracts.CollectorProtocol;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.collection.configuration.OrchestrationProperties;
import com.kholodilin.repogrowth.collection.repository.CollectionRequestJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.CollectorSearchProperties;
import com.kholodilin.repogrowth.events.repository.GrowthEventSettingJdbcRepository;
import com.kholodilin.repogrowth.search.repository.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.topics.repository.TopicWatchJdbcRepository;
import com.kholodilin.repogrowth.topics.service.TopicWatchSync;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
public class CollectionRequestService {
  public record Created(UUID requestId, List<UUID> taskIds, boolean coalesced) {
    public Created {
      taskIds = List.copyOf(taskIds);
    }
  }

  public record ScheduledCounts(int repositories, int queries, int watches) {}

  private record Spec(TaskType type, String key, Parameters parameters, LocalDate date) {
    Spec(TaskType type, String key, Parameters parameters) {
      this(type, key, parameters, null);
    }

    LocalDate dateOr(LocalDate fallback) {
      return date != null ? date : fallback;
    }
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.credentials.service.CredentialVault credentialVault;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private CollectorNodeMonitor nodeMonitor;

  private final CollectionRequestJdbcRepository requests;
  private final TransactionTemplate tx;
  private final OrchestrationProperties properties;
  private final RepositoryJdbcRepository repositories;
  private final SearchQueryJdbcRepository queries;
  private final TopicWatchJdbcRepository watches;
  private final JsonMapper json;
  private final TopicWatchSync watchSync;
  private final GrowthEventSettingJdbcRepository eventSettings;
  private final CollectorSearchProperties search;

  public CollectionRequestService(
      CollectionRequestJdbcRepository requests,
      PlatformTransactionManager tm,
      OrchestrationProperties properties,
      RepositoryJdbcRepository repositories,
      SearchQueryJdbcRepository queries,
      TopicWatchJdbcRepository watches,
      JsonMapper json,
      TopicWatchSync watchSync,
      GrowthEventSettingJdbcRepository eventSettings,
      CollectorSearchProperties search) {
    this.requests = requests;
    this.tx = new TransactionTemplate(tm);
    this.properties = properties;
    this.watchSync = watchSync;
    this.eventSettings = eventSettings;
    this.repositories = repositories;
    this.queries = queries;
    this.watches = watches;
    this.json = json;
    this.search = search == null ? new CollectorSearchProperties(null, null) : search;
  }

  public Created manual(long repositoryId, LocalDate date) {
    Created created = create(repositoryId, date, null);
    repairIncompatibleSearchSortTips(repositoryId, date);
    return created;
  }

  public ScheduledCounts scheduled(LocalDate date, String slot) {
    int count = 0, queryCount = 0, watchCount = 0;
    for (var repository : repositories.findTracked()) {
      create(
          repository.id(),
          date,
          "scheduled:" + repository.id() + ":" + date + ":" + (slot == null ? "daily" : slot));
      count++;
      repairIncompatibleSearchSortTips(repository.id(), date);
      queryCount +=
          (int) queries.findByRepository(repository.id()).stream().filter(q -> q.enabled()).count();
      watchCount +=
          (int) watches.findByRepository(repository.id()).stream().filter(w -> w.enabled()).count();
    }
    return new ScheduledCounts(count, queryCount, watchCount);
  }

  public List<Created> refresh(LocalDate date) {
    // One explicit discovery task per owner/credential scope. The Collector never discovers work.
    var seeds = requests.discoverySeedRepositoryIds();
    if (!seeds.isEmpty()) return seeds.stream().map(id -> create(id, date, null, true)).toList();
    // Empty install: discover from vault credentials without inventing a seed repository.
    if (credentialVault == null) throw discoveryCredentialRequired();
    var refs = requests.activeCredentialRefs();
    if (refs.isEmpty()) throw discoveryCredentialRequired();
    return refs.stream().map(ref -> createCredentialDiscovery(ref, date)).toList();
  }

  /** The protocol requires an authorization scope for DISCOVER_REPOSITORIES. */
  private static ApiException discoveryCredentialRequired() {
    return ApiException.validation("Configure a credential before refreshing repositories");
  }

  private Created createCredentialDiscovery(String credentialRef, LocalDate date) {
    return tx.execute(status -> {
      requests.advisoryLock(Objects.hash("discover", credentialRef));
      AuthorizationScope authority = credentialVault.discoveryScope(credentialRef);
      var existing = requests.activeCredentialDiscovery(credentialRef);
      if (!existing.isEmpty()) {
        var row = existing.getFirst();
        long request = ((Number) row.get("id")).longValue();
        if (requests.countDiscoverTasks(request) > 0)
          return response(request, (UUID) row.get("public_id"), true);
        appendCredentialDiscovery(request, authority, date);
        return response(request, (UUID) row.get("public_id"), true);
      }
      AssignedNode assigned = ensureCollectorNode(properties.defaultPool(), credentialRef);
      Long nodeId = assigned.id();
      UUID requestId = UUID.randomUUID();
      Long request = requests.insertCredentialRequest(requestId, date, credentialRef);
      UUID taskId = UUID.randomUUID(), attemptId = UUID.randomUUID();
      Instant deadline =
          Instant.now()
              .plus(properties.jobTimeout())
              .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      Map<String, Object> frozenConfiguration = new TreeMap<>();
      frozenConfiguration.put("parameters", Parameters.repository());
      frozenConfiguration.put("authorization", authority);
      frozenConfiguration.put("growthEventSettings", Map.of());
      String frozen = json.writeValueAsString(frozenConfiguration);
      long revision =
          revision(TaskType.DISCOVER_REPOSITORIES + "credential:" + credentialRef + frozen);
      Long task =
          requests.insertDiscoverTask(
              taskId,
              request,
              "credential:" + credentialRef,
              json.writeValueAsString(Parameters.repository()),
              frozen,
              revision,
              attemptId,
              UUID.fromString(authority.context()),
              authority.credentialRef());
      requests.insertAttempt(attemptId, task, nodeId);
      var envelope =
          new JobRequest(
              CollectorProtocol.VERSION,
              requestId,
              taskId,
              attemptId,
              TaskType.DISCOVER_REPOSITORIES,
              null,
              date,
              revision,
              Parameters.repository(),
              properties.destinationRef(),
              deadline,
              authority);
      requests.insertDispatch(
          attemptId,
          OrchestrationProperties.endpoint(assigned.node()),
          json.writeValueAsString(envelope),
          deadline);
      return response(request, requestId, false);
    });
  }

  private void appendCredentialDiscovery(
      long request, AuthorizationScope authority, LocalDate date) {
    var parent = json.readValue(requests.firstDispatchPayload(request), JobRequest.class);
    UUID taskId = UUID.randomUUID(), attemptId = UUID.randomUUID();
    Map<String, Object> configuration = new TreeMap<>();
    configuration.put("parameters", Parameters.repository());
    configuration.put("authorization", authority);
    configuration.put("growthEventSettings", Map.of());
    String frozen = json.writeValueAsString(configuration);
    long revision =
        revision(
            TaskType.DISCOVER_REPOSITORIES + "credential:" + authority.credentialRef() + frozen);
    var envelope =
        new JobRequest(
            CollectorProtocol.VERSION,
            parent.requestId(),
            taskId,
            attemptId,
            TaskType.DISCOVER_REPOSITORIES,
            null,
            date,
            revision,
            Parameters.repository(),
            parent.destinationRef(),
            Instant.now().plus(properties.jobTimeout()),
            authority);
    long node = requests.nodeIdForAttempt(parent.attemptId());
    long task =
        requests.insertDiscoverTask(
            taskId,
            request,
            "credential:" + authority.credentialRef(),
            json.writeValueAsString(Parameters.repository()),
            frozen,
            revision,
            attemptId,
            UUID.fromString(authority.context()),
            authority.credentialRef());
    requests.insertAttempt(attemptId, task, node);
    requests.insertDispatchForNode(
        attemptId, node, json.writeValueAsString(envelope), envelope.deadlineAt());
  }

  private Created create(long repositoryId, LocalDate date, String triggerKey) {
    return create(repositoryId, date, triggerKey, false);
  }

  public Created searchCommand(long id, LocalDate date) {
    var query =
        queries.findById(id).orElseThrow(() -> ApiException.notFound("Search query not found"));
    Created created =
        create(
            query.repositoryId(),
            date,
            null,
            false,
            List.of(new Spec(TaskType.SEARCH_QUERY, "query:" + id, searchParameters(query), date)));
    repairIncompatibleSearchSortTips(query.repositoryId(), date);
    return created;
  }

  public Created searchAllCommand(long id, LocalDate date) {
    var specs =
        queries.findByRepository(id).stream()
            .filter(q -> q.enabled())
            .map(q -> new Spec(TaskType.SEARCH_QUERY, "query:" + q.id(), searchParameters(q), date))
            .toList();
    if (specs.isEmpty()) throw ApiException.validation("No enabled search queries");
    Created created = create(id, date, null, false, specs);
    repairIncompatibleSearchSortTips(id, date);
    return created;
  }

  public Created topicCommand(long id, LocalDate date) {
    var watch =
        watches.findById(id).orElseThrow(() -> ApiException.notFound("Topic watch not found"));
    return create(
        watch.repositoryId(),
        date,
        null,
        false,
        List.of(
            new Spec(
                TaskType.TOPIC_WATCH,
                "watch:" + id,
                new Parameters(
                    watch.searchQuery(),
                    watch.topic(),
                    watch.language(),
                    watch.resultLimit(),
                    watch.sort(),
                    watch.sortOrder(),
                    topicsPublic(watch.repositoryId())))));
  }

  public Created topicsCommand(long id, String scope, LocalDate date) {
    if (!Set.of("all", "language").contains(scope))
      throw ApiException.validation("Topic scope must be all or language");
    watchSync.sync(id);
    var specs =
        watches.findEnabledByRepository(id, scope.equals("all")).stream()
            .map(w ->
                new Spec(
                    TaskType.TOPIC_WATCH,
                    "watch:" + w.id(),
                    new Parameters(
                        w.searchQuery(),
                        w.topic(),
                        w.language(),
                        w.resultLimit(),
                        w.sort(),
                        w.sortOrder(),
                        topicsPublic(w.repositoryId()))))
            .toList();
    if (specs.isEmpty()) throw ApiException.validation("No enabled topic watches");
    return create(id, date, null, false, specs);
  }

  private Created create(long repositoryId, LocalDate date, String triggerKey, boolean refresh) {
    return create(repositoryId, date, triggerKey, refresh, null);
  }

  private Created create(
      long repositoryId, LocalDate date, String triggerKey, boolean refresh, List<Spec> requested) {
    return tx.execute(status -> {
      requests.advisoryLock(repositoryId);
      // Serialize creation without blocking history writers under the request lock.
      var rows = requests.repositoryWithOwner(repositoryId);
      if (rows.isEmpty()) throw ApiException.notFound("Repository not found");
      var repository = rows.getFirst();
      var existing = requests.existingRequest(repositoryId, triggerKey);
      if (!existing.isEmpty()) {
        var row = existing.getFirst();
        long request = ((Number) row.get("id")).longValue();
        LocalDate existingDate = businessDate(row.get("business_date"));
        boolean triggerHit = triggerKey != null && triggerKey.equals(row.get("trigger_key"));
        boolean active = "ACTIVE".equals(String.valueOf(row.get("state")));
        // One ACTIVE per repository — attach cross-day search tip repairs onto it with dated target
        // keys.
        if (triggerHit || active) {
          if (requested == null && !refresh) {
            String state = requests.lockRequestState(request);
            if (!"ACTIVE".equals(state)) {
              if (triggerKey != null) return response(request, (UUID) row.get("public_id"), true);
              return create(repositoryId, date, triggerKey, false, null);
            }
            watchSync.sync(repositoryId);
            for (var type :
                List.of(
                    TaskType.STATS,
                    TaskType.TRAFFIC,
                    TaskType.REFERRERS,
                    TaskType.POPULAR_PATHS,
                    TaskType.GROWTH_EVENTS))
              append(
                  request,
                  repositoryId,
                  new Spec(type, "repository", Parameters.repository(), date),
                  date);
            for (var query : queries.findByRepository(repositoryId))
              if (query.enabled())
                append(
                    request,
                    repositoryId,
                    datedSearchSpec(query.id(), searchParameters(query), date, existingDate),
                    date);
            for (var watch : watches.findByRepository(repositoryId))
              if (watch.enabled())
                append(
                    request,
                    repositoryId,
                    new Spec(
                        TaskType.TOPIC_WATCH,
                        "watch:" + watch.id(),
                        new Parameters(
                            watch.searchQuery(),
                            watch.topic(),
                            watch.language(),
                            watch.resultLimit(),
                            watch.sort(),
                            watch.sortOrder(),
                            topicsPublic(watch.repositoryId())),
                        date),
                    date);
          }
          if (requested != null) {
            String state = requests.lockRequestState(request);
            if (!state.equals("ACTIVE")) return create(repositoryId, date, null, false, requested);
            for (var spec : requested) {
              LocalDate taskDate = spec.dateOr(date);
              append(request, repositoryId, datedSpec(spec, taskDate, existingDate), taskDate);
            }
          }
          if (refresh) {
            String state = requests.lockRequestState(request);
            if (!state.equals("ACTIVE")) return create(repositoryId, date, null, true);
            if (requests.countDiscoverTasks(request) == 0) {
              var parent = json.readValue(requests.firstDispatchPayload(request), JobRequest.class);
              var taskId = UUID.randomUUID();
              var attemptId = UUID.randomUUID();
              if (credentialVault == null) throw discoveryCredentialRequired();
              var authority = credentialVault.scope(repositoryId);
              var payload =
                  new JobRequest(
                      1,
                      parent.requestId(),
                      taskId,
                      attemptId,
                      TaskType.DISCOVER_REPOSITORIES,
                      parent.repository(),
                      date,
                      parent.configurationRevision(),
                      Parameters.repository(),
                      parent.destinationRef(),
                      Instant.now().plus(properties.jobTimeout()),
                      authority);
              Long nodeId = requests.nodeIdForAttempt(parent.attemptId());
              Long task =
                  requests.insertDiscoverTaskWithRepository(
                      taskId,
                      request,
                      repositoryId,
                      payload.configurationRevision(),
                      attemptId,
                      UUID.fromString(authority.context()),
                      authority.credentialRef());
              requests.insertAttempt(attemptId, task, nodeId);
              requests.insertDispatch(
                  attemptId,
                  requests.nodeEndpoint(nodeId),
                  json.writeValueAsString(payload),
                  payload.deadlineAt());
            }
          }
          return response(request, (UUID) row.get("public_id"), true);
        }
      }
      String owner = (String) repository.get("owner_login"),
          githubId = repository.get("github_id").toString();
      AuthorizationScope authorization =
          credentialVault == null ? null : credentialVault.scope(repositoryId);
      if (refresh && authorization == null) throw discoveryCredentialRequired();
      // Stable spread within a pool. Repeated sends use the persisted assignment, never re-route.
      AssignedNode assigned = ensureCollectorNode(properties.poolFor(owner, githubId), githubId);
      Long nodeId = assigned.id();
      UUID requestId = UUID.randomUUID();
      Long request =
          requests.insertRepositoryRequest(
              requestId,
              repositoryId,
              triggerKey == null ? "MANUAL" : "SCHEDULED",
              triggerKey,
              date);
      var target = new RepositoryTarget(githubId, owner, (String) repository.get("name"));
      watchSync.sync(repositoryId);
      Map<String, Boolean> growthSettings = new TreeMap<>();
      for (var setting : eventSettings.ensureDefaults(repositoryId))
        growthSettings.put(setting.eventType(), setting.enabled());
      List<Spec> specs = new ArrayList<>();
      for (var type :
          List.of(
              TaskType.STATS,
              TaskType.TRAFFIC,
              TaskType.REFERRERS,
              TaskType.POPULAR_PATHS,
              TaskType.GROWTH_EVENTS))
        specs.add(new Spec(type, "repository", Parameters.repository(), date));
      for (var query : queries.findByRepository(repositoryId))
        if (query.enabled())
          specs.add(datedSearchSpec(query.id(), searchParameters(query), date, date));
      for (var watch : watches.findByRepository(repositoryId))
        if (watch.enabled())
          specs.add(
              new Spec(
                  TaskType.TOPIC_WATCH,
                  "watch:" + watch.id(),
                  new Parameters(
                      watch.searchQuery(),
                      watch.topic(),
                      watch.language(),
                      watch.resultLimit(),
                      watch.sort(),
                      watch.sortOrder(),
                      topicsPublic(watch.repositoryId())),
                  date));
      if (refresh) {
        specs.clear();
        specs.add(
            new Spec(TaskType.DISCOVER_REPOSITORIES, "repository", Parameters.repository(), date));
      }
      if (requested != null) {
        specs.clear();
        for (var spec : requested) specs.add(datedSpec(spec, spec.dateOr(date), date));
      }
      Instant deadline =
          Instant.now()
              .plus(properties.jobTimeout())
              .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      for (var spec : specs) {
        LocalDate taskDate = spec.dateOr(date);
        UUID taskId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        String parameters = json.writeValueAsString(spec.parameters());
        Map<String, Object> frozenConfiguration = new TreeMap<>();
        frozenConfiguration.put("repository", target);
        frozenConfiguration.put("parameters", spec.parameters());
        frozenConfiguration.put(
            "growthEventSettings",
            spec.type() == TaskType.GROWTH_EVENTS ? growthSettings : Map.of());
        if (authorization != null) frozenConfiguration.put("authorization", authorization);
        String frozen = json.writeValueAsString(frozenConfiguration);
        long revision = revision(spec.type() + spec.key() + frozen);
        Long task =
            requests.insertTask(
                taskId,
                request,
                repositoryId,
                spec.type().name(),
                spec.key(),
                parameters,
                frozen,
                revision,
                attemptId);
        if (authorization != null)
          requests.setTaskAuthorization(
              UUID.fromString(authorization.context()), authorization.credentialRef(), task);
        requests.insertAttempt(attemptId, task, nodeId);
        var envelope =
            new JobRequest(
                CollectorProtocol.VERSION,
                requestId,
                taskId,
                attemptId,
                spec.type(),
                target,
                taskDate,
                revision,
                spec.parameters(),
                properties.destinationRef(),
                deadline,
                authorization);
        requests.insertDispatch(
            attemptId,
            OrchestrationProperties.endpoint(assigned.node()),
            json.writeValueAsString(envelope),
            deadline);
      }
      return response(request, requestId, false);
    });
  }

  private record AssignedNode(long id, OrchestrationProperties.Node node) {}

  private AssignedNode ensureCollectorNode(String pool, String routingKey) {
    var candidates =
        properties.nodes().stream()
            .filter(n ->
                n.enabled()
                    && n.pool().equals(pool)
                    && (nodeMonitor == null || nodeMonitor.eligible(n.key())))
            .sorted(Comparator.comparing(OrchestrationProperties.Node::key))
            .toList();
    if (candidates.isEmpty())
      throw ApiException.serviceUnavailable("No collector is configured for the repository pool");
    var node = candidates.get(Math.floorMod(routingKey.hashCode(), candidates.size()));
    Long id =
        requests.upsertCollectorNode(
            node.key(), node.pool(), OrchestrationProperties.endpoint(node));
    return new AssignedNode(id, node);
  }

  private void append(long request, long repository, Spec spec, LocalDate date) {
    if (requests.countTasks(request, spec.type().name(), spec.key()) > 0) return;
    var parent = json.readValue(requests.firstDispatchPayload(request), JobRequest.class);
    var authority = credentialVault == null ? null : credentialVault.scope(repository);
    var taskId = UUID.randomUUID();
    var attemptId = UUID.randomUUID();
    Map<String, Object> configuration = new TreeMap<>();
    configuration.put("repository", parent.repository());
    configuration.put("parameters", spec.parameters());
    if (authority != null) configuration.put("authorization", authority);
    Map<String, Boolean> settings = new TreeMap<>();
    if (spec.type() == TaskType.GROWTH_EVENTS)
      for (var setting : eventSettings.ensureDefaults(repository))
        settings.put(setting.eventType(), setting.enabled());
    configuration.put("growthEventSettings", settings);
    String frozen = json.writeValueAsString(configuration);
    long revision = revision(spec.type() + spec.key() + frozen);
    var envelope =
        new JobRequest(
            1,
            parent.requestId(),
            taskId,
            attemptId,
            spec.type(),
            parent.repository(),
            date,
            revision,
            spec.parameters(),
            parent.destinationRef(),
            Instant.now().plus(properties.jobTimeout()),
            authority);
    long node = requests.nodeIdForAttempt(parent.attemptId());
    long task =
        requests.insertTask(
            taskId,
            request,
            repository,
            spec.type().name(),
            spec.key(),
            json.writeValueAsString(spec.parameters()),
            frozen,
            revision,
            attemptId);
    if (authority != null)
      requests.setTaskAuthorization(
          UUID.fromString(authority.context()), authority.credentialRef(), task);
    requests.insertAttempt(attemptId, task, node);
    requests.insertDispatchForNode(
        attemptId, node, json.writeValueAsString(envelope), envelope.deadlineAt());
  }

  /**
   * After Rank History prefers stars, re-collect recent tip days that only have Best Match
   * snapshots. Attaches onto the single ACTIVE request with {@code query:{id}@{date}} keys so
   * calendar tips refill.
   */
  private void repairIncompatibleSearchSortTips(long repositoryId, LocalDate asOf) {
    // Only when Rank History prefers stars; best_match must not force a stars refill.
    if (!"stars".equals(search.sortModeLabel())) return;
    tx.executeWithoutResult(status -> {
      requests.advisoryLock(repositoryId);
      var active = requests.activeRequest(repositoryId);
      if (active.isEmpty()) return;
      long request = ((Number) active.getFirst().get("id")).longValue();
      LocalDate requestDate = businessDate(active.getFirst().get("business_date"));
      List<LocalDate> tips =
          requests.incompatibleSearchTipDates(repositoryId, asOf.minusDays(14), asOf);
      for (LocalDate tip : tips) {
        for (var query : queries.findByRepository(repositoryId)) {
          if (!query.enabled() || !needsStarsRepair(query.id(), tip)) continue;
          append(
              request,
              repositoryId,
              datedSearchSpec(query.id(), searchParameters(query), tip, requestDate),
              tip);
        }
      }
    });
  }

  private boolean needsStarsRepair(long queryId, LocalDate tip) {
    return requests.needsStarsRepair(queryId, tip);
  }

  private Spec datedSearchSpec(
      long queryId, Parameters parameters, LocalDate taskDate, LocalDate requestDate) {
    return new Spec(
        TaskType.SEARCH_QUERY,
        searchTargetKey(queryId, taskDate, requestDate),
        parameters,
        taskDate);
  }

  private Spec datedSpec(Spec spec, LocalDate taskDate, LocalDate requestDate) {
    if (spec.type() != TaskType.SEARCH_QUERY)
      return new Spec(spec.type(), spec.key(), spec.parameters(), taskDate);
    return datedSearchSpec(targetId(spec.key()), spec.parameters(), taskDate, requestDate);
  }

  public static String searchTargetKey(long queryId, LocalDate taskDate, LocalDate requestDate) {
    return taskDate.equals(requestDate) ? "query:" + queryId : "query:" + queryId + "@" + taskDate;
  }

  public static long targetId(String targetKey) {
    String raw = targetKey.substring(targetKey.indexOf(':') + 1);
    int at = raw.indexOf('@');
    return Long.parseLong(at < 0 ? raw : raw.substring(0, at));
  }

  private static LocalDate businessDate(Object value) {
    if (value instanceof LocalDate local) return local;
    if (value instanceof java.sql.Date sql) return sql.toLocalDate();
    if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
    throw new IllegalStateException(
        "Unexpected business_date type: " + (value == null ? "null" : value.getClass().getName()));
  }

  public void cancelSearch(long query) {
    tx.executeWithoutResult(status -> {
      for (long request : requests.searchTaskRequestIds(query)) {
        requests.lockRequest(request);
        for (var task : requests.openSearchTasks(request, query)) {
          requests.cancelTask(task.get("id"));
          requests.cancelAttempt(task.get("current_attempt_id"));
          requests.blockDispatch(
              task.get("current_attempt_id"), OrchestrationStates.LAST_ERROR_DEFINITION_DELETED);
        }
        requests.cancelRequestIfIdle(request);
      }
    });
  }

  private Created response(long id, UUID publicId, boolean coalesced) {
    return new Created(publicId, requests.taskPublicIds(id), coalesced);
  }

  private boolean searchPublic(long id) {
    return requests.searchPublic(id);
  }

  private boolean topicsPublic(long id) {
    return requests.topicsPublic(id);
  }

  /**
   * Freeze the node-configured search sort into the assignment so Rank History matches collection.
   */
  private Parameters searchParameters(com.kholodilin.repogrowth.search.model.SearchQuery query) {
    return new Parameters(
        query.query(),
        null,
        null,
        query.resultLimit(),
        search.githubSort(),
        search.githubOrder(),
        searchPublic(query.id()));
  }

  private static long revision(String snapshot) {
    try {
      return ByteBuffer.wrap(
                  MessageDigest.getInstance("SHA-256")
                      .digest(snapshot.getBytes(StandardCharsets.UTF_8)))
              .getLong()
          & Long.MAX_VALUE;
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
