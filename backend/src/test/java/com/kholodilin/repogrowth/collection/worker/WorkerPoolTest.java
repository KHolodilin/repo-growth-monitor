package com.kholodilin.repogrowth.collection.worker;

import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.worker.SearchWorker;
import com.kholodilin.repogrowth.search.worker.SearchWorkerPool;
import com.kholodilin.repogrowth.topic.worker.TopicWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerPoolTest {

    @Test
    void disabledPoolsDoNotStartThreads() {
        new CollectionWorkerPool(mock(CollectionWorker.class), collection(0)).start();
        new SearchWorkerPool(mock(SearchWorker.class), mock(TopicWorker.class), search(0)).start();
    }

    @Test
    void collectionLoopSurvivesAnErrorAndStops() throws Exception {
        CollectionWorker worker = mock(CollectionWorker.class);
        CollectionWorkerPool pool = new CollectionWorkerPool(worker, collection(1));
        AtomicInteger calls = new AtomicInteger();
        when(worker.poll(anyString())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                throw new IllegalStateException("boom");
            }
            running(pool).set(false);
            return true;
        });
        pool.start();
        Thread.sleep(50);
        interruptNamed("collection-worker-");
        await(pool);
    }

    @Test
    void searchLoopSleepsWhenIdleThenStops() throws Exception {
        SearchWorker searchWorker = mock(SearchWorker.class);
        TopicWorker topicWorker = mock(TopicWorker.class);
        SearchWorkerPool pool = new SearchWorkerPool(searchWorker, topicWorker, search(1));
        when(searchWorker.poll(anyString())).thenReturn(false);
        when(topicWorker.poll(anyString())).thenAnswer(invocation -> {
            running(pool).set(false);
            return false;
        });
        pool.start();
        Thread.sleep(50);
        interruptNamed("search-worker-");
        await(pool);
    }

    private static void await(Object pool) throws Exception {
        ExecutorService executor = (ExecutorService) field(pool, "executor");
        executor.shutdownNow();
        executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void interruptNamed(String prefix) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(prefix)) {
                thread.interrupt();
            }
        }
    }

    private static AtomicBoolean running(Object pool) throws Exception {
        return (AtomicBoolean) field(pool, "running");
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static CollectionProperties collection(int workers) {
        return new CollectionProperties(workers, Duration.ofMinutes(1), new CollectionProperties.Planner(LocalTime.MIDNIGHT, LocalTime.NOON, Duration.ofMinutes(5)));
    }

    private static SearchProperties search(int workers) {
        return new SearchProperties(workers, 50, Duration.ofHours(1), 30, new SearchProperties.Activity(30, 180));
    }
}
