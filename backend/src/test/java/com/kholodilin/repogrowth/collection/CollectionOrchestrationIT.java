package com.kholodilin.repogrowth.collection;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.planner.CollectionPlanner;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.worker.CollectionWorker;
import com.kholodilin.repogrowth.collection.worker.RepositoryLock;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubPathResponse;
import com.kholodilin.repogrowth.github.model.GitHubReferrerResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.github.model.GitHubTrafficClonesResponse;
import com.kholodilin.repogrowth.github.model.GitHubTrafficDay;
import com.kholodilin.repogrowth.github.model.GitHubTrafficViewsResponse;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.planner.SearchPlanner;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class CollectionOrchestrationIT extends AbstractPostgresTest {

    @MockitoBean
    GitHubClient gitHubClient;

    @Autowired
    CollectionPlanner collectionPlanner;
    @Autowired
    SearchPlanner searchPlanner;
    @Autowired
    CollectionWorker collectionWorker;
    @Autowired
    CollectionJobJdbcRepository jobRepository;
    @Autowired
    CollectionRunJdbcRepository runRepository;
    @Autowired
    GitHubOwnerJdbcRepository ownerJdbcRepository;
    @Autowired
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Autowired
    SearchQueryJdbcRepository searchQueryJdbcRepository;
    @Autowired
    SearchRunJdbcRepository searchRunJdbcRepository;
    @Autowired
    PlanningWindow planningWindow;
    @Autowired
    RepositoryLock repositoryLock;
    @Autowired
    JdbcClient jdbcClient;
    @Autowired
    TrafficJdbcRepository trafficJdbcRepository;

    private Repository repository;

    @BeforeEach
    void seed() {
        jdbcClient.sql("DELETE FROM search_result").update();
        jdbcClient.sql("DELETE FROM search_run").update();
        jdbcClient.sql("DELETE FROM search_query").update();
        jdbcClient.sql("DELETE FROM collection_job").update();
        jdbcClient.sql("DELETE FROM collection_run").update();
        jdbcClient.sql("DELETE FROM traffic_path_snapshot").update();
        jdbcClient.sql("DELETE FROM traffic_referrer_snapshot").update();
        jdbcClient.sql("DELETE FROM traffic_daily").update();
        jdbcClient.sql("DELETE FROM repository_daily_stats").update();
        GitHubOwner owner = ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
        repository = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null, 200L, owner.id(), "demo", "acme/demo", "demo repo", "PUBLIC", "main", "Java",
                false, false, 10, 0, 2, 1, 0, false, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null
        ));
        repositoryJdbcRepository.setTracking(repository.id(), true);
        repository = repositoryJdbcRepository.findById(repository.id()).orElseThrow();
        stubGithubSuccess();
    }

    @Test
    void duplicatePlannerDoesNotCreateDuplicateJobs() {
        LocalDate date = planningWindow.businessDate();
        collectionPlanner.planAll(date);
        collectionPlanner.planAll(date);
        CollectionRun run = runRepository.find(repository.id(), date).orElseThrow();
        List<CollectionJob> jobs = jobRepository.findByRun(run.id());
        assertThat(jobs).hasSize(4);
        assertThat(jobs.stream().map(CollectionJob::jobType).distinct()).hasSize(4);
    }

    @Test
    void partialCollectionKeepsSuccessfulJobs() {
        when(gitHubClient.getPopularPaths(anyString(), anyString()))
                .thenThrow(GitHubException.api(500, true, null, "boom"));
        LocalDate date = planningWindow.businessDate();
        CollectionRun run = collectionPlanner.planRepository(repository.id(), date);
        drainCollection(8);
        List<CollectionJob> jobs = jobRepository.findByRun(run.id());
        assertThat(jobs.stream().filter(job -> job.jobType() == CollectionJobType.TRAFFIC).findFirst().orElseThrow().status())
                .isEqualTo(CollectionJobStatus.SUCCESS);
        assertThat(jobs.stream().filter(job -> job.jobType() == CollectionJobType.POPULAR_PATHS).findFirst().orElseThrow().status())
                .isIn(CollectionJobStatus.RETRY, CollectionJobStatus.FAILED, CollectionJobStatus.RUNNING);
        CollectionRun updated = runRepository.findById(run.id()).orElseThrow();
        assertThat(updated.status().name()).isNotEqualTo("SUCCESS");
    }

    @Test
    void collectNowRequeuesFailedJobsWithoutDuplicatingSuccess() {
        LocalDate date = planningWindow.businessDate();
        CollectionRun run = collectionPlanner.planRepository(repository.id(), date);
        CollectionJob traffic = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.TRAFFIC)
                .findFirst()
                .orElseThrow();
        CollectionJob paths = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.POPULAR_PATHS)
                .findFirst()
                .orElseThrow();
        jobRepository.markSuccess(traffic.id());
        jobRepository.markFailed(paths.id(), "GITHUB_ERROR", "boom");
        trafficJdbcRepository.upsertDaily(repository.id(), date, 1, 1, 0, 0);
        runRepository.refreshAggregates(run.id());

        CollectionRun retried = collectionPlanner.planRepository(repository.id(), date);
        List<CollectionJob> jobs = jobRepository.findByRun(retried.id());
        assertThat(jobs).hasSize(4);
        assertThat(jobs.stream().filter(job -> job.jobType() == CollectionJobType.TRAFFIC).findFirst().orElseThrow().status())
                .isEqualTo(CollectionJobStatus.SUCCESS);
        assertThat(jobs.stream().filter(job -> job.jobType() == CollectionJobType.POPULAR_PATHS).findFirst().orElseThrow().status())
                .isEqualTo(CollectionJobStatus.READY);
    }

    @Test
    void plannerRequeuesTrafficWhenRecentDaysAreMissing() {
        LocalDate date = planningWindow.businessDate();
        CollectionRun run = collectionPlanner.planRepository(repository.id(), date);
        CollectionJob traffic = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.TRAFFIC)
                .findFirst()
                .orElseThrow();
        jobRepository.markSuccess(traffic.id());
        trafficJdbcRepository.upsertDaily(repository.id(), date.minusDays(2), 4, 2, 1, 1);
        runRepository.refreshAggregates(run.id());

        collectionPlanner.planRepository(repository.id(), date);
        CollectionJob refreshed = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.TRAFFIC)
                .findFirst()
                .orElseThrow();
        assertThat(refreshed.status()).isEqualTo(CollectionJobStatus.READY);
    }

    @Test
    void plannerDoesNotRequeueTrafficWhenCurrentDayExists() {
        LocalDate date = planningWindow.businessDate();
        CollectionRun run = collectionPlanner.planRepository(repository.id(), date);
        CollectionJob traffic = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.TRAFFIC)
                .findFirst()
                .orElseThrow();
        jobRepository.markSuccess(traffic.id());
        trafficJdbcRepository.upsertDaily(repository.id(), date, 4, 2, 1, 1);
        runRepository.refreshAggregates(run.id());

        collectionPlanner.planRepository(repository.id(), date);
        CollectionJob unchanged = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.TRAFFIC)
                .findFirst()
                .orElseThrow();
        assertThat(unchanged.status()).isEqualTo(CollectionJobStatus.SUCCESS);
    }

    @Test
    void collectNowRefreshesTrafficEvenWhenCurrentDayExists() {
        LocalDate date = planningWindow.businessDate();
        CollectionRun run = collectionPlanner.planRepository(repository.id(), date);
        CollectionJob traffic = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.TRAFFIC)
                .findFirst()
                .orElseThrow();
        jobRepository.markSuccess(traffic.id());
        trafficJdbcRepository.upsertDaily(repository.id(), date, 4, 2, 1, 1);
        runRepository.refreshAggregates(run.id());

        collectionPlanner.planRepository(repository.id(), date, true);
        CollectionJob refreshed = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.TRAFFIC)
                .findFirst()
                .orElseThrow();
        assertThat(refreshed.status()).isEqualTo(CollectionJobStatus.READY);
    }

    @Test
    void repositoryStatsCollectorPersistsWatchersFromSubscribersCount() {
        collectionPlanner.planRepository(repository.id(), planningWindow.businessDate());
        drainCollection(8);
        Repository updated = repositoryJdbcRepository.findById(repository.id()).orElseThrow();
        assertThat(updated.watchers()).isEqualTo(5);
        assertThat(updated.stars()).isEqualTo(11);
        assertThat(updated.lastCommitAt()).isEqualTo(Instant.parse("2026-08-28T07:54:00Z"));
    }

    @Test
    void retryableFailureSchedulesRetry() {
        when(gitHubClient.getViews(anyString(), anyString()))
                .thenThrow(GitHubException.api(503, true, null, "unavailable"));
        CollectionRun run = collectionPlanner.planRepository(repository.id(), planningWindow.businessDate());
        collectionWorker.poll("test-worker");
        CollectionJob traffic = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.jobType() == CollectionJobType.TRAFFIC)
                .findFirst()
                .orElseThrow();
        assertThat(traffic.status()).isEqualTo(CollectionJobStatus.RETRY);
        assertThat(traffic.nextAttemptAt()).isNotNull();
    }

    @Test
    void searchPlannerIsIdempotentAndIndependent() {
        var first = searchQueryJdbcRepository.insert(repository.id(), "q1", "outbox", true, 50);
        var second = searchQueryJdbcRepository.insert(repository.id(), "q2", "kafka outbox", true, 50);
        LocalDate date = planningWindow.businessDate();
        searchPlanner.planAll(date);
        searchPlanner.planAll(date);
        assertThat(searchRunJdbcRepository.find(first.id(), date)).isPresent();
        assertThat(searchRunJdbcRepository.find(second.id(), date)).isPresent();
        searchPlanner.planAll(date);
        assertThat(searchRunJdbcRepository.find(first.id(), date)).isPresent();
    }

    @Test
    void manualSearchRunDoesNotDuplicateActiveRun() {
        var query = searchQueryJdbcRepository.insert(repository.id(), "q1", "outbox", true, 50);
        LocalDate date = planningWindow.businessDate();
        long first = searchPlanner.planQuery(query.id(), repository.id(), date);
        long second = searchPlanner.planQuery(query.id(), repository.id(), date);
        assertThat(second).isEqualTo(first);
        assertThat(searchRunJdbcRepository.find(query.id(), date)).isPresent();
    }

    @Test
    void expiredLeaseIsReclaimed() {
        CollectionRun run = collectionPlanner.planRepository(repository.id(), planningWindow.businessDate());
        CollectionJob job = jobRepository.findByRun(run.id()).getFirst();
        jdbcClient.sql("""
                        UPDATE collection_job
                        SET status = 'RUNNING',
                            locked_by = 'dead-worker',
                            locked_until = NOW() - INTERVAL '1 minute'
                        WHERE id = :id
                        """)
                .param("id", job.id())
                .update();
        boolean processed = collectionWorker.poll("reclaimer");
        assertThat(processed).isTrue();
    }

    @Test
    void repositoryLockPreventsConcurrentGithubWork() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        Thread holder = new Thread(() -> {
            try (var connection = repositoryLock.openConnection()) {
                assertThat(repositoryLock.tryLock(connection, repository.id())).isTrue();
                started.countDown();
                hold.await(5, TimeUnit.SECONDS);
                repositoryLock.unlock(connection, repository.id());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        holder.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        CollectionRun run = collectionPlanner.planRepository(repository.id(), planningWindow.businessDate());
        boolean processed = collectionWorker.poll("other-worker");
        assertThat(processed).isTrue();
        CollectionJob claimed = jobRepository.findByRun(run.id()).stream()
                .filter(job -> job.status() != CollectionJobStatus.RUNNING)
                .findFirst()
                .orElseThrow();
        assertThat(claimed.status()).isIn(CollectionJobStatus.READY, CollectionJobStatus.RETRY);
        hold.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(concurrent.get()).isZero();
    }

    private void drainCollection(int times) {
        for (int i = 0; i < times; i++) {
            collectionWorker.poll("it-worker-" + i);
        }
    }

    private void stubGithubSuccess() {
        GitHubOwnerResponse owner = new GitHubOwnerResponse(100L, "acme", "User", null, null);
        when(gitHubClient.getViews(anyString(), anyString())).thenReturn(new GitHubTrafficViewsResponse(
                8, 3, List.of(new GitHubTrafficDay(Instant.parse("2026-08-20T00:00:00Z"), 8, 3))
        ));
        when(gitHubClient.getClones(anyString(), anyString())).thenReturn(new GitHubTrafficClonesResponse(
                2, 1, List.of(new GitHubTrafficDay(Instant.parse("2026-08-20T00:00:00Z"), 2, 1))
        ));
        when(gitHubClient.getReferrers(anyString(), anyString()))
                .thenReturn(List.of(new GitHubReferrerResponse("github.com", 4, 2)));
        when(gitHubClient.getPopularPaths(anyString(), anyString()))
                .thenReturn(List.of(new GitHubPathResponse("/acme/demo", "demo", 4, 2)));
        when(gitHubClient.getRepository(anyString(), anyString())).thenReturn(new GitHubRepositoryResponse(
                200L, "demo", "acme/demo", "demo repo", false, "public", "main", "Java", false, false,
                11, 5, 3, 1, "https://github.com/acme/demo", Instant.parse("2024-01-01T00:00:00Z"), Instant.now(), Instant.now(), owner
        ));
        when(gitHubClient.countContributors(anyString(), anyString())).thenReturn(4);
        when(gitHubClient.latestCommitAt(anyString(), anyString()))
                .thenReturn(Optional.of(Instant.parse("2026-08-28T07:54:00Z")));
        when(gitHubClient.searchRepositories(anyString(), anyInt())).thenReturn(
                new com.kholodilin.repogrowth.github.model.GitHubSearchResponse(1, List.of())
        );
    }
}
