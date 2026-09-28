package dev.dhyey.cabrouter.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

/** {@link ApiErrorController} in isolation: no Spring context, just the request attribute. */
class ApiErrorControllerTest {

    private final ApiErrorController controller = new ApiErrorController();

    @Test
    void aForwardedErrorReturnsItsOwnStatusWithAGenericDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);

        ProblemDetail detail = controller.handleError(request);

        assertThat(detail.getStatus()).isEqualTo(500);
        assertThat(detail.getDetail()).doesNotContainIgnoringCase("exception").doesNotContain("org.");
    }

    @Test
    void aDirectRequestWithNoStatusAttributeIsNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ProblemDetail detail = controller.handleError(request);

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(detail.getDetail()).doesNotContainIgnoringCase("exception").doesNotContain("org.");
    }

    @Test
    void anUnrecognisedStatusCodeFallsBackToInternalServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 999);

        ProblemDetail detail = controller.handleError(request);

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }
}
