package com.kholodilin.bootstrap;

import com.kholodilin.collector.CollectorMemoryConfiguration;
import com.kholodilin.collector.CollectorModuleConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rgm.collector.enabled", havingValue = "true", matchIfMissing = true)
@Import({CollectorModuleConfiguration.class, CollectorMemoryConfiguration.class, com.kholodilin.collector.CollectorObservabilityConfiguration.class, com.kholodilin.collector.acceptance.CollectorHttpConfiguration.class, com.kholodilin.collector.credentials.CollectorCredentialConfiguration.class, com.kholodilin.collector.execution.CollectorExecutionConfiguration.class, com.kholodilin.collector.delivery.CollectorDeliveryConfiguration.class})
public class CollectorRuntimeConfiguration { }
