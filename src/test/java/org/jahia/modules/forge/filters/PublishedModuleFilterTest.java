package org.jahia.modules.forge.filters;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S20 / U15 / F13 — the read-gate that keeps an UNPUBLISHED forge element (module OR package) from
 * being rendered to an anonymous visitor at its predictable detail URL (SECURITY-571 #54). A caller
 * without {@code jcr:write} on a {@code published=false} node is redirected to the site home; an
 * author (has {@code jcr:write}) can preview a draft; a published node is served to anyone.
 *
 * <p>NOTE: this pins the filter LOGIC. Whether the filter actually REGISTERS (and therefore runs)
 * is a separate packaging concern — see {@code FiltersPackageRegistrationTest} and Cypress
 * {@code 30-publishedFilterRuns.cy.ts} (the {@code filters.*} / {@code _dsannotations} bug).
 */
class PublishedModuleFilterTest {

    private static Resource resourceWith(boolean published, boolean canWrite) throws Exception {
        JCRPropertyWrapper publishedProp = mock(JCRPropertyWrapper.class);
        when(publishedProp.getBoolean()).thenReturn(published);
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getProperty("published")).thenReturn(publishedProp);
        when(node.hasPermission("jcr:write")).thenReturn(canWrite);
        Resource resource = mock(Resource.class);
        when(resource.getNode()).thenReturn(node);
        return resource;
    }

    private static RenderContext renderContextCapturingRedirect(HttpServletResponse response) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContextPath()).thenReturn("/ctx");
        RenderContext rc = mock(RenderContext.class);
        when(rc.getResponse()).thenReturn(response);
        when(rc.getRequest()).thenReturn(request);
        return rc;
    }

    @Test
    @DisplayName("draft + no jcr:write -> redirect to site home (draft not anonymously readable)")
    void draftWithoutWrite_redirectsHome() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.encodeRedirectURL(anyString())).thenAnswer(inv -> inv.getArgument(0));
        RenderContext rc = renderContextCapturingRedirect(response);

        String result = new PublishedModuleFilter().prepare(rc, resourceWith(false, false), null);

        verify(response, times(1)).sendRedirect("/ctx/");
        org.assertj.core.api.Assertions.assertThat(result).isNull();
    }

    @Test
    @DisplayName("draft + jcr:write (owner/author) -> no redirect, preview allowed")
    void draftWithWrite_noRedirect() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        RenderContext rc = renderContextCapturingRedirect(response);

        new PublishedModuleFilter().prepare(rc, resourceWith(false, true), null);

        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    @DisplayName("published node -> served to anonymous (no redirect)")
    void published_noRedirect() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        RenderContext rc = renderContextCapturingRedirect(response);

        new PublishedModuleFilter().prepare(rc, resourceWith(true, false), null);

        verify(response, never()).sendRedirect(anyString());
    }
}
