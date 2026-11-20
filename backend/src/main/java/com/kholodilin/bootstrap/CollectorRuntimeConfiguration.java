package com.kholodilin.bootstrap;

import com.kholodilin.collector.configuration.CollectorMemoryConfiguration;
import com.kholodilin.collector.configuration.CollectorModuleConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rgm.collector.enabled", havingValue = "true", matchIfMissing = true)
@Import({CollectorModuleConfiguration.class, CollectorMemoryConfiguration.class, com.kholodilin.collector.configuration.CollectorObservabilityConfiguration.class, com.kholodilin.collector.configuration.CollectorHttpConfiguration.class, com.kholodilin.collector.configuration.CollectorCredentialConfiguration.class, com.kholodilin.collector.configuration.CollectorExecutionConfiguration.class, com.kholodilin.collector.configuration.CollectorDeliveryConfiguration.class})
public class CollectorRuntimeConfiguration { }
