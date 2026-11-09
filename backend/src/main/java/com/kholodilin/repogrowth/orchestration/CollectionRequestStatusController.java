package com.kholodilin.repogrowth.orchestration;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.kholodilin.repogrowth.common.api.ApiException;
@RestController @RequestMapping("/api/v1/admin/collection-requests")
public class CollectionRequestStatusController {
 private final JdbcTemplate jdbc;public CollectionRequestStatusController(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @GetMapping("/{id}") public Object status(@PathVariable UUID id){var rows=jdbc.queryForList("SELECT public_id::text AS \"requestId\",state,created_at AS \"createdAt\",completed_at AS \"completedAt\" FROM collection_request WHERE public_id=?",id);if(rows.isEmpty())throw ApiException.notFound("Collection request not found");var response=rows.getFirst();response.put("tasks",jdbc.queryForList("SELECT t.public_id::text AS \"jobId\",t.task_type AS type,t.state,t.current_attempt_id::text AS \"attemptId\" FROM collection_task t JOIN collection_request r ON r.id=t.request_id WHERE r.public_id=? ORDER BY t.id",id));return response;}
}
