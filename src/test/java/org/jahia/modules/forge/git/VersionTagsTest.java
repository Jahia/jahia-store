package org.jahia.modules.forge.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionTagsTest {

    @Test
    @DisplayName("the default pattern accepts 1.2.3 and v1.2.3 and refuses a pre-release")
    void defaultPattern() {
        Pattern pattern = VersionTags.compile(null);

        assertThat(VersionTags.isVersion(pattern, "1.2.3")).isTrue();
        assertThat(VersionTags.isVersion(pattern, "v1.2.3")).isTrue();
        assertThat(VersionTags.isVersion(pattern, "1.2.3-rc1")).isFalse();
        assertThat(VersionTags.isVersion(pattern, "1.2.3-SNAPSHOT")).isFalse();
        assertThat(VersionTags.isVersion(pattern, "release")).isFalse();
    }

    @Test
    @DisplayName("a registration names its own pattern, and a broken one is refused")
    void customPattern() {
        Pattern pattern = VersionTags.compile("^release-(\\d+)$");

        assertThat(VersionTags.isVersion(pattern, "release-42")).isTrue();
        assertThat(VersionTags.isVersion(pattern, "1.2.3")).isFalse();
        assertThatThrownBy(() -> VersionTags.compile("(")).isInstanceOf(StoreManifestException.class);
    }

    @Test
    @DisplayName("the version number is the tag without its v prefix")
    void versionOf() {
        assertThat(VersionTags.versionOf("v1.2.3")).isEqualTo("1.2.3");
        assertThat(VersionTags.versionOf("1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("newest first orders on numbers, so 1.10.0 is newer than 1.9.0")
    void newestFirst() {
        List<String> versions = Arrays.asList("1.9.0", "1.10.0", "2.0.0", "1.9.1");
        versions.sort(VersionTags.newestFirst());

        assertThat(versions).containsExactly("2.0.0", "1.10.0", "1.9.1", "1.9.0");
    }
}
