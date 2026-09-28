package dev.dhyey.cabrouter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Validation on {@code POST /api/sandbox/plan}: rider count, radius, and an empty list. */
@SpringBootTest
@ActiveProfiles("test")
class SandboxValidationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void moreThan40RidersIsRejected() throws Exception {
        StringBuilder riders = new StringBuilder("[");
        for (int i = 0; i < 41; i++) {
            if (i > 0) {
                riders.append(",");
            }
            riders.append("{\"latitude\": 13.03, \"longitude\": 77.6, \"woman\": false}");
        }
        riders.append("]");

        mvc.perform(post("/api/sandbox/plan").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "PICKUP", "shiftTime": "07:30", "fleet": "SEDANS",
                                 "riders": %s}""".formatted(riders)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("the sandbox can plan at most 40 riders"));
    }

    @Test
    void aRiderThirtyKmAwayIsRejected() throws Exception {
        // Same longitude as the office, ~30 km further north in a straight line.
        mvc.perform(post("/api/sandbox/plan").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "PICKUP", "shiftTime": "07:30", "fleet": "SEDANS",
                                 "riders": [{"latitude": 13.32, "longitude": 77.6206, "woman": false}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("rider 1 is more than 25 km from the office"));
    }

    @Test
    void emptyRidersIsRejected() throws Exception {
        mvc.perform(post("/api/sandbox/plan").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "PICKUP", "shiftTime": "07:30", "fleet": "SEDANS",
                                 "riders": []}"""))
                .andExpect(status().isBadRequest());
    }
}
