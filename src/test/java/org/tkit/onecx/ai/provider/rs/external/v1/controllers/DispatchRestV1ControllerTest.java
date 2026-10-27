package org.tkit.onecx.ai.provider.rs.external.v1.controllers;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.JsonBody;
import org.mockserver.model.MediaType;
import org.tkit.onecx.ai.provider.test.AbstractTest;
import org.tkit.quarkus.security.test.GenerateKeycloakClient;
import org.tkit.quarkus.test.WithDBData;

import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.AgentFilterDTOV1;
import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.ChatMessageDTOV1;
import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.ChatRequestDTOV1;
import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.ProblemDetailResponseDTOV1;
import gen.org.tkit.onecx.ai.provider.rs.external.v1.model.RequestContextDTOV1;
import gen.org.tkit.onecx.ai.provider.runtime.client.model.RuntimeChatResponse;
import io.quarkiverse.mockserver.test.InjectMockServerClient;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestHTTPEndpoint(DispatchRestV1Controller.class)
@WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)
@GenerateKeycloakClient(clientName = "testClient", scopes = { "ocx-ai:all", "ocx-ai:read", "ocx-ai:write", "ocx-ai:delete" })
class DispatchRestV1ControllerTest extends AbstractTest {

    @InjectMockServerClient
    MockServerClient mockServerClient;

    static final String MOCK_ID = "MOCK";

    @BeforeEach
    void resetExpectation() {
        try {
            mockServerClient.clear(MOCK_ID);
        } catch (Exception _) {
            //  mockId not existing
        }
    }

    @Test
    @DisplayName("Test successful chat request with valid conversation ID")
    void chatTest() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);

        var data = new RuntimeChatResponse().message("ok");

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(data)));

        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatMessageDTOV1.class);

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("ok");
        assertThat(response.getType()).isEqualTo(ChatMessageDTOV1.TypeEnum.ASSISTANT);
        assertThat(response.getConversationId()).isEqualTo("conversation-1");
        assertThat(response.getCreationDate()).isNotNull();
    }

    @Test
    @DisplayName("Test chat request with null conversation ID - covers conversationId() method")
    void chatTestWithNullConversationId() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId(null); // null conversation ID
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);

        var data = new RuntimeChatResponse().message("response message");

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(data)));

        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatMessageDTOV1.class);

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("response message");
        assertThat(response.getConversationId()).isNull(); // should be null - covers branch in conversationId()
    }

    @Test
    @DisplayName("Test chat targeting agent with no groups - covers agent.getGroups().isEmpty() branch")
    void chatTestAgentWithEmptyGroups() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);

        // Route to agent-33-333 which has no group associations → getGroups().isEmpty() == true
        var filter = new AgentFilterDTOV1();
        filter.setKey(AgentFilterDTOV1.KeyEnum.APP_ID);
        filter.setValue("no-groups-app");
        var requestContext = new RequestContextDTOV1();
        requestContext.setFilter(filter);
        request.setRequestContext(requestContext);

        var data = new RuntimeChatResponse().message("ok from no-groups agent");

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(data)));

        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatMessageDTOV1.class);

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("ok from no-groups agent");
        assertThat(response.getConversationId()).isEqualTo("conversation-1");
    }

    @Test
    @DisplayName("Test runtime API responds with error that becomes RUNTIME_ERROR (502)")
    void chatTestRuntimeErrorResponseThrowsException() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);

        // Mock the runtime API to return BAD_REQUEST, which will cause REST client to throw
        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.BAD_REQUEST.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(new RuntimeChatResponse().message("Error from runtime"))));

        // Runtime non-2xx is mapped to a structured, non-retryable 502 without the runtime body
        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(Response.Status.BAD_GATEWAY.getStatusCode())
                .extract()
                .as(ProblemDetailResponseDTOV1.class);

        assertThat(response.getErrorCode()).isEqualTo("RUNTIME_ERROR");
        assertThat(response.getDetail()).doesNotContain("Error from runtime");
    }

    @Test
    @DisplayName("Test runtime API responds with null message in successful response")
    void chatTestRuntimeSuccessWithNullMessage() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);

        // Return OK status but with null message in response
        var successResponse = new RuntimeChatResponse(); // message is null

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(successResponse)));

        // When OK but result.getMessage() is null, should map successfully
        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .as(ChatMessageDTOV1.class);

        assertThat(response).isNotNull();
        assertThat(response.getConversationId()).isEqualTo("conversation-1");
    }

    @Test
    @DisplayName("Test runtime API responds with GATEWAY_TIMEOUT that becomes RUNTIME_TIMEOUT (504)")
    void chatTestRuntimeGatewayTimeoutException() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);

        // Mock the runtime API to return GATEWAY_TIMEOUT, which will cause REST client to throw
        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.GATEWAY_TIMEOUT.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json("")));

        // Runtime timeout is mapped to a retryable 504 with a structured body
        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(Response.Status.GATEWAY_TIMEOUT.getStatusCode())
                .extract()
                .as(ProblemDetailResponseDTOV1.class);

        assertThat(response.getErrorCode()).isEqualTo("RUNTIME_TIMEOUT");
    }

    @Test
    @DisplayName("Test runtime API throws exception")
    void chatTestRuntimeException() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .error(org.mockserver.model.HttpError.error().withDropConnection(true));

        // Runtime not reachable is mapped to a retryable 503 without exposing the connection error
        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(Response.Status.SERVICE_UNAVAILABLE.getStatusCode())
                .extract()
                .as(ProblemDetailResponseDTOV1.class);

        assertThat(response.getErrorCode()).isEqualTo("RUNTIME_UNAVAILABLE");
    }

    @Test
    void chatWithAgentFilterNotFoundTest() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);
        request.setRequestContext(
                new RequestContextDTOV1().filter(new AgentFilterDTOV1().key(AgentFilterDTOV1.KeyEnum.APP_ID).value("app-1")));

        var data = new RuntimeChatResponse().message("ok");

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(data)));

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void chatWithAgentFilterOnlyKeyNotFoundTest() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);
        request.setRequestContext(
                new RequestContextDTOV1().filter(new AgentFilterDTOV1().key(AgentFilterDTOV1.KeyEnum.APP_ID)));

        var data = new RuntimeChatResponse().message("ok");

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(data)));

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void chatWithAgentFilterOnlyValueNotFoundTest() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);
        request.setRequestContext(
                new RequestContextDTOV1().filter(new AgentFilterDTOV1().value("app-1")));

        var data = new RuntimeChatResponse().message("ok");

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(data)));

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void chatWithoutAgentFilterOnlyValueNotFoundTest() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);
        request.setRequestContext(new RequestContextDTOV1());

        var data = new RuntimeChatResponse().message("ok");

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(data)));

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void chatWithAgentFilterMultipleMachesNotFoundTest() {
        var request = new ChatRequestDTOV1();
        var message = new ChatMessageDTOV1();
        message.setConversationId("conversation-1");
        message.setType(ChatMessageDTOV1.TypeEnum.USER);
        message.setMessage("hello");
        request.setChatMessage(message);
        request.setRequestContext(new RequestContextDTOV1()
                .filter(new AgentFilterDTOV1().key(AgentFilterDTOV1.KeyEnum.APP_ID).value("onecx-test-test")));

        var data = new RuntimeChatResponse().message("ok");

        mockServerClient.when(request().withPath("/ai/internal/runtime/chat").withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(Response.Status.OK.getStatusCode())
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody(JsonBody.json(data)));

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post()
                .then()
                .statusCode(OK.getStatusCode());
    }
}
