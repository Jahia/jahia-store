package org.jahia.modules.forge.filters;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.PathNotFoundException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S20 / U15 / F13 — the read-gate that keeps an UNPUBLISHED forge element (module OR package) from
 * being rendered to an anonymous visitor at its predictable detail URL (SECURITY-571 #54). A caller
 * without {@code jcr:write} on a {@code published=false} node gets the same answer as for a node that
 * does not exist (404); an author (has {@code jcr:write}) can preview a draft; a published node is
 * served to anyone.
 *
 * <p>JSTORE-SEC-005 (S3): the filter used to redirect a draft to the site home, while a missing node
 * answers 404, so the redirect told an anonymous visitor that a draft existed at that URL. The draft
 * now raises {@link PathNotFoundException}, which Jahia turns into the same 404 as a missing node.
 *
 * <p>NOTE: this pins the filter LOGIC. Whether the filter actually REGISTERS (and therefore runs)
 * is a separate packaging concern — see {@code FiltersPackageRegistrationTest} and Cypress
 * {@code 30-publishedFilterRuns.cy.ts} (the {@code filters.*} / {@code _dsannotations} bug).
 */
class PublishedModuleFilterTest {

    private static final String DRAFT_PATH = "/sites/store/contents/modules-repository/draft";

    private static Resource resourceWith(boolean published, boolean canWrite) throws Exception {
        JCRNodeWrapper site = mock(JCRNodeWrapper.class);
        when(site.isNodeType("jnt:virtualsite")).thenReturn(true);
        when(site.getPath()).thenReturn("/sites/store");

        JCRPropertyWrapper publishedProp = mock(JCRPropertyWrapper.class);
        when(publishedProp.getBoolean()).thenReturn(published);
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.isNodeType("jmix:forgeElement")).thenReturn(true);
        when(node.hasProperty("published")).thenReturn(true);
        when(node.getProperty("published")).thenReturn(publishedProp);
        when(node.hasPermission("jcr:write")).thenReturn(canWrite);
        when(node.getPath()).thenReturn(DRAFT_PATH);
        when(node.getParent()).thenReturn(site);
        Resource resource = mock(Resource.class);
        when(resource.getNode()).thenReturn(node);
        return resource;
    }

    private static RenderContext renderContext(HttpServletResponse response) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContextPath()).thenReturn("/ctx");
        RenderContext rc = mock(RenderContext.class);
        when(rc.getResponse()).thenReturn(response);
        when(rc.getRequest()).thenReturn(request);
        return rc;
    }

    @Test
    @DisplayName("draft + no jcr:write -> 404 like a missing node, no redirect (no existence oracle, JSTORE-SEC-005 S3)")
    void draftWithoutWrite_answersNotFoundWithoutRedirect() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.encodeRedirectURL(anyString())).thenAnswer(inv -> inv.getArgument(0));
        RenderContext rc = renderContext(response);
        Resource draft = resourceWith(false, false);

        assertThatThrownBy(() -> new PublishedModuleFilter().prepare(rc, draft, null))
                .isInstanceOf(PathNotFoundException.class);

        verify(response, never()).sendRedirect(anyString());
        verify(response, never()).sendError(anyInt());
    }

    @Test
    @DisplayName("draft + jcr:write (owner/author) -> rendered, preview allowed")
    void draftWithWrite_rendered() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        RenderContext rc = renderContext(response);

        String result = new PublishedModuleFilter().prepare(rc, resourceWith(false, true), null);

        assertThat(result).isNull();
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    @DisplayName("published node -> rendered for anonymous")
    void published_rendered() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        RenderContext rc = renderContext(response);

        String result = new PublishedModuleFilter().prepare(rc, resourceWith(true, false), null);

        assertThat(result).isNull();
        verify(response, never()).sendRedirect(anyString());
    }
}
