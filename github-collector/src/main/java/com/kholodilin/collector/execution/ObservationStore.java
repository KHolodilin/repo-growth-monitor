package com.kholodilin.collector.execution;
import com.kholodilin.collector.queue.JobQueue.Lease;
import java.util.Optional;
public interface ObservationStore {
    Optional<String> find(Lease lease,String key);
    void save(Lease lease,String key,String payload);
    void remove(Lease lease,String key);
    default java.time.Instant firstObserved(Lease lease){return java.time.Instant.now();}
}
