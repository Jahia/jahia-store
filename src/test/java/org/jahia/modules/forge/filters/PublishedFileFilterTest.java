package org.jahia.modules.forge.filters;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JSTORE-SEC-005 (S2): the artifact of a draft version ({@code jnt:file} under a
 * {@code jnt:forgeModuleVersion}) was downloadable by anyone at its predictable {@code /files} URL,
 * because the draft gate lived only in the render chain and the file servlet does not run it. The
 * {@code /files} filter applies the shared rule ({@code StoreVisibility.isVisibleToCaller}) to the node
 * the file servlet would serve, in the caller's own session: a draft answers 404, like a missing file;
 * everything else goes on to the file servlet unchanged.
 */
class PublishedFileFilterTest {

    private static final String DRAFT_FILE = "/sites/store/contents/modules-repository/m/m-1.0/m-1.0.jar";

    private JCRSessionFactory factory;
    private JCRSessionWrapper liveSession;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        factory = mock(JCRSessionFactory.class);
        liveSession = mock(JCRSessionWrapper.class);
        when(factory.getCurrentUserSession("live")).thenReturn(liveSession);
        when(liveSession.getNode(anyString())).thenThrow(new PathNotFoundException());
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    private static HttpServletRequest filesRequest(String pathInfo) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContextPath()).thenReturn("");
        when(request.getServletPath()).thenReturn("/files");
        when(request.getPathInfo()).thenReturn(pathInfo);
        when(request.getRequestURI()).thenReturn("/files" + pathInfo);
        return request;
    }

    private void filter(HttpServletRequest request) throws Exception {
        try (MockedStatic<JCRSessionFactory> f = mockStatic(JCRSessionFactory.class)) {
            f.when(JCRSessionFactory::getInstance).thenReturn(factory);
            new PublishedFileFilter().doFilter(request, response, chain);
        }
    }

    /** A jnt:file under a version of a module, each with its own published flag, readable by the caller. */
    private JCRNodeWrapper artifact(JCRSessionWrapper session, boolean modulePublished, boolean versionPublished,
                                    boolean canWrite) throws Exception {
        JCRNodeWrapper site = mock(JCRNodeWrapper.class);
        when(site.isNodeType("jnt:virtualsite")).thenReturn(true);
        JCRNodeWrapper module = flagged("jmix:forgeElement", site, modulePublished, canWrite);
        JCRNodeWrapper version = flagged("jnt:forgeModuleVersion", module, versionPublished, canWrite);
        JCRNodeWrapper file = mock(JCRNodeWrapper.class);
        when(file.isNodeType("jnt:file")).thenReturn(true);
        when(file.getParent()).thenReturn(version);
        when(file.getPath()).thenReturn(DRAFT_FILE);
        org.mockito.Mockito.doReturn(file).when(session).getNode(DRAFT_FILE);
        return file;
    }

    private static JCRNodeWrapper flagged(String type, JCRNodeWrapper parent, boolean published, boolean canWrite)
            throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.isNodeType(type)).thenReturn(true);
        when(node.getParent()).thenReturn(parent);
        when(node.hasPermission("jcr:write")).thenReturn(canWrite);
        JCRPropertyWrapper flag = mock(JCRPropertyWrapper.class);
        when(flag.getBoolean()).thenReturn(published);
        when(node.hasProperty("published")).thenReturn(true);
        when(node.getProperty("published")).thenReturn(flag);
        return node;
    }

    private void assertNotFound() throws Exception {
        verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void assertPassedOn(HttpServletRequest request) throws Exception {
        verify(chain).doFilter(request, response);
        verify(response, never()).sendError(anyInt());
    }

    @Test
    @DisplayName("the filter is mapped on /files/* for direct requests and forwards")
    void mappedOnFiles() {
        PublishedFileFilter filter = new PublishedFileFilter();
        filter.activate();

        assertThat(filter.getUrlPatterns()).containsExactly("/files/*");
        assertThat(filter.getDispatcherTypes()).containsExactlyInAnyOrder("REQUEST", "FORWARD");
    }

    @Test
    @DisplayName("the artifact of a DRAFT version is 404 for a caller who cannot write it")
    void draftVersionArtifact_notFound() throws Exception {
        artifact(liveSession, true, false, false);

        filter(filesRequest("/live" + DRAFT_FILE));

        assertNotFound();
    }

    @Test
    @DisplayName("the artifact of a published version of a DRAFT module is 404 too")
    void versionOfDraftModuleArtifact_notFound() throws Exception {
        artifact(liveSession, false, true, false);

        filter(filesRequest("/live" + DRAFT_FILE));

        assertNotFound();
    }

    @Test
    @DisplayName("a released artifact (published module and version) goes on to the file servlet")
    void releasedArtifact_passedOn() throws Exception {
        artifact(liveSession, true, true, false);
        HttpServletRequest request = filesRequest("/live" + DRAFT_FILE);

        filter(request);

        assertPassedOn(request);
    }

    @Test
    @DisplayName("the owner (jcr:write on the drafts) still downloads the draft artifact")
    void draftArtifact_ownerPassedOn() throws Exception {
        artifact(liveSession, false, false, true);
        HttpServletRequest request = filesRequest("/live" + DRAFT_FILE);

        filter(request);

        assertPassedOn(request);
    }

    @Test
    @DisplayName("a missing or unreadable node goes on to the file servlet, which answers as it always did")
    void missingNode_passedOn() throws Exception {
        HttpServletRequest request = filesRequest("/live/sites/store/files/nothing.png");

        filter(request);

        assertPassedOn(request);
    }

    @Test
    @DisplayName("the {workspace} placeholder resolves to the edit workspace, as in the file servlet")
    void workspacePlaceholder_checksTheEditWorkspace() throws Exception {
        JCRSessionWrapper editSession = mock(JCRSessionWrapper.class);
        when(factory.getCurrentUserSession("default")).thenReturn(editSession);
        artifact(editSession, true, false, false);

        filter(filesRequest("/{workspace}" + DRAFT_FILE));

        assertNotFound();
    }

    @Test
    @DisplayName("an unknown workspace or a path without one goes on to the file servlet")
    void unknownWorkspace_passedOn() throws Exception {
        HttpServletRequest unknown = filesRequest("/other" + DRAFT_FILE);
        HttpServletRequest bare = filesRequest("/");

        filter(unknown);
        filter(bare);

        verify(chain).doFilter(unknown, response);
        verify(chain).doFilter(bare, response);
        verify(factory, never()).getCurrentUserSession("other");
    }

    @Test
    @DisplayName("when the servlet path is not /files, the file path is taken from the decoded request URI")
    void pathFromRequestUri() throws Exception {
        artifact(liveSession, true, false, false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContextPath()).thenReturn("/ctx");
        when(request.getServletPath()).thenReturn("");
        when(request.getRequestURI()).thenReturn("/ctx/files/live" + DRAFT_FILE.replace("m-1.0.jar", "m-1%2E0.jar"));

        filter(request);

        assertNotFound();
    }

    @Test
    @DisplayName("a repository error while resolving the node goes on to the file servlet (it fails the same way)")
    void repositoryError_passedOn() throws Exception {
        org.mockito.Mockito.doThrow(new RepositoryException("boom")).when(liveSession).getNode(DRAFT_FILE);
        HttpServletRequest request = filesRequest("/live" + DRAFT_FILE);

        filter(request);

        assertPassedOn(request);
    }
}
