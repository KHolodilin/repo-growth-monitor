package com.kholodilin.repogrowth.orchestration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({OrchestrationProperties.class,RecoveryProperties.class})
public class OrchestrationConfiguration {
    @org.springframework.context.annotation.Bean
    @ConditionalOnProperty(name="rgm.orchestration.recovery.enabled",havingValue="true")
    CollectorNodeClient collectorNodeClient(OrchestrationProperties properties,tools.jackson.databind.json.JsonMapper json){return new CollectorNodeClient(properties,json);}
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${rgm.orchestration.enabled:false} && ${spring.task.scheduling.enabled:true}")
    static class DispatchScheduling { }
}
