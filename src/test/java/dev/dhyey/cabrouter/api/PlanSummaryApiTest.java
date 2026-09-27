package dev.dhyey.cabrouter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET /api/plans}: newest first, summary fields, for the website's plan picker. */
@SpringBootTest
@ActiveProfiles("test")
class PlanSummaryApiTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void listsPlansNewestFirstWithSummaryFields() throws Exception {
        long officeId = id(mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isCreated()).andReturn());
        long emp = id(mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Asha", "gender": "FEMALE", "latitude": 13.12, "longitude": 77.62,
                                 "officeId": %d}""".formatted(officeId)))
                .andExpect(status().isCreated()).andReturn());

        long firstPlan = id(mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": [%d]}""".formatted(officeId, emp)))
                .andExpect(status().isCreated()).andReturn());
        long secondPlan = id(mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T22:00:00", "direction": "DROP",
                                 "employeeIds": [%d]}""".formatted(officeId, emp)))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(get("/api/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(secondPlan))
                .andExpect(jsonPath("$[0].officeId").value(officeId))
                .andExpect(jsonPath("$[0].officeName").value("Manyata Tech Park"))
                .andExpect(jsonPath("$[0].direction").value("DROP"))
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].cabCount").value(1))
                .andExpect(jsonPath("$[0].employeeCount").value(1))
                .andExpect(jsonPath("$[0].totalDistanceKm").isNumber())
                .andExpect(jsonPath("$[0].totalCost").isNumber())
                .andExpect(jsonPath("$[1].id").value(firstPlan));
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
