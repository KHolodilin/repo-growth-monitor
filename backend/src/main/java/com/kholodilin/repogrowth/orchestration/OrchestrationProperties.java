package com.kholodilin.repogrowth.orchestration;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rgm.orchestration")
public record OrchestrationProperties(boolean enabled, String defaultPool, String destinationRef,
        Duration jobTimeout, Duration dispatchLease, Duration retryDelay, Duration connectTimeout,
        Duration readTimeout, boolean allowHttp, List<Node> nodes, List<Route> routes) {
    public OrchestrationProperties {
        defaultPool=defaultPool==null?"default":defaultPool;
        destinationRef=destinationRef==null?"rgm-results":destinationRef;
        jobTimeout=positive(jobTimeout,Duration.ofHours(2));
        dispatchLease=positive(dispatchLease,Duration.ofSeconds(30));
        retryDelay=positive(retryDelay,Duration.ofSeconds(5));
        connectTimeout=positive(connectTimeout,Duration.ofSeconds(3));
        readTimeout=positive(readTimeout,Duration.ofSeconds(10));
        if(dispatchLease.compareTo(connectTimeout.plus(readTimeout).plusSeconds(2))<=0) throw new IllegalArgumentException("Dispatch lease must exceed HTTP timeouts");
        nodes=nodes==null?List.of():List.copyOf(nodes); routes=routes==null?List.of():List.copyOf(routes);
        Set<String> keys=new HashSet<>(),resolveTokens=new HashSet<>();
        for(Node node:nodes) {
            if(!keys.add(node.key())) throw new IllegalArgumentException("Duplicate collector node");
            if(node.resolveToken()!=null && (node.resolveToken().length()<32 || !resolveTokens.add(node.resolveToken()))) throw new IllegalArgumentException("Node resolve tokens must be unique and at least 32 characters");
            URI uri=node.endpoint();
            if(uri==null || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || (allowHttp && "http".equals(uri.getScheme())))) throw new IllegalArgumentException("Collector requires a trusted HTTPS endpoint");
        }
    }
    private static Duration positive(Duration value,Duration fallback) {
        value=value==null?fallback:value; if(value.isNegative() || value.isZero()) throw new IllegalArgumentException("Duration must be positive"); return value;
    }
    public record Node(String key,String pool,URI endpoint,boolean enabled,String serviceToken,String resolveToken) {
        public Node(String key,String pool,URI endpoint,boolean enabled,String serviceToken) {this(key,pool,endpoint,enabled,serviceToken,null);}
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Node {
            if(key==null || !key.matches("[a-zA-Z0-9_-]{1,64}") || pool==null || pool.isBlank() || pool.length()>64) throw new IllegalArgumentException("Invalid collector node/pool");
        }
        @Override public String toString() { return "Node[key="+key+", pool="+pool+", serviceToken=REDACTED, resolveToken=REDACTED]"; }
    }
    public record Route(String owner,String repositoryGithubId,String pool) { }
    public String poolFor(String owner,String githubId) {
        for(Route route:routes) if(githubId.equals(route.repositoryGithubId())) return route.pool();
        for(Route route:routes) if(route.repositoryGithubId()==null && owner.equalsIgnoreCase(route.owner())) return route.pool();
        return defaultPool;
    }
    public Node trusted(String key,String endpoint) {
        return nodes.stream().filter(n->n.enabled() && n.key().equals(key) && endpoint(n).equals(endpoint)).findFirst()
            .orElseThrow(()->new IllegalStateException("Assignment endpoint is no longer trusted"));
    }
    public static String endpoint(Node node) { return node.endpoint().toString().replaceAll("/+$",""); }
}
