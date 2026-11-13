package com.kholodilin.collector.execution;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("collector.execution")
public record ExecutionProperties(boolean enabled,URI apiBaseUrl,boolean allowHttp,Duration lease,Duration pollInterval,
        Duration requestTimeout,int maximumFailures,int maximumPages,int maximumPayloadBytes) {
    public ExecutionProperties {
        apiBaseUrl=apiBaseUrl==null?URI.create("https://api.github.com"):apiBaseUrl;
        lease=lease==null?Duration.ofMinutes(1):lease;pollInterval=pollInterval==null?Duration.ofMillis(250):pollInterval;
        requestTimeout=requestTimeout==null?Duration.ofSeconds(10):requestTimeout;
        maximumFailures=maximumFailures==0?5:maximumFailures;maximumPages=maximumPages==0?5:maximumPages;maximumPayloadBytes=maximumPayloadBytes==0?5*1024*1024:maximumPayloadBytes;
        if(apiBaseUrl.getHost()==null || apiBaseUrl.getUserInfo()!=null || apiBaseUrl.getQuery()!=null || apiBaseUrl.getFragment()!=null || !("https".equals(apiBaseUrl.getScheme()) || (allowHttp && "http".equals(apiBaseUrl.getScheme()))))throw new IllegalArgumentException("Trusted GitHub API base is required");
        if(requestTimeout.toMillis()<1 || requestTimeout.compareTo(Duration.ofSeconds(15))>0 || lease.compareTo(requestTimeout.multipliedBy(3))<0 || lease.compareTo(Duration.ofHours(1))>0 || pollInterval.toMillis()<1 || maximumFailures<1 || maximumFailures>20 || maximumPages<1 || maximumPages>100 || maximumPayloadBytes<1024 || maximumPayloadBytes>5*1024*1024)throw new IllegalArgumentException("Invalid Collector execution policy");
    }
}
