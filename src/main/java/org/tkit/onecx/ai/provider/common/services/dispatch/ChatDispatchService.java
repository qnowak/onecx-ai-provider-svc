package org.tkit.onecx.ai.provider.common.services.dispatch;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.tkit.onecx.ai.provider.common.services.agent.AgentService;
import org.tkit.onecx.ai.provider.domain.daos.AgentDAO;
import org.tkit.onecx.ai.provider.domain.daos.ExternalAgentDAO;
import org.tkit.onecx.ai.provider.domain.models.Agent;
import org.tkit.onecx.ai.provider.domain.models.AgentGroup;
import org.tkit.onecx.ai.provider.rs.external.v1.mappers.RuntimeSnapshotMapper;

import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.ChatRequestDTOV1;
import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.ProblemDetailResponseDTOV1;
import gen.org.tkit.onecx.ai.provider.runtime.client.api.RuntimeInternalApi;
import gen.org.tkit.onecx.ai.provider.runtime.client.model.AgentGroupSnapshot;
import gen.org.tkit.onecx.ai.provider.runtime.client.model.RuntimeChatResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class ChatDispatchService {

    static final String AGENT_NOT_FOUND = "AGENT_NOT_FOUND";
    static final String RUNTIME_UNAVAILABLE = "RUNTIME_UNAVAILABLE";
    static final String RUNTIME_TIMEOUT = "RUNTIME_TIMEOUT";
    static final String RUNTIME_ERROR = "RUNTIME_ERROR";
    static final String RUNTIME_INVALID_RESPONSE = "RUNTIME_INVALID_RESPONSE";

    @Inject
    AgentService agentService;

    @Inject
    AgentDAO agentDAO;

    @Inject
    ExternalAgentDAO externalAgentDAO;

    @Inject
    RuntimeSnapshotMapper runtimeSnapshotMapper;

    @Inject
    @RestClient
    RuntimeInternalApi providerRuntimeClient;

    public Response chat(ChatRequestDTOV1 chatRequestDTO) {
        log.info("Received chat request: {}", chatRequestDTO);
        Agent agent;
        if (chatRequestDTO.getRequestContext() != null && chatRequestDTO.getRequestContext().getAgentId() != null) {
            agent = agentDAO.findById(chatRequestDTO.getRequestContext().getAgentId());
        } else {
            agent = agentService.findAgentByRequestContext(chatRequestDTO.getRequestContext());
        }
        if (agent == null) {
            log.error("No agent found for request context: {}", chatRequestDTO.getRequestContext());
            return error(Response.Status.NOT_FOUND, AGENT_NOT_FOUND,
                    "No agent found for the given request context");
        }
        int runtimeStatus;
        RuntimeChatResponse result = null;
        try (Response response = providerRuntimeClient.chat(
                runtimeSnapshotMapper.toRuntimeRequest(agent, chatRequestDTO, mapGroups(agent)))) {
            runtimeStatus = response.getStatus();
            if (runtimeStatus == Response.Status.OK.getStatusCode()) {
                result = response.readEntity(RuntimeChatResponse.class);
            }
        } catch (WebApplicationException e) {
            int status = e.getResponse() != null ? e.getResponse().getStatus() : 0;
            log.error("Agent runtime chat request failed with status {}", status, e);
            return runtimeFailure(status);
        } catch (Exception e) {
            log.error("Error invoking runtime chat API: {}", e.getMessage(), e);
            return error(Response.Status.SERVICE_UNAVAILABLE, RUNTIME_UNAVAILABLE,
                    "The agent runtime is not reachable, try again later");
        }
        if (runtimeStatus == Response.Status.OK.getStatusCode()) {
            if (result == null) {
                log.error("Agent runtime returned an empty chat response");
                return error(Response.Status.BAD_GATEWAY, RUNTIME_INVALID_RESPONSE,
                        "The agent runtime returned an invalid response");
            }
            return Response.ok(runtimeSnapshotMapper.mapRuntimeChatMessage(result.getMessage(),
                    conversationId(chatRequestDTO))).build();
        }
        log.error("Agent runtime chat request failed with status {}", runtimeStatus);
        return runtimeFailure(runtimeStatus);
    }

    private Response runtimeFailure(int runtimeStatus) {
        if (runtimeStatus == Response.Status.GATEWAY_TIMEOUT.getStatusCode()) {
            return error(Response.Status.GATEWAY_TIMEOUT, RUNTIME_TIMEOUT,
                    "The agent runtime did not respond in time, try again later");
        }
        return error(Response.Status.BAD_GATEWAY, RUNTIME_ERROR,
                "The agent runtime failed to process the request");
    }

    private Response error(Response.Status status, String errorCode, String detail) {
        var dto = new ProblemDetailResponseDTOV1();
        dto.setErrorCode(errorCode);
        dto.setDetail(detail);
        return Response.status(status).entity(dto).build();
    }

    private String conversationId(ChatRequestDTOV1 request) {
        return request.getChatMessage() != null && request.getChatMessage().getConversationId() != null
                ? request.getChatMessage().getConversationId()
                : null;
    }

    private List<AgentGroupSnapshot> mapGroups(Agent agent) {
        if (agent.getGroups() == null || agent.getGroups().isEmpty()) {
            return List.of();
        }
        return agent.getGroups().stream().map(this::mapGroup).toList();
    }

    private AgentGroupSnapshot mapGroup(AgentGroup group) {
        var agents = agentDAO.findAgentsByGroupId(group.getId()).stream()
                .map(runtimeSnapshotMapper::mapAgent)
                .toList();
        var externalAgents = externalAgentDAO.findExternalAgentsByGroupId(group.getId()).stream()
                .map(runtimeSnapshotMapper::mapExternalAgent)
                .toList();
        return runtimeSnapshotMapper.mapGroup(group, agents, externalAgents);
    }
}
