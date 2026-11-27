package com.kholodilin.repogrowth.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "rgm.mock.seed-northstar=true")
class NorthstarMockSeedIT extends AbstractPostgresTest {

  @Autowired NorthstarMockSeed seed;
  @Autowired JdbcClient jdbc;

  @Test
  void seedsNorthstarOwnerReposAndThirtyDaysOfMetrics() {
    assertThat(
            jdbc.sql("SELECT login FROM github_owner WHERE github_id = :id")
                .param("id", NorthstarMockSeed.OWNER_GITHUB_ID)
                .query(String.class)
                .single())
        .isEqualTo(NorthstarMockSeed.OWNER_LOGIN);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM repository r
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(10L);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM traffic_daily t
                    JOIN repository r ON r.id = t.repository_id
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(10L * NorthstarMockSeed.TRAFFIC_DAYS);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM repository_daily_stats s
                    JOIN repository r ON r.id = s.repository_id
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(10L * NorthstarMockSeed.TRAFFIC_DAYS);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM repository r
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login AND r.public_dashboard_enabled
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(9L);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM traffic_referrer_snapshot s
                    JOIN repository r ON r.id = s.repository_id
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(10L * NorthstarMockSeed.TRAFFIC_DAYS * 5L);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM traffic_path_snapshot s
                    JOIN repository r ON r.id = s.repository_id
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login AND s.service_path = FALSE
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(10L * NorthstarMockSeed.TRAFFIC_DAYS * 3L);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM search_query q
                    JOIN repository r ON r.id = q.repository_id
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login AND q.public_enabled
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(9L * 2L);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM topic_watch w
                    JOIN repository r ON r.id = w.repository_id
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login AND w.language IS NOT NULL AND w.enabled
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(10L * 3L);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM growth_event e
                    JOIN repository r ON r.id = e.repository_id
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login AND e.category = 'PROMOTION'
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isGreaterThanOrEqualTo(20L);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM github_owner
                    WHERE github_id >= :base AND github_id < :end
                    """)
                .param("base", NorthstarMockSeed.DIRECTORY_OWNER_GITHUB_ID_BASE)
                .param(
                    "end",
                    NorthstarMockSeed.DIRECTORY_OWNER_GITHUB_ID_BASE
                        + NorthstarMockSeed.DIRECTORY_OWNER_COUNT)
                .query(Long.class)
                .single())
        .isEqualTo((long) NorthstarMockSeed.DIRECTORY_OWNER_COUNT);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM repository r
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.github_id >= :base AND o.github_id < :end
                      AND r.public_dashboard_enabled AND r.visibility = 'PUBLIC'
                    """)
                .param("base", NorthstarMockSeed.DIRECTORY_OWNER_GITHUB_ID_BASE)
                .param(
                    "end",
                    NorthstarMockSeed.DIRECTORY_OWNER_GITHUB_ID_BASE
                        + NorthstarMockSeed.DIRECTORY_OWNER_COUNT)
                .query(Long.class)
                .single())
        .isEqualTo(45L); // 15 owners × 2 repos + 15 owners × 1 repo

    // Second pass exercises ON CONFLICT update paths.
    seed.run(new DefaultApplicationArguments());
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM repository r
                    JOIN github_owner o ON o.id = r.owner_id
                    WHERE o.login = :login
                    """)
                .param("login", NorthstarMockSeed.OWNER_LOGIN)
                .query(Long.class)
                .single())
        .isEqualTo(10L);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM github_owner
                    WHERE github_id >= :base AND github_id < :end
                    """)
                .param("base", NorthstarMockSeed.DIRECTORY_OWNER_GITHUB_ID_BASE)
                .param(
                    "end",
                    NorthstarMockSeed.DIRECTORY_OWNER_GITHUB_ID_BASE
                        + NorthstarMockSeed.DIRECTORY_OWNER_COUNT)
                .query(Long.class)
                .single())
        .isEqualTo((long) NorthstarMockSeed.DIRECTORY_OWNER_COUNT);
  }
}
