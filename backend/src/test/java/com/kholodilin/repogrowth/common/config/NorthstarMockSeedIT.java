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
  }
}
