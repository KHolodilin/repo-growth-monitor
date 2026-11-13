package com.kholodilin.collector.credentials;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("collector.credentials")
public record CollectorCredentialProperties(boolean enabled,Duration ttl,long maximumSize,int maximumRejected,
        Duration failureBackoff,Duration connectTimeout,Duration readTimeout,boolean allowHttp,List<Origin> origins) {
    public CollectorCredentialProperties {
        ttl=ttl==null?Duration.ofHours(2):ttl;failureBackoff=failureBackoff==null?Duration.ofSeconds(5):failureBackoff;
        connectTimeout=connectTimeout==null?Duration.ofSeconds(3):connectTimeout;readTimeout=readTimeout==null?Duration.ofSeconds(10):readTimeout;
        maximumSize=maximumSize==0?100:maximumSize;maximumRejected=maximumRejected==0?1000:maximumRejected;
        if(maximumSize<1 || maximumRejected<1 || maximumSize>100000 || maximumRejected>100000) throw new IllegalArgumentException("Invalid credential cache bounds");
        for(Duration duration:List.of(ttl,failureBackoff,connectTimeout,readTimeout)) if(duration.toMillis()<1 || duration.compareTo(Duration.ofHours(24))>0) throw new IllegalArgumentException("Credential durations must be 1ms..24h");
        origins=origins==null?List.of():List.copyOf(origins);Set<String> ids=new HashSet<>();
        for(var origin:origins) {
            URI uri=origin.endpoint();
            if(origin.origin()==null || !ids.add(origin.origin()) || uri==null || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || (allowHttp && "http".equals(uri.getScheme()))) || origin.serviceToken()==null || origin.serviceToken().length()<32)
                throw new IllegalArgumentException("Trusted credential origins require HTTPS and separate service credentials");
        }
        if(enabled && origins.isEmpty()) throw new IllegalArgumentException("Credential resolution origin is required");
    }
    public record Origin(String origin,URI endpoint,String serviceToken) {@Override public String toString(){return "Origin[origin="+origin+", serviceToken=REDACTED]";}}
}
