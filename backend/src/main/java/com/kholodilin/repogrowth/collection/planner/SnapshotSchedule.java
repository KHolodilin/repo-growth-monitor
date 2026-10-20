package com.kholodilin.repogrowth.collection.planner;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class SnapshotSchedule {

    public List<LocalTime> slots(LocalTime from, LocalTime to, Duration step) {
        if (from == null || to == null || step == null || step.isZero() || step.isNegative() || !to.isAfter(from)) {
            return List.of();
        }
        List<LocalTime> slots = new ArrayList<>();
        LocalTime cursor = from;
        while (cursor.isBefore(to)) {
            slots.add(cursor);
            LocalTime next = cursor.plus(step);
            if (!next.isAfter(cursor)) {
                break;
            }
            cursor = next;
        }
        return List.copyOf(slots);
    }

    public Optional<LocalTime> due(LocalTime now, LocalTime from, LocalTime to, Duration step) {
        if (now == null || now.isBefore(from) || !now.isBefore(to)) {
            return Optional.empty();
        }
        LocalTime due = null;
        for (LocalTime slot : slots(from, to, step)) {
            if (!now.isBefore(slot)) {
                due = slot;
            }
        }
        return Optional.ofNullable(due);
    }
}
