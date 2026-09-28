package dev.dhyey.cabrouter.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the size of sandbox requests before anything reads them.
 *
 * <p>The sandbox is the one public endpoint that accepts a request body without an API
 * key, and Spring parses the whole JSON body before the controller (and its rider cap
 * and rate limit) ever runs. Without this filter, one request with a huge body could
 * exhaust the small production heap. A legitimate 40-rider request is about 4 KB, so
 * bodies over {@value #MAX_BODY_BYTES} bytes are refused with 413, and bodies that do
 * not declare their length (chunked) are refused with 411.
 */
@Component
public class SandboxRequestLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 32 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiKeyFilter.SANDBOX_PLAN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            reject(response, HttpServletResponse.SC_LENGTH_REQUIRED, "the request must declare its length");
            return;
        }
        if (length > MAX_BODY_BYTES) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "the request is too large");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
