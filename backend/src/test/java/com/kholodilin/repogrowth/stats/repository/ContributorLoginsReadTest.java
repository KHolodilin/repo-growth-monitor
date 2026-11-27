package com.kholodilin.repogrowth.stats.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ContributorLoginsReadTest {

  @Test
  void readLoginsCoversCollectedEmptyBrokenAndObjectArrays() throws Exception {
    assertThat(StatsJdbcRepository.readLogins(false, null)).isNull();
    assertThat(StatsJdbcRepository.readLogins(true, null)).isEmpty();

    Array strings = mock(Array.class);
    when(strings.getArray()).thenReturn(new String[] {"alice", null, "bob"});
    assertThat(StatsJdbcRepository.readLogins(true, strings)).containsExactly("alice", "bob");

    Array objects = mock(Array.class);
    when(objects.getArray()).thenReturn(new Object[] {"carol", null});
    assertThat(StatsJdbcRepository.readLogins(true, objects)).containsExactly("carol");

    Array other = mock(Array.class);
    when(other.getArray()).thenReturn(42);
    assertThat(StatsJdbcRepository.readLogins(true, other)).isEmpty();

    Array broken = mock(Array.class);
    when(broken.getArray()).thenThrow(new SQLException("boom"));
    assertThat(StatsJdbcRepository.readLogins(true, broken)).isEmpty();
  }
}
