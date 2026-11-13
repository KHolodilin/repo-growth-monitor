package com.kholodilin.repogrowth.orchestration;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrchestrationPolicyTest {
    OrchestrationProperties config(boolean http,List<OrchestrationProperties.Node> nodes,List<OrchestrationProperties.Route> routes) {
        return new OrchestrationProperties(true,null,null,null,null,null,null,null,http,nodes,routes);
    }
    @Test void explicitRepositoryRoutePrecedesOwnerThenDefaultPool() {
        var p=config(false,List.of(),List.of(new OrchestrationProperties.Route("KHolodilin",null,"owner-pool"),new OrchestrationProperties.Route(null,"202","repo-pool")));
        assertThat(p.poolFor("kholodilin","202")).isEqualTo("repo-pool");
        assertThat(p.poolFor("kholodilin","203")).isEqualTo("owner-pool");
        assertThat(p.poolFor("other","203")).isEqualTo("default");
    }
    @Test void onlyConfiguredEnabledEndpointCanReceiveAnAssignment() {
        var a=new OrchestrationProperties.Node("a","default",URI.create("https://collector.example/"),true,"secret");
        var b=new OrchestrationProperties.Node("b","default",URI.create("https://collector-b.example"),false,"secret-b");
        var p=config(false,List.of(a,b),List.of());
        assertThat(p.trusted("a","https://collector.example")).isEqualTo(a);
        assertThatThrownBy(()->p.trusted("a","https://attacker.example")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->p.trusted("b","https://collector-b.example")).isInstanceOf(IllegalStateException.class);
        assertThat(p.toString()).doesNotContain("secret-b","serviceToken=secret");
    }
    @Test void badEndpointsDuplicateNodesAndUnsafeTimeoutsFailEarly() {
        for(String url:List.of("http://collector.example","https://user:password@collector.example","https://collector.example?token=x","https://collector.example#fragment","file:///tmp/x")) {
            var node=new OrchestrationProperties.Node("a","default",URI.create(url),true,"secret");
            assertThatThrownBy(()->config(false,List.of(node),List.of())).isInstanceOf(IllegalArgumentException.class);
        }
        var a=new OrchestrationProperties.Node("a","default",URI.create("http://localhost:9090"),true,"secret");
        assertThat(config(true,List.of(a),List.of()).nodes()).hasSize(1);
        assertThatThrownBy(()->config(true,List.of(a,a),List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OrchestrationProperties(true,null,null,null,Duration.ofSeconds(1),null,null,null,false,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
