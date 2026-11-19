package vn.cusc.meetingservice.client.conclusion;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.cusc.meetingservice.config.AiServiceProperties;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.ProviderRequest;

class ConclusionClientImplTest {
    private MockRestServiceServer server;
    private ConclusionClientImpl client;
    private ProviderRequest request;

    @BeforeEach void fixture() throws Exception {
        var builder = RestClient.builder().baseUrl("http://localhost:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AiServiceProperties(); properties.setInternalApiKey("internal-test-key");
        client = new ConclusionClientImpl(builder.build(), properties);
        request = new ProviderRequest(UUID.randomUUID().toString(), 37L,
            new ObjectMapper().readTree("{\"minutes\":{\"sourceRef\":\"minutes.v1\",\"content\":\"Current text\"}}"));
    }

    @Test void typedRoundtripUsesInternalKeyAndPreservesJobIdAndStructuredSources() {
        server.expect(requestTo("http://localhost:8000/api/ai/conclusions/generate"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("X-Internal-Api-Key", "internal-test-key"))
            .andExpect(jsonPath("$.generationId").value(request.generationId()))
            .andExpect(jsonPath("$.context.minutes.content").value("Current text"))
            .andRespond(withSuccess("""
                {"generationId":"%s","result":{"summary":"Summary","decisions":[],"pollConclusions":[],
                "actionItems":[{"task":"Work","assignee":null,"deadline":null,"sourceRefs":["minutes.v1"]}],"unresolvedIssues":[]}}
                """.formatted(request.generationId()), MediaType.APPLICATION_JSON));
        var response = client.generate(request);
        assertEquals(request.generationId(), response.generationId());
        assertNull(response.result().actionItems().get(0).assignee());
        assertEquals("minutes.v1", response.result().actionItems().get(0).sourceRefs().get(0));
        server.verify();
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 409, 429, 502, 503})
    void providerFailuresExposeOnlySafeCodesAndRetryOnlyBusyResponses(int status) {
        server.expect(requestTo("http://localhost:8000/api/ai/conclusions/generate"))
            .andRespond(withStatus(HttpStatus.valueOf(status)).body("secret-provider-details"));
        var error = assertThrows(ConclusionClient.Failure.class, () -> client.generate(request));
        assertEquals(status == 409 || status == 429, error.retryable);
        assertFalse(error.toString().contains("secret-provider-details"));
        server.verify();
    }
}
