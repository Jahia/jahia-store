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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoreManifestParserTest {

    private static final String VALID = "---\n"
            + "name: my-module\n"
            + "groupId: org.example.modules\n"
            + "title: My Module\n"
            + "license: Apache-2.0\n"
            + "icon: docs/icon.png\n"
            + "screenshots:\n"
            + "  - docs/shot-1.png\n"
            + "  - docs/shot-2.png\n"
            + "requiredVersion: 8.2.0.0\n"
            + "dependencies:\n"
            + "  - default\n"
            + "  - search\n"
            + "---\n"
            + "# My Module\n"
            + "\n"
            + "It does *things*.\n"
            + "\n"
            + "## Versions\n"
            + "\n"
            + "- [1.2.0](store/1.2.0)\n"
            + "- [1.1.0](./store/1.1.0/)\n"
            + "\n"
            + "## Support\n"
            + "\n"
            + "Ask on the forum.\n"
            + "\n"
            + "## Tabs\n"
            + "\n"
            + "- [Installation](store/installation.md)\n"
            + "- [License](store/license.md)\n";

    @Test
    @DisplayName("the Tabs section lists the tabs with their Markdown files, and is not part of the description")
    void tabsSection() {
        StoreManifest manifest = StoreManifestParser.parse(VALID);

        assertThat(manifest.getTabs()).extracting(StoreManifest.TabLink::getTitle).containsExactly("Installation", "License");
        assertThat(manifest.getTabs()).extracting(StoreManifest.TabLink::getFile).containsExactly("store/installation.md", "store/license.md");
        assertThat(manifest.getDescriptionHtml()).doesNotContain("Tabs").doesNotContain("installation.md");
        assertThatThrownBy(() -> StoreManifestParser.parse(VALID.replace("(store/license.md)", "(store/license.txt)")))
                .hasMessageContaining("Markdown file");
        assertThatThrownBy(() -> StoreManifestParser.parse(VALID.replace("[License]", "[installation]")))
                .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("parses the shared fields and renders the body to HTML")
    void parsesFieldsAndBody() {
        StoreManifest manifest = StoreManifestParser.parse(VALID);

        assertThat(manifest.getName()).isEqualTo("my-module");
        assertThat(manifest.getGroupId()).isEqualTo("org.example.modules");
        assertThat(manifest.getTitle()).isEqualTo("My Module");
        assertThat(manifest.getLicense()).isEqualTo("Apache-2.0");
        assertThat(manifest.getIcon()).isEqualTo("docs/icon.png");
        assertThat(manifest.getScreenshots()).containsExactly("docs/shot-1.png", "docs/shot-2.png");
        assertThat(manifest.getRequiredVersion()).isEqualTo("8.2.0.0");
        assertThat(manifest.getDependencies()).containsExactly("default", "search");
        assertThat(manifest.getDescriptionHtml()).contains("<h1>My Module</h1>").contains("<em>things</em>");
    }

    @Test
    @DisplayName("the Versions section lists the versions with their folders, and is not part of the description")
    void versionsSection() {
        StoreManifest manifest = StoreManifestParser.parse(VALID);

        assertThat(manifest.getVersions()).extracting(StoreManifest.VersionLink::getNumber).containsExactly("1.2.0", "1.1.0");
        assertThat(manifest.getVersions()).extracting(StoreManifest.VersionLink::getFolder).containsExactly("store/1.2.0", "store/1.1.0");
        assertThat(manifest.getDescriptionHtml()).doesNotContain("1.2.0").doesNotContain("Versions").contains("Ask on the forum");
    }

    @Test
    @DisplayName("a manifest without a Versions section has no version")
    void noVersionsSection() {
        String withoutVersions = VALID.substring(0, VALID.indexOf("## Versions"));

        assertThat(StoreManifestParser.parse(withoutVersions).getVersions()).isEmpty();
    }

    @Test
    @DisplayName("a version listed twice, a non-version link text, or a line that is not a link is refused")
    void versionsSectionRules() {
        assertThatThrownBy(() -> StoreManifestParser.parse(VALID.replace("[1.1.0]", "[1.2.0]")))
                .isInstanceOf(StoreManifestException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> StoreManifestParser.parse(VALID.replace("[1.1.0]", "[latest]")))
                .hasMessageContaining("Maven version");
        assertThatThrownBy(() -> StoreManifestParser.parse(VALID.replace("- [1.1.0](./store/1.1.0/)", "- 1.1.0 is old")))
                .hasMessageContaining("not a link");
    }

    @Test
    @DisplayName("a version folder that leaves the repository or is a URL is refused")
    void versionFolderStaysInRepository() {
        assertThatThrownBy(() -> StoreManifestParser.parse(VALID.replace("(store/1.2.0)", "(../other/1.2.0)")))
                .hasMessageContaining("inside the repository");
        assertThatThrownBy(() -> StoreManifestParser.parse(VALID.replace("(store/1.2.0)", "(https://example.com/1.2.0)")))
                .hasMessageContaining("inside the repository");
    }

    @Test
    @DisplayName("a missing mandatory field is refused and named")
    void mandatoryFieldNamed() {
        String withoutTitle = VALID.replace("title: My Module\n", "");

        assertThatThrownBy(() -> StoreManifestParser.parse(withoutTitle))
                .isInstanceOf(StoreManifestException.class)
                .hasMessageContaining("'title'");
    }

    @Test
    @DisplayName("a name that is not a Maven coordinate is refused")
    void nameMustBeCoordinate() {
        String traversal = VALID.replace("name: my-module", "name: ../etc");

        assertThatThrownBy(() -> StoreManifestParser.parse(traversal)).hasMessageContaining("'name'");
    }

    @Test
    @DisplayName("an icon or screenshot path that leaves the repository is refused")
    void filesStayInRepository() {
        String outside = VALID.replace("icon: docs/icon.png", "icon: ../secret.png");
        String absolute = VALID.replace("docs/shot-2.png", "/etc/passwd");

        assertThatThrownBy(() -> StoreManifestParser.parse(outside)).hasMessageContaining("'icon'");
        assertThatThrownBy(() -> StoreManifestParser.parse(absolute)).hasMessageContaining("'screenshots'");
    }

    @Test
    @DisplayName("a file without front matter is refused")
    void frontMatterRequired() {
        assertThatThrownBy(() -> StoreManifestParser.parse("# Just a README\n"))
                .isInstanceOf(StoreManifestException.class)
                .hasMessageContaining("front matter");
        assertThatThrownBy(() -> StoreManifestParser.parse("---\nname: x\n"))
                .hasMessageContaining("closing");
    }

    @Test
    @DisplayName("raw HTML in the body is escaped, not rendered")
    void rawHtmlEscaped() {
        String withScript = VALID.replace("It does *things*.", "It does *things*.\n\n<script>alert(1)</script>");

        assertThat(StoreManifestParser.parse(withScript).getDescriptionHtml())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("dependencies accept a comma-separated string too")
    void dependenciesAsString() {
        String csv = VALID.replace("dependencies:\n  - default\n  - search\n", "dependencies: default, search\n");

        assertThat(StoreManifestParser.parse(csv).getDependencies()).containsExactly("default", "search");
    }
}
