package com.kholodilin.repogrowth.orchestration;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("rgm.orchestration.recovery")
public record RecoveryProperties(boolean enabled,Duration nodeInterval,Duration minimumAge,Duration stalledAfter,int failureThreshold,int batchSize,int maximumAttempts){
    public RecoveryProperties {
        nodeInterval=nodeInterval==null?Duration.ofSeconds(30):nodeInterval;minimumAge=minimumAge==null?Duration.ofMinutes(2):minimumAge;stalledAfter=stalledAfter==null?Duration.ofMinutes(10):stalledAfter;
        failureThreshold=failureThreshold==0?3:failureThreshold;batchSize=batchSize==0?20:batchSize;maximumAttempts=maximumAttempts==0?3:maximumAttempts;
        if(nodeInterval.toMillis()<100 || minimumAge.isNegative() || stalledAfter.compareTo(nodeInterval)<0 || failureThreshold<2 || failureThreshold>20 || batchSize<1 || batchSize>1000 || maximumAttempts<1 || maximumAttempts>10)throw new IllegalArgumentException("Invalid node recovery policy");
    }
}
