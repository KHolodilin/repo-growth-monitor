package com.kholodilin.repogrowth.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "search")
public record SearchProperties(
        int workers,
        int defaultResultLimit,
        Duration enrichmentTtl,
        Activity activity
) {
    public SearchProperties {
        if (enrichmentTtl == null) {
            enrichmentTtl = Duration.ofHours(24);
        }
        if (activity == null) {
            activity = new Activity(30, 180);
        }
    }

    public record Activity(int activeDays, int lowActivityDays) {
        public Activity {
            if (activeDays <= 0) {
                activeDays = 30;
            }
            if (lowActivityDays <= 0) {
                lowActivityDays = 180;
            }
        }
    }
}
