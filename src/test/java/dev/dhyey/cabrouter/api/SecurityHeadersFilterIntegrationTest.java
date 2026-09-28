package dev.dhyey.cabrouter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@link SecurityHeadersFilter} wired for real, the same way {@link ApiKeyFilterIntegrationTest}
 * wires {@link ApiKeyFilter}: {@code webAppContextSetup} does not pick up servlet filters on
 * its own, so the filter bean is added explicitly.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityHeadersFilterIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecurityHeadersFilter securityHeadersFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityHeadersFilter).build();
    }

    @Test
    void rootCarriesEverySecurityHeader() throws Exception {
        assertAllHeaders(mvc.perform(get("/")).andExpect(status().isOk()));
    }

    @Test
    void plansCarryEverySecurityHeader() throws Exception {
        assertAllHeaders(mvc.perform(get("/api/plans")).andExpect(status().isOk()));
    }

    private static void assertAllHeaders(ResultActions result) throws Exception {
        result.andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", "geolocation=(), camera=(), microphone=()"));
    }
}
