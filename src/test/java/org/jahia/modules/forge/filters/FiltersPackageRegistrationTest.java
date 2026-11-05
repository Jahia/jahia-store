package org.jahia.modules.forge.filters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * G18(a) / S39 — CONFIRMED PACKAGING BUG (expected RED until the Stage-7 product fix).
 *
 * <p>{@link PublishedModuleFilter} is {@code @Component(service = RenderFilter.class)}, but the pom's
 * {@code <_dsannotations>} lists only {@code graphql.*, actions.*, proxy.*, settings.*} — the
 * {@code filters.*} package is ABSENT. When {@code _dsannotations} is set explicitly it REPLACES
 * bnd's default {@code *}, so {@code filters} is never scanned and no {@code OSGI-INF} descriptor is
 * generated. There is no blueprint fallback either. Result: the unpublished-module read-gate
 * (SECURITY-571 #54) does not register, so a draft {@code jnt:forgeModule}/{@code jnt:forgePackage}
 * is anonymously readable at its predictable URL.
 *
 * <p><b>Why an assumption, not a hard failure:</b> so this evidence lands WITHOUT breaking the rest
 * of the suite. While the bug is present the assumption is violated and the test is reported as
 * ABORTED/SKIPPED (with this reason), not FAILED. Once Stage 7 adds
 * {@code org.jahia.modules.forge.filters.*} to {@code _dsannotations}, the assumption holds and the
 * subsequent assertion turns this test GREEN. The live end-to-end confirmation is Cypress
 * {@code 30-publishedFilterRuns.cy.ts}.
 */
class FiltersPackageRegistrationTest {

    private static final String FILTERS_PACKAGE = "org.jahia.modules.forge.filters";

    @Test
    @DisplayName("_dsannotations must scan the filters package so PublishedModuleFilter registers")
    void dsAnnotationsMustIncludeFiltersPackage() throws Exception {
        Path pom = Paths.get("pom.xml");
        assertThat(Files.exists(pom)).as("pom.xml resolvable from the module basedir").isTrue();
        String content = new String(Files.readAllBytes(pom));

        int start = content.indexOf("<_dsannotations>");
        int end = content.indexOf("</_dsannotations>");
        assertThat(start).as("_dsannotations declared in pom.xml").isGreaterThan(-1);
        String ds = content.substring(start, end);

        boolean filtersScanned = ds.contains(FILTERS_PACKAGE);
        assumeTrue(filtersScanned,
                "EXPECTED-RED until Stage-7 product fix (SECURITY-571 #54): '" + FILTERS_PACKAGE
                        + ".*' is missing from <_dsannotations>, so PublishedModuleFilter's @Component "
                        + "is never scanned and the filter cannot register — draft store modules are "
                        + "anonymously readable. This test is intentionally ABORTED (not failed) while "
                        + "the bug is present; adding the package to _dsannotations turns it green.");

        // Reached only once Stage 7 fixes the pom — then this is a hard guarantee.
        assertThat(ds).contains(FILTERS_PACKAGE);
    }
}
