package com.kholodilin.repogrowth.common.config;

import com.kholodilin.repogrowth.common.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    @Bean
    Clock clock(AppProperties appProperties) {
        return Clock.system(ZoneId.of(appProperties.timezone()));
    }
}
