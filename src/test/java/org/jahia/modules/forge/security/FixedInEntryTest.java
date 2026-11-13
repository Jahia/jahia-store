package org.jahia.modules.forge.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Optional;

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
}
