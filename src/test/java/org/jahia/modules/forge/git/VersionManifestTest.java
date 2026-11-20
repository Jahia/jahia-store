/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2026 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms & Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionManifestTest {

    private static final String VALID = "---\n"
            + "artifact: https://example.com/releases/{version}/my-module-{version}.jar\n"
            + "date: 2026-03-04\n"
            + "requiredVersion: 8.2.1.0\n"
            + "dependencies: default, search\n"
            + "---\n"
            + "### Improvements\n"
            + "\n"
            + "- Added the thing. ![The thing](thing.png)\n";

    @Test
    @DisplayName("parses the version fields, fills the placeholder of the artefact URL and keeps the notes")
    void parsesFields() {
        VersionManifest version = VersionManifest.parse("1.2.0", VALID);

        assertThat(version.getArtifactUrl()).isEqualTo("https://example.com/releases/1.2.0/my-module-1.2.0.jar");
        assertThat(version.getDate()).isEqualTo(Instant.parse("2026-03-04T00:00:00Z"));
        assertThat(version.isDisabled()).isFalse();
        assertThat(version.getRequiredVersion()).isEqualTo("8.2.1.0");
        assertThat(version.getDependencies()).containsExactly("default", "search");
        assertThat(version.getNotesMarkdown()).contains("Added the thing");
    }

    @Test
    @DisplayName("the artefact is mandatory and must be an absolute http or https URL")
    void artifactRules() {
        assertThatThrownBy(() -> VersionManifest.parse("1.2.0", VALID.replace("artifact: ", "artefact: ")))
                .isInstanceOf(StoreManifestException.class)
                .hasMessageContaining("version 1.2.0").hasMessageContaining("'artifact'");
        assertThatThrownBy(() -> VersionManifest.parse("1.2.0", VALID.replace("https://example.com", "ftp://example.com")))
                .hasMessageContaining("'artifact'");
        assertThatThrownBy(() -> VersionManifest.parse("1.2.0", VALID.replace("https://example.com/releases/", "releases/")))
                .hasMessageContaining("'artifact'");
    }

    @Test
    @DisplayName("disabled: true disables the version, and anything but a boolean is refused")
    void disabledFlag() {
        assertThat(VersionManifest.parse("1.2.0", VALID.replace("date: 2026-03-04\n", "disabled: true\n")).isDisabled()).isTrue();
        assertThat(VersionManifest.parse("1.2.0", VALID.replace("date: 2026-03-04\n", "disabled: no\n")).isDisabled()).isFalse();
        assertThatThrownBy(() -> VersionManifest.parse("1.2.0", VALID.replace("date: 2026-03-04\n", "disabled: maybe\n")))
                .hasMessageContaining("'disabled'");
    }

    @Test
    @DisplayName("the date is optional and must be YYYY-MM-DD")
    void dateRules() {
        assertThat(VersionManifest.parse("1.2.0", VALID.replace("date: 2026-03-04\n", "")).getDate()).isNull();
        assertThatThrownBy(() -> VersionManifest.parse("1.2.0", VALID.replace("2026-03-04", "March 4")))
                .hasMessageContaining("'date'");
    }

    @Test
    @DisplayName("a file with an empty front matter still needs its artefact")
    void emptyFrontMatter() {
        assertThatThrownBy(() -> VersionManifest.parse("1.2.0", "---\n---\nnotes\n")).hasMessageContaining("'artifact'");
    }
}
