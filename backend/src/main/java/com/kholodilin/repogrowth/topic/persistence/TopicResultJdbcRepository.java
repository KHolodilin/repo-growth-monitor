package com.kholodilin.repogrowth.topic.persistence;

import com.kholodilin.repogrowth.topic.domain.TopicResult;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TopicResultJdbcRepository {

    static final RowMapper<TopicResult> MAPPER = (rs, rowNum) -> new TopicResult(
            rs.getLong("id"),
            rs.getLong("topic_run_id"),
            rs.getInt("position"),
            rs.getLong("github_repository_id"),
            rs.getString("full_name"),
            rs.getString("owner"),
            rs.getInt("stars"),
            rs.getInt("watchers"),
            rs.getInt("forks"),
            rs.getString("language"),
            rs.getString("description"),
            rs.getString("html_url")
    );

    private final JdbcClient jdbcClient;

    public TopicResultJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void replaceAll(long topicRunId, List<TopicResult> results) {
        jdbcClient.sql("DELETE FROM topic_result WHERE topic_run_id = :topicRunId")
                .param("topicRunId", topicRunId)
                .update();
        for (TopicResult result : results) {
            jdbcClient.sql("""
                            INSERT INTO topic_result (
                                topic_run_id, position, github_repository_id, full_name, owner,
                                stars, watchers, forks, language, description, html_url
                            ) VALUES (
                                :topicRunId, :position, :githubRepositoryId, :fullName, :owner,
                                :stars, :watchers, :forks, :language, :description, :htmlUrl
                            )
                            """)
                    .param("topicRunId", topicRunId)
                    .param("position", result.position())
                    .param("githubRepositoryId", result.githubRepositoryId())
                    .param("fullName", result.fullName())
                    .param("owner", result.owner())
                    .param("stars", result.stars())
                    .param("watchers", result.watchers())
                    .param("forks", result.forks())
                    .param("language", result.language())
                    .param("description", result.description())
                    .param("htmlUrl", result.htmlUrl())
                    .update();
        }
    }

    public List<TopicResult> findByRun(long topicRunId) {
        return jdbcClient.sql("SELECT * FROM topic_result WHERE topic_run_id = :topicRunId ORDER BY position")
                .param("topicRunId", topicRunId)
                .query(MAPPER)
                .list();
    }
}
