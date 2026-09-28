package dev.dhyey.cabrouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dhyey.cabrouter.domain.OfficeRepository;
import dev.dhyey.cabrouter.domain.RoutePlanRepository;
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
 * {@code POST /api/sandbox/plan} is the one write endpoint {@link ApiKeyFilter} exempts, and
 * it persists nothing. {@code cabal.api-key} is configured, with the filter wired into MockMvc
 * as {@link ApiKeyFilterIntegrationTest} does, so the exemption is exercised for real.
 */
@SpringBootTest(properties = "cabal.api-key=test-key")
@ActiveProfiles("test")
class SandboxControllerIntegrationTest {

    private static final String VALID_BODY = """
            {"direction": "PICKUP", "shiftTime": "07:30", "fleet": "SEDANS",
             "riders": [{"latitude": 13.03, "longitude": 77.6, "woman": true}]}""";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApiKeyFilter apiKeyFilter;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private RoutePlanRepository plans;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(apiKeyFilter).build();
    }

    @Test
    void planWithoutAKeyReturnsCabsAndPersistsNothing() throws Exception {
        long officesBefore = offices.count();
        long plansBefore = plans.count();

        mvc.perform(post("/api/sandbox/plan").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cabCount").value(1))
                .andExpect(jsonPath("$.riderCount").value(1))
                .andExpect(jsonPath("$.officeLatitude").value(13.0475))
                .andExpect(jsonPath("$.officeLongitude").value(77.6206))
                .andExpect(jsonPath("$.cabs[0].stops[0].name").value("Rider 1"))
                .andExpect(jsonPath("$.cabs[0].stops[0].woman").value(true));

        assertThat(offices.count()).isEqualTo(officesBefore);
        assertThat(plans.count()).isEqualTo(plansBefore);
    }

    @Test
    void officesStillNeedTheKey() throws Exception {
        mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isUnauthorized());
    }

    /** The exemption is exact-match, not a prefix: a trailing slash still needs the key. */
    @Test
    void aTrailingSlashStillNeedsTheKey() throws Exception {
        mvc.perform(post("/api/sandbox/plan/").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }
}
