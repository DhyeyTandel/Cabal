package dev.dhyey.cabrouter.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SandboxRequestLimitFilterTest {

    private final SandboxRequestLimitFilter filter = new SandboxRequestLimitFilter();

    private MockHttpServletResponse run(String uri, byte[] body, boolean declareLength) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return declareLength ? super.getContentLengthLong() : -1;
            }
        };
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void aNormalSandboxRequestPassesThrough() throws Exception {
        assertThat(run("/api/sandbox/plan", new byte[4 * 1024], true).getStatus()).isEqualTo(200);
    }

    @Test
    void anOversizedSandboxBodyIsRefusedBeforeAnythingReadsIt() throws Exception {
        MockHttpServletResponse response = run("/api/sandbox/plan", new byte[SandboxRequestLimitFilter.MAX_BODY_BYTES + 1], true);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void aSandboxBodyWithoutADeclaredLengthIsRefused() throws Exception {
        assertThat(run("/api/sandbox/plan", new byte[10], false).getStatus()).isEqualTo(411);
    }

    @Test
    void otherPathsAreNotAffected() throws Exception {
        assertThat(run("/api/offices", new byte[SandboxRequestLimitFilter.MAX_BODY_BYTES + 1], true).getStatus())
                .isEqualTo(200);
    }
}
