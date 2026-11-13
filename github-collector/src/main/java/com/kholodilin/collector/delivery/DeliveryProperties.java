package com.kholodilin.collector.delivery;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("collector.delivery")
public record DeliveryProperties(boolean enabled,Duration lease,Duration retryDelay,Duration pollInterval,int maximumAttempts,boolean allowHttp,List<Destination> destinations) {
    public DeliveryProperties {
        lease=lease==null?Duration.ofSeconds(60):lease;retryDelay=retryDelay==null?Duration.ofSeconds(5):retryDelay;pollInterval=pollInterval==null?Duration.ofMillis(500):pollInterval;maximumAttempts=maximumAttempts==0?12:maximumAttempts;
        destinations=destinations==null?List.of():List.copyOf(destinations);
        if(lease.compareTo(Duration.ofSeconds(30))<0 || lease.compareTo(Duration.ofMinutes(10))>0 || retryDelay.toMillis()<1 || pollInterval.toMillis()<1 || maximumAttempts<1 || maximumAttempts>100)throw new IllegalArgumentException("Invalid result delivery policy");
        Set<String> names=new HashSet<>();for(var destination:destinations){var uri=destination.endpoint();if(destination.ref()==null || destination.origin()==null || !names.add(destination.origin()+":"+destination.ref()) || uri==null || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !("https".equals(uri.getScheme()) || (allowHttp && "http".equals(uri.getScheme()))))throw new IllegalArgumentException("Trusted unique result destinations are required");}
        if(enabled && destinations.isEmpty())throw new IllegalArgumentException("Result delivery destination is required");
    }
    public record Destination(String ref,String origin,URI endpoint) { }
}
