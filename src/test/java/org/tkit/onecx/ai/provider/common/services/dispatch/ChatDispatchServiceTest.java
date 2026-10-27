package org.tkit.onecx.ai.provider.common.services.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tkit.onecx.ai.provider.common.services.agent.AgentService;
import org.tkit.onecx.ai.provider.domain.daos.AgentDAO;
import org.tkit.onecx.ai.provider.domain.daos.AgentMcpToolRuleDAO;
import org.tkit.onecx.ai.provider.domain.daos.ExternalAgentDAO;
import org.tkit.onecx.ai.provider.domain.models.Agent;
import org.tkit.onecx.ai.provider.domain.models.AgentGroup;
import org.tkit.onecx.ai.provider.domain.models.AgentMcpToolRule;
import org.tkit.onecx.ai.provider.domain.models.Model;
import org.tkit.onecx.ai.provider.domain.models.Provider;
import org.tkit.onecx.ai.provider.domain.models.Tool;
import org.tkit.onecx.ai.provider.domain.models.enums.ProviderType;
import org.tkit.onecx.ai.provider.domain.models.enums.ToolPermission;
import org.tkit.onecx.ai.provider.test.AbstractTest;

import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.ChatRequestDTOV1;
import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.ProblemDetailResponseDTOV1;
import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.RequestContextDTOV1;
import gen.org.tkit.onecx.ai.provider.runtime.client.api.RuntimeInternalApi;
import gen.org.tkit.onecx.ai.provider.runtime.client.model.RuntimeChatRequest;
import gen.org.tkit.onecx.ai.provider.runtime.client.model.RuntimeChatResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChatDispatchServiceTest extends AbstractTest {

    @Inject
    ChatDispatchService chatDispatchService;

    @InjectMock
    AgentService agentService;

    @InjectMock
    @RestClient
    RuntimeInternalApi providerRuntimeClient;

    @InjectMock
    AgentDAO agentDAO;

    @InjectMock
    ExternalAgentDAO externalAgentDAO;

    @InjectMock
    AgentMcpToolRuleDAO agentMcpToolRuleDAO;

    @Test
    void chat_noAgentFound_returnsNotFound() {
        when(agentService.findAgentByRequestContext(any())).thenReturn(null);

        var request = new ChatRequestDTOV1();
        request.setRequestContext(null);

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
            assertProblem(response, ChatDispatchService.AGENT_NOT_FOUND);
        }
    }

    @Test
    void chat_agentFound_routesToRuntime() {
        var provider = new Provider();
        provider.setType(ProviderType.OLLAMA);
        provider.setLlmUrl("http://ollama.local");
        var model = new Model();
        model.setProvider(provider);
        model.setModelIdentifier("mistral");
        var agent = new Agent();
        agent.setModel(model);

        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        var request = new ChatRequestDTOV1();
        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("reply");
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.ok(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }

        var requestCaptor = ArgumentCaptor.forClass(RuntimeChatRequest.class);
        verify(providerRuntimeClient).chat(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getRootAgent().getModel().getModelIdentifier()).isEqualTo("mistral");
        assertThat(requestCaptor.getValue().getRootAgent().getModel().getProvider().getType()).isEqualTo("OLLAMA");
    }

    @Test
    void chat_nullChatMessage_conversationIdIsNull() {
        // Covers: conversationId() → request.getChatMessage() != null = false (null chatMessage)
        var agent = new Agent();
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        var request = new ChatRequestDTOV1(); // chatMessage is null - bypasses HTTP @NotNull validation
        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("ok");
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.ok(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    void chat_agentRuntimeTimeout_returnsRetryableGatewayTimeout() {
        var agent = new Agent();
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        var request = new ChatRequestDTOV1();
        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("raw runtime timeout details");
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.status(Response.Status.GATEWAY_TIMEOUT).entity(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.GATEWAY_TIMEOUT.getStatusCode());
            assertProblem(response, ChatDispatchService.RUNTIME_TIMEOUT);
        }
    }

    @Test
    void chat_agentRuntimeFailed_returnsBadGatewayWithoutRuntimeDetails() {
        var agent = new Agent();
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        var request = new ChatRequestDTOV1();
        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("internal stack details");
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.status(Response.Status.BAD_REQUEST).entity(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_GATEWAY.getStatusCode());
            assertProblem(response, ChatDispatchService.RUNTIME_ERROR);
            assertThat(((ProblemDetailResponseDTOV1) response.getEntity()).getDetail())
                    .doesNotContain("internal stack details");
        }
    }

    @Test
    void chat_agentRuntimeFailed_nonJsonBody_returnsBadGateway() {
        // The runtime error body is not parsed, so a non-JSON body cannot break the mapping.
        var agent = new Agent();
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        var mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        when(providerRuntimeClient.chat(any())).thenReturn(mockResponse);

        try (var response = chatDispatchService.chat(new ChatRequestDTOV1())) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_GATEWAY.getStatusCode());
            assertProblem(response, ChatDispatchService.RUNTIME_ERROR);
        }
    }

    @Test
    void chat_runtimeReturnsEmptyBody_returnsBadGatewayInvalidResponse() {
        var agent = new Agent();
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        var mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(Response.Status.OK.getStatusCode());
        when(mockResponse.readEntity(RuntimeChatResponse.class)).thenReturn(null);
        when(providerRuntimeClient.chat(any())).thenReturn(mockResponse);

        try (var response = chatDispatchService.chat(new ChatRequestDTOV1())) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_GATEWAY.getStatusCode());
            assertProblem(response, ChatDispatchService.RUNTIME_INVALID_RESPONSE);
        }
    }

    @Test
    void chat_runtimeNotReachable_returnsRetryableServiceUnavailable() {
        var agent = new Agent();
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        when(providerRuntimeClient.chat(any())).thenThrow(new RuntimeException("connect refused to 10.0.0.5"));

        try (var response = chatDispatchService.chat(new ChatRequestDTOV1())) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
            assertProblem(response, ChatDispatchService.RUNTIME_UNAVAILABLE);
            assertThat(((ProblemDetailResponseDTOV1) response.getEntity()).getDetail())
                    .doesNotContain("10.0.0.5");
        }
    }

    private static void assertProblem(Response response, String errorCode) {
        assertThat(response.getEntity()).isInstanceOf(ProblemDetailResponseDTOV1.class);
        assertThat(((ProblemDetailResponseDTOV1) response.getEntity()).getErrorCode()).isEqualTo(errorCode);
    }

    @Test
    void chat_agentWithEmptyGroups_returnsOk() {
        // Covers: agent.getGroups() != null but agent.getGroups().isEmpty() → mapGroups returns List.of()
        var agent = new Agent();
        agent.setGroups(new HashSet<>()); // empty non-null Set
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        var request = new ChatRequestDTOV1();
        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("ok");
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.ok(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    void chat_agentGroup_nullGroupId_coversNullBranch() {
        // Covers: group.getId() == null → groupId = null (false branch of ternary)
        var group = new AgentGroup(); // getId() returns null (not persisted)
        var agent = new Agent();
        agent.setGroups(Set.of(group));
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        when(agentDAO.findAgentsByGroupId(isNull())).thenReturn(List.of());
        when(externalAgentDAO.findExternalAgentsByGroupId(isNull())).thenReturn(List.of());

        var request = new ChatRequestDTOV1();
        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("ok");
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.ok(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    void chat_agentGroup_agentWithNullId_isFiltered() {
        // Covers: .filter(agent -> agent.getId() != null) false branch – filters out agents with null ID
        var group = new AgentGroup(); // getId() returns null
        var agent = new Agent();
        agent.setGroups(Set.of(group));
        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);

        var agentInGroup = new Agent(); // getId() == null → should be filtered out
        when(agentDAO.findAgentsByGroupId(isNull())).thenReturn(List.of(agentInGroup));
        when(externalAgentDAO.findExternalAgentsByGroupId(isNull())).thenReturn(List.of());

        var request = new ChatRequestDTOV1();
        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("ok");
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.ok(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    void chat_agentIdInRequestContext_usesAgentDaoLookup() {
        var provider = new Provider();
        provider.setType(ProviderType.OLLAMA);
        var model = new Model();
        model.setProvider(provider);
        model.setModelIdentifier("mistral");
        var agent = new Agent();
        agent.setModel(model);

        var requestContext = new RequestContextDTOV1();
        requestContext.setAgentId("agent-1");

        var request = new ChatRequestDTOV1();
        request.setRequestContext(requestContext);

        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("reply");

        when(agentDAO.findById("agent-1")).thenReturn(agent);
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.ok(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }

        verify(agentDAO).findById("agent-1");
        verify(agentService, never()).findAgentByRequestContext(any());
    }

    @Test
    void chat_agentWithTools_mapsToolRulesFromDao() {
        // Covers: RuntimeSnapshotMapper.agentRulesByToolId non-empty branch + mapRules non-empty branch
        var provider = new Provider();
        provider.setType(ProviderType.OLLAMA);
        provider.setLlmUrl("http://ollama.local");
        var model = new Model();
        model.setProvider(provider);
        model.setModelIdentifier("mistral");

        var tool = new Tool();
        tool.setId("tool-1");
        tool.setName("searchTool");
        tool.setType(org.tkit.onecx.ai.provider.domain.models.enums.ToolType.MCP);
        tool.setUrl("http://mcp.local");

        var agent = new Agent();
        agent.setId("agent-1");
        agent.setModel(model);
        agent.setTools(Set.of(tool));

        var rule = new AgentMcpToolRule();
        rule.setTool(tool);
        rule.setToolName("searchTool");
        rule.setAllowed(ToolPermission.ALLOW);

        when(agentService.findAgentByRequestContext(any())).thenReturn(agent);
        when(agentMcpToolRuleDAO.findByAgentAndToolIds("agent-1", java.util.List.of("tool-1")))
                .thenReturn(java.util.List.of(rule));

        var request = new ChatRequestDTOV1();
        var runtimeResponse = new RuntimeChatResponse();
        runtimeResponse.setMessage("ok");
        when(providerRuntimeClient.chat(any()))
                .thenReturn(Response.ok(runtimeResponse).build());

        try (var response = chatDispatchService.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }

        var requestCaptor = ArgumentCaptor.forClass(RuntimeChatRequest.class);
        verify(providerRuntimeClient).chat(requestCaptor.capture());
        var snapshot = requestCaptor.getValue().getRootAgent();
        assertThat(snapshot.getTools()).hasSize(1);
        assertThat(snapshot.getTools().get(0).getToolRules()).hasSize(1);
        assertThat(snapshot.getTools().get(0).getToolRules().get(0).getToolName()).isEqualTo("searchTool");
    }
}
