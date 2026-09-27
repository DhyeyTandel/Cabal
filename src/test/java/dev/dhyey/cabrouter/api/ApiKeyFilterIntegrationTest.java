package dev.dhyey.cabrouter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@link ApiKeyFilter} wired for real, with {@code cabal.api-key} set. The MockMvc built by
 * {@code webAppContextSetup} does not pick up servlet filters on its own, so the filter bean
 * is added explicitly, unlike {@link PlanApiIntegrationTest} where no key is configured.
 */
@SpringBootTest(properties = "cabal.api-key=test-key")
@ActiveProfiles("test")
class ApiKeyFilterIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApiKeyFilter apiKeyFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(apiKeyFilter).build();
    }

    @Test
    void writeWithoutHeaderIsUnauthorized() throws Exception {
        mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("changing data needs an API key"));
    }

    @Test
    void writeWithCorrectHeaderIsCreated() throws Exception {
        mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "test-key")
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void writeWithWrongHeaderIsUnauthorized() throws Exception {
        mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "not-the-key")
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readWithoutHeaderIsOk() throws Exception {
        mvc.perform(get("/api/plans")).andExpect(status().isOk());
    }

    /**
     * The key is required for every write, not just paths starting with /api/. A path
     * prefix check could be bypassed with an encoded or doubled slash path that Spring
     * still routes to an /api/ controller.
     */
    @Test
    void writesOutsideTheApiPrefixStillNeedTheKey() throws Exception {
        mvc.perform(post("/healthz")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/anything")).andExpect(status().isUnauthorized());
    }
}
