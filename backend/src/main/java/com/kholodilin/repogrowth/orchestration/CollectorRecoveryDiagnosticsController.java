package com.kholodilin.repogrowth.orchestration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
/** Admin-only diagnostics. Never returns tokens, source payloads or configured secret endpoints. */
@RestController
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
public class CollectorRecoveryDiagnosticsController {
    private final JdbcTemplate jdbc;
    public CollectorRecoveryDiagnosticsController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping("/api/v1/admin/collectors")
    public Map<String,Object> status(){
        return Map.of("nodes",jdbc.queryForList("SELECT node_key,state,consecutive_failures,last_checked_at,last_success_at FROM collector_node_health ORDER BY node_key"),
            "attempts",jdbc.queryForList("SELECT n.node_key,a.state,a.last_collector_state,count(*) AS count,max(extract(epoch FROM clock_timestamp()-a.assigned_at)) AS oldest_age_seconds FROM collection_attempt a JOIN collector_node n ON n.id=a.node_id WHERE a.state IN ('ASSIGNED','ACCEPTED','RUNNING') GROUP BY n.node_key,a.state,a.last_collector_state ORDER BY n.node_key,a.state"));
    }
}
