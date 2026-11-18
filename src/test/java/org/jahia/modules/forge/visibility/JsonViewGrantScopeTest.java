package org.jahia.modules.forge.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSTORE-SEC-005 (S5): the API authorization profile shipped by the module grants the anonymous JSON
 * views ({@code view.json.moduleList}, {@code view.json.securityUpdates}). Scoped by node type only, it
 * opened them on EVERY {@code jnt:contentFolder} of the repository. Each grant must carry a
 * {@code pathPattern} that matches a site's {@code modules-repository} folder and nothing else, the
 * only place the feeds are served (Jahia's security filter matches the pattern against the whole node
 * path, {@code Matcher.matches()}).
 *
 * <p>The file is read as text, like {@code FiltersPackageRegistrationTest} reads the pom: the grant
 * syntax is flat (one {@code - api:} line per grant), and the security filter splits a pattern on
 * commas and spaces, so the pattern must contain neither.
 */
class JsonViewGrantScopeTest {

    private static final Path PROFILE = Paths.get(
            "src/main/resources/META-INF/configurations/org.jahia.bundles.api.authorization-jahia-store.yaml");

    private static List<String> lines() throws Exception {
        assertThat(Files.exists(PROFILE)).as("authorization profile resolvable from the module basedir").isTrue();
        return Files.readAllLines(PROFILE, StandardCharsets.UTF_8);
    }

    private static List<String> values(List<String> lines, String key) {
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("- " + key + ":") || trimmed.startsWith(key + ":")) {
                values.add(trimmed.substring(trimmed.indexOf(':') + 1).trim());
            }
        }
        return values;
    }

    @Test
    @DisplayName("every anonymous JSON-view grant carries a pathPattern")
    void everyGrantIsPathScoped() throws Exception {
        List<String> lines = lines();

        List<String> apis = values(lines, "api");
        List<String> patterns = values(lines, "pathPattern");

        assertThat(apis).containsExactlyInAnyOrder("view.json.moduleList", "view.json.securityUpdates");
        assertThat(patterns).as("one pathPattern per grant").hasSameSizeAs(apis);
    }

    @Test
    @DisplayName("the pathPattern matches a site's modules-repository folder and nothing else")
    void pathPatternMatchesOnlyTheModulesRepository() throws Exception {
        for (String raw : values(lines(), "pathPattern")) {
            String regex = raw.replaceAll("^['\"]|['\"]$", "");
            assertThat(regex).as("the security filter splits patterns on ',' and ' '").doesNotContain(",", " ");
            Pattern pattern = Pattern.compile(regex);

            assertThat(pattern.matcher("/sites/store/contents/modules-repository").matches()).isTrue();
            assertThat(pattern.matcher("/sites/other-store/contents/modules-repository").matches()).isTrue();

            assertThat(pattern.matcher("/sites/store/contents").matches()).isFalse();
            assertThat(pattern.matcher("/sites/store/contents/security-advisories").matches()).isFalse();
            assertThat(pattern.matcher("/sites/store/contents/modules-repository/org").matches()).isFalse();
            assertThat(pattern.matcher("/sites/store/contents/x/modules-repository").matches()).isFalse();
            assertThat(pattern.matcher("/sites/store/files/modules-repository").matches()).isFalse();
            assertThat(pattern.matcher("/sites/a/b/contents/modules-repository").matches()).isFalse();
        }
    }
}
