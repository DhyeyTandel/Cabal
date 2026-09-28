package dev.dhyey.cabrouter.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * Standing time-window preferences over HTTP: creating an employee with one, clearing it
 * with {@code PUT .../time-window}, and a plan carrying it through to the response.
 */
@SpringBootTest
@ActiveProfiles("test")
class TimeWindowApiTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void createEchoesTheWindowAndPutClearsIt() throws Exception {
        long officeId = id(mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isCreated()).andReturn());

        MvcResult created = mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Asha", "gender": "FEMALE", "latitude": 13.12, "longitude": 77.62,
                                 "officeId": %d, "earliestPickup": "06:00"}""".formatted(officeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.earliestPickup").value("06:00"))
                .andExpect(jsonPath("$.latestDrop").value(nullValue()))
                .andReturn();
        long empId = id(created);

        mvc.perform(put("/api/employees/{id}/time-window", empId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"earliestPickup": null, "latestDrop": "23:15"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earliestPickup").value(nullValue()))
                .andExpect(jsonPath("$.latestDrop").value("23:15"));

        mvc.perform(put("/api/employees/{id}/time-window", empId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"earliestPickup": null, "latestDrop": null}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earliestPickup").value(nullValue()))
                .andExpect(jsonPath("$.latestDrop").value(nullValue()));
    }

    @Test
    void planResponseCarriesTheWindowAndFlagsAMissedOne() throws Exception {
        long officeId = id(mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isCreated()).andReturn());

        // A 09:00 shift's office time is 08:45 (the 15-minute arrival buffer); a few km
        // out, the natural pickup is well before that, so an earliest of 08:44 is missed.
        long empId = id(mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Asha", "gender": "FEMALE", "latitude": 13.12, "longitude": 77.62,
                                 "officeId": %d, "earliestPickup": "08:44"}""".formatted(officeId)))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": [%d]}""".formatted(officeId, empId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowsMissed").value(1))
                .andExpect(jsonPath("$.cabs[0].stops[0].earliestPickup").value("08:44"))
                .andExpect(jsonPath("$.cabs[0].stops[0].windowMissed").value(true));
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
