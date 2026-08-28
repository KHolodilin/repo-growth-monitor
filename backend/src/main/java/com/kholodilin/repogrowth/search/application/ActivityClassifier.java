package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class ActivityClassifier {

    private final SearchProperties searchProperties;
    private final Clock clock;

    public ActivityClassifier(SearchProperties searchProperties, Clock clock) {
        this.searchProperties = searchProperties;
        this.clock = clock;
    }

    public ActivityStatus classify(Instant activityAt) {
        return classify(false, activityAt);
    }

    public ActivityStatus classify(boolean archived, Instant activityAt) {
        if (archived) {
            return ActivityStatus.ARCHIVED;
        }
        if (activityAt == null) {
            return ActivityStatus.UNKNOWN;
        }
        long days = Duration.between(activityAt, Instant.now(clock)).toDays();
        if (days <= searchProperties.activity().activeDays()) {
            return ActivityStatus.ACTIVE;
        }
        if (days <= searchProperties.activity().lowActivityDays()) {
            return ActivityStatus.LOW_ACTIVITY;
        }
        return ActivityStatus.INACTIVE;
    }
}
