package dev.dhyey.cabrouter.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Write protection for the public API. Reads (GET, HEAD, OPTIONS) are always open; every other
 * request, on any path, must carry header {@code X-API-Key} matching {@code cabal.api-key}.
 * When that property is blank the filter lets everything through, which is what local dev, tests
 * and the demo script rely on. The key is never logged, and comparison is constant time.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final String UNAUTHORIZED_BODY =
            "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"changing data needs an API key\"}";

    private final String apiKey;

    public ApiKeyFilter(@Value("${cabal.api-key:}") String apiKey,
                        @Value("${cabal.require-api-key:false}") boolean requireApiKey) {
        if (requireApiKey && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException(
                    "cabal.require-api-key is true but cabal.api-key is blank; set CABAL_API_KEY");
        }
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (apiKey == null || apiKey.isBlank() || !needsKey(request)) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }

    /**
     * Every request that could change data needs the key, whatever its path. Matching on
     * the path is deliberately avoided: the raw request URI can differ from the path Spring
     * routes on (for example {@code /%61pi/offices} or {@code //api/offices} both reach
     * {@code /api/offices}), so a path prefix check could be bypassed. No public endpoint
     * writes anything, so there is nothing to exempt.
     */
    private static boolean needsKey(HttpServletRequest request) {
        String method = request.getMethod();
        return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
    }
}
