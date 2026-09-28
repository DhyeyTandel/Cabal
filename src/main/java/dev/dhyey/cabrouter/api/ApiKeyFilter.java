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
 * request, on any path, must carry header {@code X-API-Key} matching {@code cabal.api-key},
 * with one exemption: {@code POST /api/sandbox/plan} (see {@link #needsKey}), the public "try
 * it" demo, which never touches the database. When {@code cabal.api-key} is blank the filter
 * lets everything through, which is what local dev, tests and the demo script rely on. The key
 * is never logged, and comparison is constant time.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    static final String SANDBOX_PLAN_PATH = "/api/sandbox/plan";
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
     * Every request that could change data needs the key, whatever its path, with one
     * exemption: a POST whose raw request URI is EXACTLY {@code /api/sandbox/plan} (the
     * public sandbox, which persists nothing; see {@code SandboxController}). Matching on
     * the path is otherwise deliberately avoided: the raw request URI can differ from the
     * path Spring routes on (for example {@code /%61pi/offices} or {@code //api/offices}
     * both reach {@code /api/offices}), so a path prefix check could be bypassed. The
     * sandbox exemption is intentionally exact-match, not a prefix: an encoded, doubled-slash
     * or trailing-slash variant of the path, or a sub-path of it, still needs the key. No
     * other public endpoint writes anything, so there is nothing else to exempt.
     */
    private static boolean needsKey(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        return !("POST".equals(method) && SANDBOX_PLAN_PATH.equals(request.getRequestURI()));
    }
}
