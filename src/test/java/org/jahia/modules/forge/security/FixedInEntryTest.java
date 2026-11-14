package org.jahia.modules.forge.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FixedInEntryTest {

    @Test
    @DisplayName("component@version parses with no groupId")
    void componentAndVersion() {
        Optional<FixedInEntry> entry = FixedInEntry.parse("graphql-dxm-provider@3.10.0");

        assertThat(entry).hasValueSatisfying(e -> {
            assertThat(e.getGroupId()).isNull();
            assertThat(e.getComponent()).isEqualTo("graphql-dxm-provider");
            assertThat(e.getVersion()).isEqualTo("3.10.0");
        });
    }

    @Test
    @DisplayName("groupId:component@version parses all three parts")
    void groupIdComponentAndVersion() {
        Optional<FixedInEntry> entry = FixedInEntry.parse("org.jahia.modules:graphql-dxm-provider@2.24.0");

        assertThat(entry).hasValueSatisfying(e -> {
            assertThat(e.getGroupId()).isEqualTo("org.jahia.modules");
            assertThat(e.getComponent()).isEqualTo("graphql-dxm-provider");
            assertThat(e.getVersion()).isEqualTo("2.24.0");
        });
    }

    @Test
    @DisplayName("surrounding whitespace is ignored")
    void trimsWhitespace() {
        assertThat(FixedInEntry.parse("  jahia-core@8.2.3.7 ")).hasValueSatisfying(e -> {
            assertThat(e.getComponent()).isEqualTo("jahia-core");
            assertThat(e.getVersion()).isEqualTo("8.2.3.7");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"graphql-dxm-provider", "@3.0.0", "graphql@", "graphql@x1", "a:b:c@1.0",
            "../etc@1.0", "a b@1.0", "graphql@1.0/..", ":graphql@1.0"})
    @DisplayName("malformed entries are rejected")
    void rejectsMalformed(String value) {
        assertThat(FixedInEntry.parse(value)).isEmpty();
    }

    // The version rule mirrors the security-updates client (matching/Versions.isWellFormed): the feed must
    // never emit a version the client rejects as malformed.

    /** 64 characters: the longest version the client accepts. */
    private static final String VERSION_64 = "1.0.0-" + "a".repeat(58);

    static Stream<String> clientAcceptedVersions() {
        return Stream.of("2.24.0", "8.2.3.7", "1.0.0-SNAPSHOT", "3.0.0+build", "2_1", "3", "1.0.0-",
                "1.0.0-rc.1", "123456789.1", VERSION_64);
    }

    @ParameterizedTest
    @MethodSource("clientAcceptedVersions")
    @DisplayName("versions the client accepts are accepted")
    void acceptsClientWellFormedVersions(String version) {
        assertThat(FixedInEntry.parse("jahia-core@" + version))
                .hasValueSatisfying(e -> assertThat(e.getVersion()).isEqualTo(version));
    }

    static Stream<String> clientRefusedVersions() {
        return Stream.of("3a", "3_", "3-", "3.", "3..", "3+", "3.-1", "3._", "-1", "a1",
                VERSION_64 + "a", "1234567890", "1.1234567890", "1.0.1234567890-SNAPSHOT");
    }

    @ParameterizedTest
    @MethodSource("clientRefusedVersions")
    @DisplayName("versions the client refuses as malformed are refused")
    void rejectsClientMalformedVersions(String version) {
        assertThat(FixedInEntry.parse("jahia-core@" + version)).isEmpty();
        assertThat(FixedInEntry.parse("org.jahia.modules:graphql-dxm-provider@" + version)).isEmpty();
    }

    @Test
    @DisplayName("a long number after a qualifier is not a leading segment, as in the client")
    void longNumberInQualifierAccepted() {
        assertThat(FixedInEntry.parse("jahia-core@1.0-1234567890")).isPresent();
    }
}
