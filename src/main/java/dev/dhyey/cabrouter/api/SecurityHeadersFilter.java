package dev.dhyey.cabrouter.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Browser security headers on every response, applied before anything else runs so they land
 * even on a response an earlier-in-chain filter (rate limit, API key) short-circuits. The
 * Content-Security-Policy has no {@code 'unsafe-inline'} or {@code 'unsafe-eval'}; every
 * script and style the demo site needs is loaded from a file or an allow-listed origin
 * (see {@code static/index.html}, {@code app.js}, {@code app.css}), and JS never writes an
 * HTML {@code style} attribute, only the {@code element.style} property, which the policy
 * does not restrict.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; "
            + "script-src 'self' https://cdnjs.cloudflare.com; "
            + "style-src 'self' https://cdnjs.cloudflare.com https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; "
            + "img-src 'self' data: https://tile.openstreetmap.org https://cdnjs.cloudflare.com; "
            + "connect-src 'self'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'none'; "
            + "form-action 'none'; "
            + "object-src 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        chain.doFilter(request, response);
    }
}
