package com.gogo.travel.agent.memory;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.config.Mem0Properties;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class Mem0ContractTest {
    @Test void nativeClientUsesAuthenticatedScopedContractAndPropagatesWriteFailures() throws Exception {
        var requests = new CopyOnWriteArrayList<Map<String,Object>>();
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            var body = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            body.put("path",exchange.getRequestURI().getPath());
            body.put("auth",exchange.getRequestHeaders().getFirst("X-API-Key")); requests.add(body);
            byte[] response = (exchange.getRequestURI().getPath().equals("/search")
                ? "{\"results\":[{\"id\":\"1\",\"memory\":\"prefers window seats\",\"score\":0.9}]}" : "{\"results\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),response.length);
            exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            var factory = new TravelPreferenceLongTermMemoryFactory(new Mem0Properties(true,
                "http://127.0.0.1:"+server.getAddress().getPort(),"local-test-key","self-hosted",Duration.ofSeconds(2),"travel-test"));
            var alice = (TravelPreferenceLongTermMemory) factory.create("alice");
            var bob = (TravelPreferenceLongTermMemory) factory.create("bob");
            Msg query = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("seat preference").build()).build();
            alice.record(List.of(query)).block();
            assertThat(alice.retrieveStrict(query).block()).contains("window seats");
            bob.retrieveStrict(query).block();
            assertThat(requests).allSatisfy(r -> assertThat(r.get("auth")).isEqualTo("local-test-key"));
            assertThat(requests.get(0)).containsEntry("path","/memories").containsEntry("user_id","alice").containsEntry("agent_id","travel-test");
            assertThat((Map<?,?>)requests.get(1).get("filters")).isEqualTo(Map.of("user_id","alice","agent_id","travel-test"));
            assertThat((Map<?,?>)requests.get(2).get("filters")).isEqualTo(Map.of("user_id","bob","agent_id","travel-test"));
            status.set(503);
            assertThatThrownBy(() -> alice.record(List.of(query)).block()).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> alice.retrieveStrict(query).block()).isInstanceOf(RuntimeException.class);
            assertThat(alice.retrieve(query).block()).isEmpty();
        } finally { server.stop(0); }
    }
}
