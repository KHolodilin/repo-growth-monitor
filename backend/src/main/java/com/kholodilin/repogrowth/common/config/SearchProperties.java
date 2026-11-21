package com.kholodilin.repogrowth.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "search")
public record SearchProperties(
    int workers,
    int defaultResultLimit,
    Duration enrichmentTtl,
    int gapLookbackDays,
    Activity activity) {
  public SearchProperties {
    if (enrichmentTtl == null) {
      enrichmentTtl = Duration.ofHours(24);
    }
    if (gapLookbackDays <= 0) {
      gapLookbackDays = 30;
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
