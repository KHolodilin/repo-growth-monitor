package com.kholodilin.repogrowth.collection.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

@Repository
public class PlannerSlotJdbcRepository {

    private final JdbcClient jdbcClient;

    public PlannerSlotJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<LocalTime> find(LocalDate businessDate) {
        return jdbcClient.sql("""
                        SELECT slot_time
                        FROM planner_snapshot_slot
                        WHERE business_date = :businessDate
                        """)
                .param("businessDate", businessDate)
                .query((rs, rowNum) -> rs.getObject("slot_time", LocalTime.class))
                .optional();
    }

    public void save(LocalDate businessDate, LocalTime slotTime) {
        jdbcClient.sql("""
                        INSERT INTO planner_snapshot_slot (business_date, slot_time)
                        VALUES (:businessDate, :slotTime)
                        ON CONFLICT (business_date) DO UPDATE
                        SET slot_time = EXCLUDED.slot_time,
                            updated_at = NOW()
                        """)
                .param("businessDate", businessDate)
                .param("slotTime", slotTime)
                .update();
    }
}
