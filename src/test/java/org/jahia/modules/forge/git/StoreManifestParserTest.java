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
            + "artifact: https://example.com/releases/{version}/my-module-{version}.jar\n"
            + "license: Apache-2.0\n"
            + "icon: docs/icon.png\n"
            + "requiredVersion: 8.2.0.0\n"
            + "dependencies:\n"
            + "  - default\n"
            + "  - search\n"
            + "---\n"
            + "\n"
            + "# My Module\n"
            + "\n"
            + "It does *things*.\n";

    @Test
    @DisplayName("parses the store fields and renders the body to HTML")
    void parsesFieldsAndBody() {
        StoreManifest manifest = StoreManifestParser.parse(VALID);

        assertThat(manifest.getName()).isEqualTo("my-module");
        assertThat(manifest.getGroupId()).isEqualTo("org.example.modules");
        assertThat(manifest.getTitle()).isEqualTo("My Module");
        assertThat(manifest.artifactUrl("1.2.0")).isEqualTo("https://example.com/releases/1.2.0/my-module-1.2.0.jar");
        assertThat(manifest.getLicense()).isEqualTo("Apache-2.0");
        assertThat(manifest.getIcon()).isEqualTo("docs/icon.png");
        assertThat(manifest.getRequiredVersion()).isEqualTo("8.2.0.0");
        assertThat(manifest.getDependencies()).containsExactly("default", "search");
        assertThat(manifest.getDescriptionHtml()).contains("<h1>My Module</h1>").contains("<em>things</em>");
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
    @DisplayName("the artifact must be an absolute http or https URL")
    void artifactMustBeHttp() {
        String ftp = VALID.replace("https://example.com", "ftp://example.com");
        String relative = VALID.replace("https://example.com/releases/{version}/my-module-{version}.jar", "releases/{version}.jar");

        assertThatThrownBy(() -> StoreManifestParser.parse(ftp)).hasMessageContaining("'artifact'");
        assertThatThrownBy(() -> StoreManifestParser.parse(relative)).hasMessageContaining("'artifact'");
    }

    @Test
    @DisplayName("a name that is not a Maven coordinate is refused")
    void nameMustBeCoordinate() {
        String traversal = VALID.replace("name: my-module", "name: ../etc");

        assertThatThrownBy(() -> StoreManifestParser.parse(traversal)).hasMessageContaining("'name'");
    }

    @Test
    @DisplayName("an icon path that leaves the repository is refused")
    void iconStaysInRepository() {
        String outside = VALID.replace("icon: docs/icon.png", "icon: ../secret.png");
        String absolute = VALID.replace("icon: docs/icon.png", "icon: /etc/passwd");

        assertThatThrownBy(() -> StoreManifestParser.parse(outside)).hasMessageContaining("'icon'");
        assertThatThrownBy(() -> StoreManifestParser.parse(absolute)).hasMessageContaining("'icon'");
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
        String withScript = VALID + "\n<script>alert(1)</script>\n";

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
