package dev.dhyey.cabrouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** Full stack: HTTP, validation, service, JPA and Flyway against in-memory H2. */
@SpringBootTest
@ActiveProfiles("test")
class PlanApiIntegrationTest {

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

        // Two tight groups of four, north and south of the office, plus two stragglers east.
        double[][] homes = {
                {13.12, 77.62}, {13.125, 77.625}, {13.13, 77.615}, {13.118, 77.63},
                {12.97, 77.62}, {12.965, 77.625}, {12.96, 77.615}, {12.972, 77.61},
                {13.05, 77.72}, {13.055, 77.73}};
        for (int i = 0; i < homes.length; i++) {
            employeeIds.add(id(mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Emp %d", "gender": "%s", "latitude": %s, "longitude": %s, "officeId": %d}"""
                                    .formatted(i, i % 3 == 0 ? "FEMALE" : "MALE", homes[i][0], homes[i][1], officeId)))
                    .andExpect(status().isCreated()).andReturn()));
        }
    }

    @Test
    void planCancelAndLateBookingRoundTrip() throws Exception {
        List<Long> firstNine = employeeIds.subList(0, 9);
        MvcResult created = mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": %s, "cabCapacity": 4}""".formatted(officeId, firstNine)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cabCount").value(3))
                .andExpect(jsonPath("$.employeeCount").value(9))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.cabs[0].officeTime").value("2026-10-01T08:45:00"))
                .andExpect(jsonPath("$.cabs[0].travelSource").value("HAVERSINE"))
                .andReturn();
        long planId = id(created);

        // Cancel someone in the north group. Only their cab should change (covered by ShiftPlanTest).
        long cancelled = employeeIds.get(1);
        mvc.perform(delete("/api/plans/{id}/employees/{emp}", planId, cancelled))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCount").value(8))
                .andExpect(jsonPath("$.revision").value(2));

        // Late booking: the east straggler not in the plan should take the free seat, not a new cab.
        long late = employeeIds.get(9);
        mvc.perform(post("/api/plans/{id}/employees/{emp}", planId, late))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCount").value(9))
                .andExpect(jsonPath("$.cabCount").value(3));

        mvc.perform(post("/api/plans/{id}/employees/{emp}", planId, late))
                .andExpect(status().isConflict());

        // Pickup stops come out in driving order with ETAs ascending towards the office.
        String body = mvc.perform(get("/api/plans/{id}", planId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (Map<String, Object> cab : cabs(body)) {
            List<String> etas = JsonPath.read(cab, "$.stops[*].eta");
            assertThat(etas).isSorted();
            assertThat(etas.get(etas.size() - 1)).isLessThan((String) cab.get("officeTime"));
        }
    }

    @Test
    void fullReplanAfterCancellationStillRoutesEveryone() throws Exception {
        long planId = id(mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T22:30:00", "direction": "DROP",
                                 "employeeIds": %s}""".formatted(officeId, employeeIds)))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(delete("/api/plans/{id}/employees/{emp}", planId, employeeIds.get(0)).param("strategy", "FULL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCount").value(9))
                .andExpect(jsonPath("$.cabs[0].officeTime").value("2026-10-01T22:40:00"));
    }

    @Test
    void mixedFleetIsRightSizedAndAnUndersizedFleetIsRejected() throws Exception {
        List<Long> northAndSouth = employeeIds.subList(0, 8);
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": %s,
                                 "fleet": [{"name": "SEDAN", "seats": 4}, {"name": "SUV", "seats": 6, "available": 1}]}"""
                                .formatted(officeId, northAndSouth)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cabCount").value(2))
                .andExpect(jsonPath("$.cabs[*].vehicleType").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("SEDAN"))))
                .andExpect(jsonPath("$.fleet[0].name").value("SEDAN"))
                .andExpect(jsonPath("$.fleet[0].used").value(2))
                .andExpect(jsonPath("$.fleet[1].used").value(0))
                .andExpect(jsonPath("$.fleet[0].costPerTrip").value(1000.0))
                .andExpect(jsonPath("$.totalCost").value(org.hamcrest.Matchers.greaterThan(2000.0)));

        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": %s, "fleet": [{"name": "SEDAN", "seats": 4, "available": 1}]}"""
                                .formatted(officeId, northAndSouth)))
                .andExpect(status().is(422));

        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": %s, "cabCapacity": 4, "fleet": [{"name": "SEDAN", "seats": 4}]}"""
                                .formatted(officeId, northAndSouth)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void errorsComeBackAsProblemDetails() throws Exception {
        mvc.perform(get("/api/plans/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("plan 999999 not found"));

        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": [], "cabCapacity": 40}""".formatted(officeId)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": [%d, %d]}""".formatted(officeId, employeeIds.get(0), employeeIds.get(0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("employeeIds contains duplicates"));
    }

    @Test
    void dissolveSuggestionsAndDissolveCabEndpoints() throws Exception {
        // North (0-3) and south (4-7) fill a 4-seat cab exactly each; east (8-9) is left
        // with two free seats. Thinning south to one rider makes it dissolvable into east.
        long planId = id(mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"officeId": %d, "shiftTime": "2026-10-01T09:00:00", "direction": "PICKUP",
                                 "employeeIds": %s, "cabCapacity": 4, "maxRideMinutes": 240}"""
                                .formatted(officeId, employeeIds)))
                .andExpect(status().isCreated()).andReturn());

        // Not dissolvable yet: north and south are both full, so east has nowhere to send its riders.
        String before = mvc.perform(get("/api/plans/{id}", planId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int eastCabNumber = cabNumberFor(before, employeeIds.get(8));
        mvc.perform(post("/api/plans/{id}/cabs/{cabNumber}/dissolve", planId, eastCabNumber))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/plans/{id}/employees/{emp}", planId, employeeIds.get(5))).andExpect(status().isOk());
        mvc.perform(delete("/api/plans/{id}/employees/{emp}", planId, employeeIds.get(6))).andExpect(status().isOk());
        String thinned = mvc.perform(delete("/api/plans/{id}/employees/{emp}", planId, employeeIds.get(7)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int southCabNumber = cabNumberFor(thinned, employeeIds.get(4));
        int cabCountBefore = ((Number) JsonPath.read(thinned, "$.cabCount")).intValue();

        mvc.perform(get("/api/plans/{id}/dissolve-suggestions", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.cabNumber == %d)]".formatted(southCabNumber)).exists());

        mvc.perform(post("/api/plans/{id}/cabs/{cabNumber}/dissolve", planId, southCabNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cabCount").value(cabCountBefore - 1));

        mvc.perform(post("/api/plans/{id}/cabs/{cabNumber}/dissolve", planId, 9999))
                .andExpect(status().isNotFound());
    }

    private static int cabNumberFor(String planBody, long employeeId) {
        for (Map<String, Object> cab : cabs(planBody)) {
            List<Number> ids = JsonPath.read(cab, "$.stops[*].employeeId");
            if (ids.stream().anyMatch(n -> n.longValue() == employeeId)) {
                return ((Number) cab.get("cabNumber")).intValue();
            }
        }
        throw new AssertionError("employee " + employeeId + " not found in any cab");
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static List<Map<String, Object>> cabs(String plan) {
        return JsonPath.read(plan, "$.cabs");
    }
}
