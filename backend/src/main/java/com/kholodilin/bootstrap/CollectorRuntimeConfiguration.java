package com.kholodilin.bootstrap;

import com.kholodilin.collector.CollectorModuleConfiguration;
import com.kholodilin.collector.CollectorStorageConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rgm.collector.enabled", havingValue = "true", matchIfMissing = true)
@Import({CollectorModuleConfiguration.class, CollectorStorageConfiguration.class, com.kholodilin.collector.acceptance.CollectorHttpConfiguration.class, com.kholodilin.collector.credentials.CollectorCredentialConfiguration.class, com.kholodilin.collector.execution.CollectorExecutionConfiguration.class, com.kholodilin.collector.delivery.CollectorDeliveryConfiguration.class})
public class CollectorRuntimeConfiguration { }
