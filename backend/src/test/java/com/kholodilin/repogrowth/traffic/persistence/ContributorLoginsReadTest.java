package com.kholodilin.repogrowth.traffic.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContributorLoginsReadTest {

    @Test
    void readLoginsCoversCollectedEmptyBrokenAndObjectArrays() throws Exception {
        assertThat(TrafficJdbcRepository.readLogins(false, null)).isNull();
        assertThat(TrafficJdbcRepository.readLogins(true, null)).isEmpty();

        Array strings = mock(Array.class);
        when(strings.getArray()).thenReturn(new String[]{"alice", null, "bob"});
        assertThat(TrafficJdbcRepository.readLogins(true, strings)).containsExactly("alice", "bob");

        Array objects = mock(Array.class);
        when(objects.getArray()).thenReturn(new Object[]{"carol", null});
        assertThat(TrafficJdbcRepository.readLogins(true, objects)).containsExactly("carol");

        Array other = mock(Array.class);
        when(other.getArray()).thenReturn(42);
        assertThat(TrafficJdbcRepository.readLogins(true, other)).isEmpty();

        Array broken = mock(Array.class);
        when(broken.getArray()).thenThrow(new SQLException("boom"));
        assertThat(TrafficJdbcRepository.readLogins(true, broken)).isEmpty();
    }
}
