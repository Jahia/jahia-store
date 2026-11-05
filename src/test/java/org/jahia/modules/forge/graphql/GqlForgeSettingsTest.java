package org.jahia.modules.forge.graphql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S28 / D13 — the GraphQL settings DTO must never expose the stored password. The client only
 * needs a boolean {@code passwordSet}; the base64-obfuscated value is an at-rest form that is
 * never serialized to a client. Pure, no mocks.
 */
class GqlForgeSettingsTest {

    @Test
    @DisplayName("builder-populated DTO exposes url/id/user and a passwordSet boolean")
    void exposesExpectedFieldsOnly() {
        // Arrange
        GqlForgeSettings dto = GqlForgeSettings.builder("mysite")
                .url("https://nexus.internal/repository/store/")
                .id("remote-repository")
                .user("svc")
                .passwordSet(true)
                .build();

        // Act + Assert
        assertThat(dto.getSiteKey()).isEqualTo("mysite");
        assertThat(dto.getUrl()).isEqualTo("https://nexus.internal/repository/store/");
        assertThat(dto.getId()).isEqualTo("remote-repository");
        assertThat(dto.getUser()).isEqualTo("svc");
        assertThat(dto.isPasswordSet()).isTrue();
    }

    @Test
    @DisplayName("passwordSet reflects builder value and defaults to false")
    void passwordSetDefaultsFalse() {
        assertThat(GqlForgeSettings.builder("s").build().isPasswordSet()).isFalse();
        assertThat(GqlForgeSettings.builder("s").passwordSet(true).build().isPasswordSet()).isTrue();
    }

    @Test
    @DisplayName("no accessor returns a stored password value (only the boolean passwordSet exists)")
    void noPasswordGetter() {
        // Arrange: there must be no getter/field surfacing the raw password to the client.
        for (Method m : GqlForgeSettings.class.getMethods()) {
            final String name = m.getName().toLowerCase();
            // The only password-related accessor allowed is the boolean isPasswordSet().
            boolean returnsPasswordValue = name.contains("password")
                    && m.getReturnType() == String.class;
            assertThat(returnsPasswordValue)
                    .as("method %s must not return a password string", m.getName())
                    .isFalse();
        }
    }
}
