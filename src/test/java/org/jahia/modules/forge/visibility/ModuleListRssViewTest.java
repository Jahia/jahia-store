package org.jahia.modules.forge.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSTORE-SEC-005 (S1, corpus SEC-1015): the module-list RSS view
 * ({@code rss/contentFolder.moduleList.jsp}, served anonymously at {@code <site>/feed.xml}) tested only
 * the VERSION's {@code published} flag, so the published versions of an UNPUBLISHED module were listed
 * with working download links. The view must apply the shared published-only rule
 * ({@link StoreVisibility#isPublished}: the version AND every store element above it are published),
 * the rule {@code moduleList.json} applies.
 *
 * <p>A JSP cannot run in a unit test, so this pins the view's source; the live proof is Cypress
 * {@code 34-anonymousDraftDisclosure.cy.ts}.
 */
class ModuleListRssViewTest {

    private static final Path RSS_VIEW = Paths.get("src/main/resources/jnt_contentFolder/rss/contentFolder.moduleList.jsp");

    private static String view() throws Exception {
        assertThat(Files.exists(RSS_VIEW)).as("RSS view resolvable from the module basedir").isTrue();
        return new String(Files.readAllBytes(RSS_VIEW), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the RSS view lists a version only through the shared published-only rule")
    void rssViewUsesTheSharedPublishedRule() throws Exception {
        String view = view();

        assertThat(view).contains("org.jahia.modules.forge.visibility.StoreVisibility");
        assertThat(view).contains("StoreVisibility.isPublished(");
    }

    @Test
    @DisplayName("the RSS view no longer gates an item on the version flag alone")
    void rssViewDoesNotGateOnTheVersionFlagAlone() throws Exception {
        assertThat(view()).doesNotContain("<c:if test=\"${version.properties.published.boolean}\">");
    }
}
