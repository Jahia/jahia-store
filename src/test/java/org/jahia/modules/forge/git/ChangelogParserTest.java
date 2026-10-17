package org.jahia.modules.forge.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChangelogParserTest {

    private static final String CHANGELOG = "# Changelog\n"
            + "\n"
            + "## [Unreleased]\n"
            + "- something coming\n"
            + "\n"
            + "## [1.2.0] - 2026-10-01\n"
            + "### Added\n"
            + "- a feature\n"
            + "\n"
            + "## v1.1.0\n"
            + "- a fix\n"
            + "\n"
            + "## 1.0.0 (2026-01-01)\n"
            + "- first\n";

    @Test
    @DisplayName("finds the Keep-a-Changelog section of a version, up to the next heading")
    void bracketedHeading() {
        assertThat(ChangelogParser.section(CHANGELOG, "1.2.0")).isEqualTo("### Added\n- a feature");
    }

    @Test
    @DisplayName("accepts a v prefix and a bare heading")
    void prefixedAndBareHeadings() {
        assertThat(ChangelogParser.section(CHANGELOG, "1.1.0")).isEqualTo("- a fix");
        assertThat(ChangelogParser.section(CHANGELOG, "1.0.0")).isEqualTo("- first");
    }

    @Test
    @DisplayName("a version without a section has no notes")
    void missingSection() {
        assertThat(ChangelogParser.section(CHANGELOG, "9.9.9")).isNull();
        assertThat(ChangelogParser.section(null, "1.0.0")).isNull();
    }

    @Test
    @DisplayName("a version is matched whole: 1.2.0 does not match 1.2.0-rc1 or 11.2.0")
    void wholeVersionOnly() {
        String changelog = "## [1.2.0-rc1]\n- rc\n\n## [11.2.0]\n- eleven\n";

        assertThat(ChangelogParser.section(changelog, "1.2.0")).isNull();
    }
}
