package dev.dhyey.cabrouter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
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

/** {@code routing.max-riders-per-plan} rejects both an oversized plan and an oversized add. */
@SpringBootTest(properties = "routing.max-riders-per-plan=2")
@ActiveProfiles("test")
class RiderCapTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;
    private long officeId;
    private final List<Long> employeeIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        officeId = id(mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isCreated()).andReturn());

        double[][] homes = {{13.12, 77.62}, {13.125, 77.625}, {13.13, 77.615}};
        for (double[] home : homes) {
            employeeIds.add(id(mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Emp", "gender": "MALE", "latitude": %s, "longitude": %s, "officeId": %d}"""
                                    .formatted(home[0], home[1], officeId)))
                    .andExpect(status().isCreated()).andReturn()));
        }
    }

    @Test
    void creatingAPlanOverTheCapIsRejected() throws Exception {
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": %s}""".formatted(officeId, employeeIds)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("a plan can have at most 2 riders"));
    }

    @Test
    void addingARiderPastTheCapIsRejected() throws Exception {
        long planId = id(mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": %s}""".formatted(officeId, employeeIds.subList(0, 2))))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(post("/api/plans/{id}/employees/{emp}", planId, employeeIds.get(2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("a plan can have at most 2 riders"));
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
