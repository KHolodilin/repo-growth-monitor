package com.kholodilin.repogrowth.collection.collector;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubTrafficClonesResponse;
import com.kholodilin.repogrowth.github.model.GitHubTrafficDay;
import com.kholodilin.repogrowth.github.model.GitHubTrafficViewsResponse;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Component
public class TrafficCollector implements Collector {

    private final GitHubClient gitHubClient;
    private final TrafficJdbcRepository trafficJdbcRepository;
    private final TransactionTemplate transactionTemplate;

    public TrafficCollector(
            GitHubClient gitHubClient,
            TrafficJdbcRepository trafficJdbcRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.gitHubClient = gitHubClient;
        this.trafficJdbcRepository = trafficJdbcRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public CollectionJobType type() {
        return CollectionJobType.TRAFFIC;
    }

    @Override
    public void collect(CollectionContext context) {
        String owner = context.ownerLogin();
        String name = context.repository().name();
        GitHubTrafficViewsResponse views = gitHubClient.getViews(owner, name);
        GitHubTrafficClonesResponse clones = gitHubClient.getClones(owner, name);

        Map<LocalDate, int[]> merged = new HashMap<>();
        for (GitHubTrafficDay day : views.days()) {
            merged.put(day.date(), new int[]{day.count(), day.uniques(), 0, 0});
        }
        for (GitHubTrafficDay day : clones.days()) {
            int[] values = merged.computeIfAbsent(day.date(), key -> new int[]{0, 0, 0, 0});
            values[2] = day.count();
            values[3] = day.uniques();
        }
        transactionTemplate.executeWithoutResult(status -> {
            for (Map.Entry<LocalDate, int[]> entry : merged.entrySet()) {
                int[] values = entry.getValue();
                trafficJdbcRepository.upsertDaily(
                        context.repository().id(),
                        entry.getKey(),
                        values[0],
                        values[1],
                        values[2],
                        values[3]
                );
            }
        });
    }
}
