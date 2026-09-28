package dev.dhyey.cabrouter.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@link ApiErrorController} through the real dispatch. {@code GET /api/plans/999999} is the
 * existing 404 path exercised in {@link PlanApiIntegrationTest#errorsComeBackAsProblemDetails};
 * it is repeated here to confirm registering our own {@code ErrorController} did not disturb
 * ordinary exception-driven 404s, which never go through {@code /error} at all.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiErrorControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void directlyRequestingErrorIsNotFoundWithNoLeakedDetail() throws Exception {
        mvc.perform(get("/error"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("org."))));
    }

    @Test
    void anExistingNotFoundPathIsStillNotFound() throws Exception {
        mvc.perform(get("/api/plans/999999")).andExpect(status().isNotFound());
    }
}
