package com.kholodilin.collector.queue;

import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Privileged maintenance, separate from claiming work. Never drops data. */
public final class HistoryPartitions {
    private final JdbcTemplate jdbc;
    public HistoryPartitions(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public static String name(YearMonth month) { return "history_" + month.format(DateTimeFormatter.ofPattern("uuuu_MM")); }
    public void ensure(YearMonth month) {
        if (month.getYear() < 2000 || month.getYear() > 9999) throw new IllegalArgumentException("Unsupported history year");
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(210001)", Object.class);
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + name(month) + " PARTITION OF collector_job_history FOR VALUES FROM ('"
            + month.atDay(1) + " 00:00:00+00') TO ('" + month.plusMonths(1).atDay(1) + " 00:00:00+00')");
        });
    }
    public void ensureCurrentAndNext() {
        YearMonth month = YearMonth.now(ZoneOffset.UTC); ensure(month); ensure(month.plusMonths(1));
    }
    /** Explicit operator action; cutoff is an exclusive retention boundary in UTC. */
    public void detach(YearMonth month, Instant retentionCutoff) {
        if (month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).isAfter(retentionCutoff)
            || !month.isBefore(YearMonth.now(ZoneOffset.UTC))) throw new IllegalArgumentException("Partition is within retention");
        jdbc.execute("ALTER TABLE collector_job_history DETACH PARTITION " + name(month));
    }
}
