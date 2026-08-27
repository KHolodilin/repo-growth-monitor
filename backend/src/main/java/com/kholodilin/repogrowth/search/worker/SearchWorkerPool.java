package com.kholodilin.repogrowth.search.worker;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class SearchWorkerPool {

    private final SearchWorker searchWorker;
    private final SearchProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public SearchWorkerPool(SearchWorker searchWorker, SearchProperties properties) {
        this.searchWorker = searchWorker;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int workers = Math.max(0, properties.workers());
        if (workers == 0) {
            log.info("Search workers disabled");
            return;
        }
        running.set(true);
        executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("search-worker-" + UUID.randomUUID().toString().substring(0, 8));
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < workers; i++) {
            String workerId = "search-" + UUID.randomUUID();
            executor.submit(() -> loop(workerId));
        }
        log.info("Started search workers count={}", workers);
    }

    private void loop(String workerId) {
        while (running.get()) {
            try {
                boolean worked = searchWorker.poll(workerId);
                if (!worked) {
                    TimeUnit.SECONDS.sleep(1);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.error("Search worker loop error", ex);
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
