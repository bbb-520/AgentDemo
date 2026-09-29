package com.bbb.exercise.agentdemo.gateway;

import com.bbb.exercise.agentdemo.gateway.config.GatewayRoutes;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteTest {
    @Test
    void declaresRoutesForCoreDomains() {
        assertThat(GatewayRoutes.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("agentRoutes"));
    }
}
