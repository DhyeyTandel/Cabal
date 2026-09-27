package dev.dhyey.cabrouter.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the read-only demo website's single page at the root. Spring Boot's own welcome-page
 * mechanism would forward "/" to "index.html" automatically, but that internal forward does not
 * round-trip through MockMvc's test dispatcher, so the page is served directly here instead:
 * the same bytes, in one dispatch, with no forward involved.
 */
@RestController
public class HomeController {

    private static final Resource INDEX = new ClassPathResource("static/index.html");

    @GetMapping("/")
    public ResponseEntity<Resource> index() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(INDEX);
    }
}
