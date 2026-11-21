package com.kholodilin.collector.configuration;

import com.kholodilin.collector.service.CollectorAcceptanceService;
import com.kholodilin.collector.service.CollectorLifecycleGate;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Collector module boundary. Explicit component registration so acceptance beans load both in the
 * standalone app and when RGM {@code @Import}s this configuration.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackageClasses = CollectorAcceptanceService.class,
    useDefaultFilters = false,
    includeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {CollectorAcceptanceService.class, CollectorLifecycleGate.class}))
public class CollectorModuleConfiguration {}
