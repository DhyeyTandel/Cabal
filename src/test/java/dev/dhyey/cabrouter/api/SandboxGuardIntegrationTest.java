package dev.dhyey.cabrouter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** {@code routing.sandbox.per-minute} rejects a second request from the same client with 429. */
@SpringBootTest(properties = {"routing.sandbox.per-minute=1", "routing.sandbox.max-concurrent=5"})
@ActiveProfiles("test")
class SandboxGuardIntegrationTest {

    private static final String VALID_BODY = """
            {"direction": "PICKUP", "shiftTime": "07:30", "fleet": "SEDANS",
             "riders": [{"latitude": 13.03, "longitude": 77.6, "woman": false}]}""";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void aSecondRequestWithinAMinuteIsRejectedWithRetryAfter() throws Exception {
        mvc.perform(post("/api/sandbox/plan").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk());

        mvc.perform(post("/api/sandbox/plan").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.detail").value("the demo is busy, try again in a minute"));
    }
}
