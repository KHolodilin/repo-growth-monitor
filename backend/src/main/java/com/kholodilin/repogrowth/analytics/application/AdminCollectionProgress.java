package com.kholodilin.repogrowth.analytics.application;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
/** Admin operational projection only; Public APIs never use this payload. */
@Component
public class AdminCollectionProgress {
 private final NamedParameterJdbcTemplate jdbc;private final JsonMapper json;
 public AdminCollectionProgress(JdbcTemplate jdbc,JsonMapper json){this.jdbc=new NamedParameterJdbcTemplate(jdbc);this.json=json;}
 public Object apply(DashboardResponse dashboard){if(dashboard==null||dashboard.repositories().isEmpty())return dashboard;var ids=dashboard.repositories().stream().map(row->row.id()).toList();var tasks=jdbc.queryForList("SELECT r.repository_id,t.task_type,t.state FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE r.state='ACTIVE' AND r.repository_id IN (:ids) ORDER BY t.id",Map.of("ids",ids));if(tasks.isEmpty())return dashboard;ObjectNode result=json.valueToTree(dashboard);int successful=0;for(var task:tasks)if(task.get("state").equals("SUCCEEDED"))successful++;
 var active=json.createObjectNode();active.put("status","RUNNING");active.put("successfulJobs",successful);active.put("plannedJobs",tasks.size());result.set("activeCollection",active);
 for(JsonNode row:result.path("repositories")){long repository=row.path("id").asLong();var scoped=tasks.stream().filter(task->((Number)task.get("repository_id")).longValue()==repository).toList();if(scoped.isEmpty())continue;var mutable=(ObjectNode)row;mutable.put("collectionStatus","RUNNING");var jobs=json.createArrayNode();for(var task:scoped){var job=json.createObjectNode();String type=(String)task.get("task_type"),state=(String)task.get("state");job.put("jobType",type.equals("STATS")?"REPOSITORY_STATS":type);job.put("status",state.equals("SUCCEEDED")?"SUCCESS":Set.of("READY","ACCEPTED","RETRY").contains(state)?"PLANNED":state);jobs.add(job);}mutable.set("jobs",jobs);}return result;
 }
}
