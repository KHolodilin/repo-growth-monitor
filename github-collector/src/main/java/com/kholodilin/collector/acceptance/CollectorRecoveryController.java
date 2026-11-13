package com.kholodilin.collector.acceptance;
import com.kholodilin.collector.queue.JobQueue;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
@RestController
public final class CollectorRecoveryController {
    public record Confirmation(UUID eventId) { }
    private final JobQueue queue;private final JsonMapper json=JsonMapper.builder().build();
    private final org.springframework.beans.factory.ObjectProvider<com.kholodilin.collector.execution.AssignedJobExecutor> executor;
    public CollectorRecoveryController(JobQueue queue,org.springframework.beans.factory.ObjectProvider<com.kholodilin.collector.execution.AssignedJobExecutor> executor){this.queue=queue;this.executor=executor;}
    @GetMapping("/internal/v1/status") public Map<String,Object> status(Authentication auth){Map<String,Object> values=new LinkedHashMap<>(queue.diagnostics(auth.getName()));values.put("schemaVersion",1);values.put("executionEnabled",executor.getIfAvailable()!=null);values.put("accepting",executor.getIfAvailable()!=null);values.put("capabilities",List.of("durable-jobs","status","result-recovery"));return values;}
    @GetMapping("/internal/v1/jobs/by-attempt/{id}") public ResponseEntity<?> byAttempt(@PathVariable UUID id,Authentication auth){return queue.statusByAttempt(id,auth.getName()).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
    @GetMapping("/internal/v1/jobs/{id}") public ResponseEntity<?> job(@PathVariable UUID id,Authentication auth){return queue.status(id,auth.getName()).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
    @GetMapping("/internal/v1/jobs/{id}/result") public ResponseEntity<?> result(@PathVariable UUID id,Authentication auth){return queue.result(id,auth.getName()).map(value->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(json.readTree(value))).orElseGet(()->ResponseEntity.notFound().build());}
    @PostMapping("/internal/v1/jobs/{id}/delivery/ack") public ResponseEntity<Void> confirm(@PathVariable UUID id,@RequestBody Confirmation request,Authentication auth){return queue.confirmDelivery(id,auth.getName(),request.eventId())?ResponseEntity.noContent().build():ResponseEntity.notFound().build();}
    @PostMapping("/internal/v1/jobs/{id}/delivery/retry") public ResponseEntity<Void> retry(@PathVariable UUID id,Authentication auth){return queue.requeueDelivery(id,auth.getName())?ResponseEntity.noContent().build():ResponseEntity.notFound().build();}
}
