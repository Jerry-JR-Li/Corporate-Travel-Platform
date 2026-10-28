package com.gogo.travel.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptionalMcpRegistrationTest {

    @Test
    void absentClientDoesNotPreventAgentCreation() {
        Toolkit toolkit = new Toolkit();

        assertDoesNotThrow(() -> BaseSubAgent.registerOptionalMcpClient(toolkit, null, List.of()));
        assertTrue(toolkit.getToolNames().isEmpty());
    }

    @Test
    void failedHandshakeDoesNotPreventAgentCreation() {
        Toolkit toolkit = new Toolkit();
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.initialize()).thenReturn(Mono.error(new IllegalStateException("Service unavailable")));

        assertDoesNotThrow(() -> BaseSubAgent.registerOptionalMcpClient(toolkit, client, List.of()));
        assertTrue(toolkit.getToolNames().isEmpty());
        verify(client, never()).listTools();
    }

    @Test
    void failedToolDiscoveryDoesNotPreventAgentCreation() {
        Toolkit toolkit = new Toolkit();
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.initialize()).thenReturn(Mono.empty());
        when(client.listTools()).thenReturn(Mono.error(new IllegalStateException("Discovery unavailable")));

        assertDoesNotThrow(() -> BaseSubAgent.registerOptionalMcpClient(toolkit, client, List.of()));
        assertTrue(toolkit.getToolNames().isEmpty());
        verify(client).listTools();
    }

    @Test
    void availableClientIsInitializedAndDiscovered() {
        Toolkit toolkit = new Toolkit();
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("available-mcp");
        when(client.initialize()).thenReturn(Mono.empty());
        when(client.listTools()).thenReturn(Mono.just(List.of()));

        assertDoesNotThrow(() -> BaseSubAgent.registerOptionalMcpClient(toolkit, client, List.of()));
        verify(client).initialize();
        verify(client).listTools();
    }
}
