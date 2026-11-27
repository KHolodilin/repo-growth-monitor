package com.kholodilin.repogrowth.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class OwnerDirectoryReadModelIT extends AbstractPostgresTest {

  @Autowired OwnerDirectoryReadModel readModel;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void seed() {
    wipeRepositoryData(jdbc);
    long ownerA =
        jdbc.sql(
                """
                INSERT INTO github_owner (github_id, login, owner_type)
                VALUES (501, 'Alpha', 'USER') RETURNING id
                """)
            .query(Long.class)
            .single();
    long ownerB =
        jdbc.sql(
                """
                INSERT INTO github_owner (github_id, login, owner_type)
                VALUES (502, 'Beta', 'ORGANIZATION') RETURNING id
                """)
            .query(Long.class)
            .single();
    insertRepo(ownerA, 601, "one");
    insertRepo(ownerA, 602, "two");
    insertRepo(ownerB, 603, "other");
  }

  @Test
  void listsAllOwnersGroupedWithRepositoryIds() {
    List<OwnerDirectoryReadModel.OwnerRow> rows = readModel.list(null);
    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().login()).isEqualTo("Alpha");
    assertThat(rows.getFirst().repositoryIds()).hasSize(2);
    assertThat(rows.get(1).login()).isEqualTo("Beta");
    assertThat(rows.get(1).repositoryIds()).hasSize(1);
  }

  @Test
  void filtersByExactLoginCaseInsensitively() {
    List<OwnerDirectoryReadModel.OwnerRow> rows = readModel.list("alpha");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().login()).isEqualTo("Alpha");
    assertThat(rows.getFirst().repositoryIds()).hasSize(2);
    assertThat(readModel.list("missing")).isEmpty();
  }

  private void insertRepo(long ownerId, long githubId, String name) {
    jdbc.sql(
            """
            INSERT INTO repository (
              github_id, owner_id, name, full_name, visibility, default_branch,
              stars, watchers, forks, open_issues, contributors, tracking_enabled, account_accessible
            ) VALUES (
              :githubId, :ownerId, :name, :fullName, 'PUBLIC', 'main',
              1, 1, 0, 0, 1, TRUE, TRUE
            )
            """)
        .param("githubId", githubId)
        .param("ownerId", ownerId)
        .param("name", name)
        .param("fullName", "owner/" + name)
        .update();
  }
}
