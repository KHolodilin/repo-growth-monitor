package com.kholodilin.repogrowth.collection.persistence;

import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerSlotJdbcRepositoryIT extends AbstractPostgresTest {

    @Autowired
    PlannerSlotJdbcRepository repository;
    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clean() {
        jdbcClient.sql("DELETE FROM planner_snapshot_slot").update();
    }

    @Test
    void savesAndReplacesTheSlotForADay() {
        LocalDate date = LocalDate.of(2026, 10, 20);

        assertThat(repository.find(date)).isEmpty();
        repository.save(date, LocalTime.of(6, 0));
        assertThat(repository.find(date)).contains(LocalTime.of(6, 0));

        repository.save(date, LocalTime.of(9, 0));
        assertThat(repository.find(date)).contains(LocalTime.of(9, 0));
        assertThat(repository.find(date.plusDays(1))).isEmpty();
    }
}
