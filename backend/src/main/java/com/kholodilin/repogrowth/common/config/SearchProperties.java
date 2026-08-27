package com.kholodilin.repogrowth.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "search")
public record SearchProperties(
        int workers,
        int defaultResultLimit
) {
}
