package dev.dhyey.cabrouter.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** With routing.travel-model=osrm, plans are timed by the (stub) OSRM server. */
@SpringBootTest
@ActiveProfiles("test")
class OsrmWiringIntegrationTest {

    private static final StubOsrmServer OSRM = start();

    private static StubOsrmServer start() {
        try {
            return new StubOsrmServer(100);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void osrm(DynamicPropertyRegistry registry) {
        registry.add("routing.travel-model", () -> "osrm");
        registry.add("routing.osrm.base-url", OSRM::baseUrl);
    }

    @AfterAll
    static void stop() {
        OSRM.close();
    }

    @Autowired
    private WebApplicationContext context;

    @Test
    void plansAreTimedByOsrmWhenConfigured() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String office = mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"HQ\",\"latitude\":13.0,\"longitude\":77.6}"))
                .andReturn().getResponse().getContentAsString();
        long officeId = ((Number) JsonPath.read(office, "$.id")).longValue();

        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String emp = mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"E%d\",\"gender\":\"MALE\",\"latitude\":%s,\"longitude\":77.6,\"officeId\":%d}"
                                    .formatted(i, 13.0 + i * 0.02, officeId)))
                    .andReturn().getResponse().getContentAsString();
            ids.add(((Number) JsonPath.read(emp, "$.id")).longValue());
        }
        int before = OSRM.requests.get();

        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": %s}""".formatted(officeId, ids)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cabs[0].travelSource").value("OSRM"));

        assertThat(OSRM.requests.get() - before).isEqualTo(1);
    }
}
