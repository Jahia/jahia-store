package org.jahia.modules.forge.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pom's {@code <_dsannotations>} is an allow-list: a package left out is never scanned and its
 * {@code @Component}s never register. Without {@code security.*}, {@link AdvisoriesLock} and its listeners
 * would silently not run, and store developers could write security advisories again.
 */
class SecurityPackageRegistrationTest {

    @Test
    @DisplayName("_dsannotations scans the security package so the advisories lock registers")
    void dsAnnotationsIncludeSecurityPackage() throws Exception {
        String pom = new String(Files.readAllBytes(Paths.get("pom.xml")));
        int start = pom.indexOf("<_dsannotations>");
        int end = pom.indexOf("</_dsannotations>");

        assertThat(start).isGreaterThan(-1);
        assertThat(pom.substring(start, end)).contains("org.jahia.modules.forge.security.*");
    }
}
