package com.kholodilin.collector.credentials;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="collector.credentials.enabled",havingValue="true")
@EnableConfigurationProperties(CollectorCredentialProperties.class)
public class CollectorCredentialConfiguration {
    @Bean CredentialResolver credentialResolver(CollectorCredentialProperties properties) {return new HttpCredentialResolver(properties);}
    @Bean CredentialCache credentialCache(CredentialResolver resolver,CollectorCredentialProperties properties) {return new CredentialCache(resolver,properties,Clock.systemUTC(),Ticker.systemTicker());}
}
