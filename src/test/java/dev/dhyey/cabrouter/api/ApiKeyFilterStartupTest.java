package dev.dhyey.cabrouter.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dhyey.cabrouter.CabRouterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * {@code cabal.require-api-key=true} with a blank key is a misconfiguration that must never
 * reach production silently: the app should refuse to start at all (see {@link ApiKeyFilter}'s
 * constructor).
 */
class ApiKeyFilterStartupTest {

    @Test
    void requireApiKeyWithBlankKeyFailsStartup() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(CabRouterApplication.class)
                        .web(WebApplicationType.NONE)
                        .profiles("test")
                        .run("--cabal.require-api-key=true", "--cabal.api-key="))
                .isInstanceOf(BeanCreationException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
