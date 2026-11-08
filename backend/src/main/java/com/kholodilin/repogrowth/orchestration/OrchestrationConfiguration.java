package com.kholodilin.repogrowth.orchestration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(OrchestrationProperties.class)
public class OrchestrationConfiguration {
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${rgm.orchestration.enabled:false} && ${spring.task.scheduling.enabled:true}")
    static class DispatchScheduling { }
}
