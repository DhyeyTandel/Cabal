package dev.dhyey.cabrouter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * {@code employees.name} and {@code offices.name} are {@code varchar(120)} (see
 * {@code V1__init.sql}); an over-long name used to reach Postgres unvalidated and come back
 * as a 500. {@code @Size(max = 120)} on {@link dev.dhyey.cabrouter.api.dto.CreateEmployeeRequest}
 * and {@link dev.dhyey.cabrouter.api.dto.CreateOfficeRequest} turns that into a 400.
 */
@SpringBootTest
@ActiveProfiles("test")
class NameLengthValidationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;
    private long officeId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        officeId = id(mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Manyata Tech Park", "latitude": 13.0475, "longitude": 77.6206}"""))
                .andExpect(status().isCreated()).andReturn());
    }

    @Test
    void a121CharacterOfficeNameIsRejected() throws Exception {
        mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "latitude": 13.0475, "longitude": 77.6206}"""
                                .formatted("A".repeat(121))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a120CharacterOfficeNameIsAccepted() throws Exception {
        mvc.perform(post("/api/offices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "latitude": 13.0475, "longitude": 77.6206}"""
                                .formatted("A".repeat(120))))
                .andExpect(status().isCreated());
    }

    @Test
    void a121CharacterEmployeeNameIsRejected() throws Exception {
        mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "gender": "MALE", "latitude": 13.03, "longitude": 77.6, "officeId": %d}"""
                                .formatted("B".repeat(121), officeId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a120CharacterEmployeeNameIsAccepted() throws Exception {
        mvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "gender": "MALE", "latitude": 13.03, "longitude": 77.6, "officeId": %d}"""
                                .formatted("B".repeat(120), officeId)))
                .andExpect(status().isCreated());
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
