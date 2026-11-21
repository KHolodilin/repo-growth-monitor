package com.kholodilin.collector.configuration;

import com.github.benmanes.caffeine.cache.Ticker;
import com.kholodilin.collector.integration.credentials.CredentialCache;
import com.kholodilin.collector.integration.credentials.CredentialResolver;
import com.kholodilin.collector.integration.credentials.HttpCredentialResolver;
import com.kholodilin.collector.model.CredentialSettings;
import com.kholodilin.collector.service.CredentialInvalidationService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "collector.credentials.enabled", havingValue = "true")
@EnableConfigurationProperties(CollectorCredentialProperties.class)
public class CollectorCredentialConfiguration {
  @Bean
  CredentialSettings credentialSettings(CollectorCredentialProperties properties) {
    return properties.toSettings();
  }

  @Bean
  CredentialResolver credentialResolver(CredentialSettings settings) {
    return new HttpCredentialResolver(settings, HttpCredentialResolver.restClient(settings));
  }

  @Bean
  CredentialCache credentialCache(CredentialResolver resolver, CredentialSettings settings) {
    return new CredentialCache(resolver, settings, Clock.systemUTC(), Ticker.systemTicker());
  }

  @Bean
  CredentialInvalidationService credentialInvalidationService(CredentialCache cache) {
    return new CredentialInvalidationService(cache);
  }
}
