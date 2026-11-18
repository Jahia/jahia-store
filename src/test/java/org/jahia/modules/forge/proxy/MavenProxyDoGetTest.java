package org.jahia.modules.forge.proxy;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.jahia.modules.forge.settings.ForgeSettings;
import org.jahia.modules.forge.settings.ForgeSettingsService;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRWorkspaceWrapper;
import org.jahia.services.content.QueryManagerWrapper;
import org.jahia.services.query.QueryResultWrapper;
import org.jahia.services.query.QueryWrapper;
import org.jahia.services.notification.HttpClientService;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import javax.jcr.query.Query;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1–S5, S7 — the {@code MavenProxy.doGet} flow. These pin the ORCHESTRATION (the validator-only
 * guards are covered by {@link MavenProxyValidatorTest}):
 * <ul>
 *   <li>S1/S2 — a suspicious path or invalid site name is rejected with 400 BEFORE any egress
 *       (no {@code getHttpClient}, no {@code forgeSettingsService.get}).</li>
 *   <li>S3 — a caller who cannot read the site module repository gets 403 with no egress; the
 *       anonymous (null-user) branch does not NPE.</li>
 *   <li>S4 — an authorized request egresses to a URL PINNED under the admin-configured repository
 *       root (SSRF containment).</li>
 *   <li>S5 — a blank configured repository URL is refused with 400 before egress.</li>
 *   <li>JSTORE-SEC-005 (S2) — an artifact of a catalogued module or version that is still a draft
 *       answers 404 with no egress, unless the caller can write the draft (owner preview); an
 *       artifact that the catalogue does not list (raw Maven coordinates) is still served.</li>
 *   <li>S7 — a hostile upstream Content-Type still gets attachment + nosniff response headers;
 *       credentials ride the OUTGOING request only, never the response; upstream non-200 maps to
 *       sendError with no body.</li>
 * </ul>
 * {@code doGet} is {@code protected} and this test is in the same package, so no visibility change
 * is needed. {@code JCRSessionFactory} is statically mocked (mockito-inline).
 */
class MavenProxyDoGetTest {

    private static final String ROOT = "https://nexus.internal/repository/store/";
    private static final String REPO_NODE = "/sites/store/contents/modules-repository";

    /** A ServletOutputStream over an in-memory buffer so IOUtils.copy has a real sink. */
    private static ServletOutputStream servletOutputStream(final ByteArrayOutputStream sink) {
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // no-op
            }

            @Override
            public void write(int b) {
                sink.write(b);
            }
        };
    }

    /** Build a MavenProxy with mocked collaborators and a statically-mocked session factory. */
    private static MavenProxy proxyWithSession(MockedStatic<JCRSessionFactory> factoryStatic,
                                               HttpClientService http,
                                               ForgeSettingsService settings,
                                               JCRSessionWrapper session) throws Exception {
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(factory);
        when(factory.getCurrentUserSession("live")).thenReturn(session);
        return new MavenProxy(http, settings);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/store/../../etc/passwd",
            "/store/%2e%2e/x",
            "/store/%2E%2E/x",
            "/store/x%2fy",
            "/store/x%5cy",
            "/store/a\\b",
            "/store/a\nb",
            "/store/http://169.254.169.254/x"
    })
    @DisplayName("S1: a suspicious path is rejected with 400 before any egress or credential build")
    void suspiciousPath_rejectedBeforeEgress(String pathInfo) throws Exception {
        HttpClientService http = mock(HttpClientService.class);
        ForgeSettingsService settings = mock(ForgeSettingsService.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getPathInfo()).thenReturn(pathInfo);

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            MavenProxy proxy = proxyWithSession(f, http, settings, session);
            proxy.doGet(request, response);
        }

        verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST);
        verify(http, never()).getHttpClient(anyString());
        verify(settings, never()).get(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/..evil/name/1/n-1.jar",
            "/foo bar/x",
            "/site:evil/x",
            "/foo..bar/x"
    })
    @DisplayName("S2: an invalid site name is rejected with 400 before any egress")
    void invalidSiteName_rejectedBeforeEgress(String pathInfo) throws Exception {
        HttpClientService http = mock(HttpClientService.class);
        ForgeSettingsService settings = mock(ForgeSettingsService.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getPathInfo()).thenReturn(pathInfo);

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            MavenProxy proxy = proxyWithSession(f, http, settings, session);
            proxy.doGet(request, response);
        }

        verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST);
        verify(http, never()).getHttpClient(anyString());
        verify(settings, never()).get(anyString());
    }

    @Test
    @DisplayName("S3: a caller who cannot read the site repository gets 403 with no egress")
    void unauthorizedCaller_forbiddenNoEgress() throws Exception {
        HttpClientService http = mock(HttpClientService.class);
        ForgeSettingsService settings = mock(ForgeSettingsService.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getPathInfo()).thenReturn("/store/org/jahia/m/1.0/m-1.0.jar");
        when(session.nodeExists(REPO_NODE)).thenReturn(false);
        JahiaUser user = mock(JahiaUser.class);
        when(user.getName()).thenReturn("bob");
        when(session.getUser()).thenReturn(user);

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            MavenProxy proxy = proxyWithSession(f, http, settings, session);
            proxy.doGet(request, response);
        }

        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN);
        verify(http, never()).getHttpClient(anyString());
        verify(settings, never()).get(anyString());
    }

    @Test
    @DisplayName("S3: the anonymous (null user) denied branch does not NPE and returns 403")
    void anonymousDenied_noNpe() throws Exception {
        HttpClientService http = mock(HttpClientService.class);
        ForgeSettingsService settings = mock(ForgeSettingsService.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getPathInfo()).thenReturn("/store/org/jahia/m/1.0/m-1.0.jar");
        when(session.nodeExists(REPO_NODE)).thenReturn(false);
        when(session.getUser()).thenReturn(null); // anonymous

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            MavenProxy proxy = proxyWithSession(f, http, settings, session);
            proxy.doGet(request, response);
        }

        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN);
        verify(http, never()).getHttpClient(anyString());
    }

    @Test
    @DisplayName("S4/S7: authorized request egresses pinned under the admin root, forwards creds on the request only, sets nosniff/attachment")
    void authorized_egressPinnedUnderRoot_withDownloadHeaders() throws Exception {
        HttpClientService http = mock(HttpClientService.class);
        ForgeSettingsService settings = mock(ForgeSettingsService.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getPathInfo()).thenReturn("/store/org/acme/m/1.0/m-1.0.jar");
        when(session.nodeExists(REPO_NODE)).thenReturn(true);
        catalogue(session); // a raw coordinate: no catalogued module
        when(settings.get("store")).thenReturn(ForgeSettings.builder()
                .url(ROOT).user("svc").password("pw").build());

        CloseableHttpClient client = mock(CloseableHttpClient.class);
        CloseableHttpResponse upstream = mock(CloseableHttpResponse.class);
        HttpEntity entity = mock(HttpEntity.class);
        when(http.getHttpClient(anyString())).thenReturn(client);
        when(client.execute(any(HttpGet.class))).thenReturn(upstream);
        when(upstream.getCode()).thenReturn(200);
        when(upstream.getEntity()).thenReturn(entity);
        when(entity.getContentType()).thenReturn("text/html"); // hostile upstream mime
        when(entity.getContent()).thenReturn(new ByteArrayInputStream("JAR-BYTES".getBytes()));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(servletOutputStream(sink));

        ArgumentCaptor<HttpGet> getCaptor = ArgumentCaptor.forClass(HttpGet.class);

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            MavenProxy proxy = proxyWithSession(f, http, settings, session);
            proxy.doGet(request, response);
        }

        verify(client).execute(getCaptor.capture());
        HttpGet issued = getCaptor.getValue();
        // SSRF containment: the egress URI is pinned under the admin-configured root.
        assertThat(issued.getUri().toString())
                .startsWith(ROOT)
                .isEqualTo(ROOT + "org/acme/m/1.0/m-1.0.jar");
        // Credentials ride the OUTGOING request only.
        Header auth = issued.getFirstHeader("Authorization");
        assertThat(auth).isNotNull();
        assertThat(auth.getValue()).startsWith("Basic ");
        // Stored-XSS mitigation headers on the response; body streamed through.
        verify(response).setHeader("Content-Disposition", "attachment");
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
        verify(response, never()).setHeader(eq("Authorization"), anyString());
        assertThat(sink.toString()).isEqualTo("JAR-BYTES");
    }

    @Test
    @DisplayName("S7: an upstream non-200 is surfaced as sendError with no body copied")
    void upstreamNon200_sendErrorNoBody() throws Exception {
        HttpClientService http = mock(HttpClientService.class);
        ForgeSettingsService settings = mock(ForgeSettingsService.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getPathInfo()).thenReturn("/store/org/acme/m/1.0/m-1.0.jar");
        when(session.nodeExists(REPO_NODE)).thenReturn(true);
        catalogue(session); // a raw coordinate: no catalogued module
        when(settings.get("store")).thenReturn(ForgeSettings.builder()
                .url(ROOT).user("svc").password("pw").build());

        CloseableHttpClient client = mock(CloseableHttpClient.class);
        CloseableHttpResponse upstream = mock(CloseableHttpResponse.class);
        when(http.getHttpClient(anyString())).thenReturn(client);
        when(client.execute(any(HttpGet.class))).thenReturn(upstream);
        when(upstream.getCode()).thenReturn(404);

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            MavenProxy proxy = proxyWithSession(f, http, settings, session);
            proxy.doGet(request, response);
        }

        verify(response).sendError(404);
        verify(response, never()).getOutputStream();
    }

    @Test
    @DisplayName("S5: a blank configured repository URL is refused with 400 before egress")
    void blankRepositoryUrl_rejectedBeforeEgress() throws Exception {
        HttpClientService http = mock(HttpClientService.class);
        ForgeSettingsService settings = mock(ForgeSettingsService.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getPathInfo()).thenReturn("/store/org/acme/m/1.0/m-1.0.jar");
        when(session.nodeExists(REPO_NODE)).thenReturn(true);
        catalogue(session); // a raw coordinate: no catalogued module
        when(settings.get("store")).thenReturn(ForgeSettings.empty()); // url == null/blank

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            MavenProxy proxy = proxyWithSession(f, http, settings, session);
            proxy.doGet(request, response);
        }

        verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST);
        verify(http, never()).getHttpClient(anyString());
        verify(settings, times(1)).get("store");
    }

    // ---------------------------------------------------------------- JSTORE-SEC-005 (S2): drafts

    private static final String DRAFT_JAR = "/store/org/acme/m/1.0/m-1.0.jar";

    /** Runs doGet for {@code pathInfo} with a readable repository and the given catalogue; yields the egress mock. */
    private static HttpClientService getThroughProxy(String pathInfo, HttpServletResponse response,
                                                     JCRNodeWrapper... catalogued) throws Exception {
        HttpClientService http = mock(HttpClientService.class);
        ForgeSettingsService settings = mock(ForgeSettingsService.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn(pathInfo);
        when(session.nodeExists(REPO_NODE)).thenReturn(true);
        catalogue(session, catalogued);
        when(settings.get("store")).thenReturn(ForgeSettings.builder().url(ROOT).user("svc").password("pw").build());

        CloseableHttpClient client = mock(CloseableHttpClient.class);
        CloseableHttpResponse upstream = mock(CloseableHttpResponse.class);
        HttpEntity entity = mock(HttpEntity.class);
        when(http.getHttpClient(anyString())).thenReturn(client);
        when(client.execute(any(HttpGet.class))).thenReturn(upstream);
        when(upstream.getCode()).thenReturn(200);
        when(upstream.getEntity()).thenReturn(entity);
        when(entity.getContentType()).thenReturn("application/java-archive");
        when(entity.getContent()).thenReturn(new ByteArrayInputStream("JAR-BYTES".getBytes()));
        when(response.getOutputStream()).thenReturn(servletOutputStream(new ByteArrayOutputStream()));

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            proxyWithSession(f, http, settings, session).doGet(request, response);
        }
        return http;
    }

    private static void assertNotFoundWithoutEgress(HttpServletResponse response, HttpClientService http) throws Exception {
        verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
        verify(http, never()).getHttpClient(anyString());
    }

    private static void assertStreamed(HttpServletResponse response, HttpClientService http) throws Exception {
        verify(http).getHttpClient(anyString());
        verify(response, never()).sendError(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("JSTORE-SEC-005 S2: an artifact of a DRAFT module is 404 for a caller who cannot write it, with no egress")
    void draftModuleArtifact_notFoundNoEgress() throws Exception {
        JCRNodeWrapper module = module("m", "org.acme", false, false);
        version(module, "1.0", true, false);
        HttpServletResponse response = mock(HttpServletResponse.class);

        HttpClientService http = getThroughProxy(DRAFT_JAR, response, module);

        assertNotFoundWithoutEgress(response, http);
    }

    @Test
    @DisplayName("JSTORE-SEC-005 S2: the maven-metadata.xml of a DRAFT module is 404 too (it lists the draft's versions)")
    void draftModuleMetadata_notFoundNoEgress() throws Exception {
        JCRNodeWrapper module = module("m", "org.acme", false, false);
        HttpServletResponse response = mock(HttpServletResponse.class);

        HttpClientService http = getThroughProxy("/store/org/acme/m/maven-metadata.xml", response, module);

        assertNotFoundWithoutEgress(response, http);
    }

    @Test
    @DisplayName("JSTORE-SEC-005 S2: a DRAFT version of a published module is 404, with no egress")
    void draftVersionArtifact_notFoundNoEgress() throws Exception {
        JCRNodeWrapper module = module("m", "org.acme", true, false);
        version(module, "0.9", true, false);
        version(module, "1.0", false, false);
        HttpServletResponse response = mock(HttpServletResponse.class);

        HttpClientService http = getThroughProxy(DRAFT_JAR, response, module);

        assertNotFoundWithoutEgress(response, http);
    }

    @Test
    @DisplayName("JSTORE-SEC-005 S2: a published version of a published module is still streamed")
    void publishedVersionArtifact_streamed() throws Exception {
        JCRNodeWrapper module = module("m", "org.acme", true, false);
        version(module, "1.0", true, false);
        HttpServletResponse response = mock(HttpServletResponse.class);

        HttpClientService http = getThroughProxy(DRAFT_JAR, response, module);

        assertStreamed(response, http);
    }

    @Test
    @DisplayName("JSTORE-SEC-005 S2: the owner (jcr:write on the draft) still downloads the draft artifact")
    void draftArtifact_ownerStreamed() throws Exception {
        JCRNodeWrapper module = module("m", "org.acme", false, true);
        version(module, "1.0", false, true);
        HttpServletResponse response = mock(HttpServletResponse.class);

        HttpClientService http = getThroughProxy(DRAFT_JAR, response, module);

        assertStreamed(response, http);
    }

    @Test
    @DisplayName("JSTORE-SEC-005 S2: a module of another groupId with the same name does not hide the artifact")
    void sameNameOtherGroup_streamed() throws Exception {
        JCRNodeWrapper module = module("m", "org.other", false, false);
        HttpServletResponse response = mock(HttpServletResponse.class);

        HttpClientService http = getThroughProxy(DRAFT_JAR, response, module);

        assertStreamed(response, http);
    }

    /** Stubs the caller's catalogue query: the modules it finds under the site's modules-repository. */
    private static void catalogue(JCRSessionWrapper session, JCRNodeWrapper... modules) throws Exception {
        JCRWorkspaceWrapper workspace = mock(JCRWorkspaceWrapper.class);
        QueryManagerWrapper queryManager = mock(QueryManagerWrapper.class);
        QueryWrapper query = mock(QueryWrapper.class);
        QueryResultWrapper result = mock(QueryResultWrapper.class);
        when(session.getWorkspace()).thenReturn(workspace);
        when(workspace.getQueryManager()).thenReturn(queryManager);
        when(queryManager.createQuery(anyString(), eq(Query.JCR_SQL2))).thenReturn(query);
        when(query.execute()).thenReturn(result);
        List<JCRNodeWrapper> list = Arrays.asList(modules);
        when(result.getNodes()).thenAnswer(inv -> iterator(list));
    }

    private static JCRNodeWrapper site() throws Exception {
        JCRNodeWrapper site = mock(JCRNodeWrapper.class);
        when(site.isNodeType("jnt:virtualsite")).thenReturn(true);
        when(site.getPath()).thenReturn("/sites/store");
        return site;
    }

    private static JCRNodeWrapper module(String name, String groupId, boolean published, boolean canWrite) throws Exception {
        JCRNodeWrapper repository = mock(JCRNodeWrapper.class);
        when(repository.getPath()).thenReturn(REPO_NODE);
        JCRNodeWrapper site = site();
        when(repository.getParent()).thenReturn(site);
        JCRNodeWrapper module = mock(JCRNodeWrapper.class);
        when(module.getName()).thenReturn(name);
        when(module.getPath()).thenReturn(REPO_NODE + "/" + name);
        when(module.getParent()).thenReturn(repository);
        when(module.isNodeType("jnt:forgeModule")).thenReturn(true);
        when(module.isNodeType("jmix:forgeElement")).thenReturn(true);
        when(module.hasPermission("jcr:write")).thenReturn(canWrite);
        flag(module, published);
        JCRPropertyWrapper group = mock(JCRPropertyWrapper.class);
        when(group.getString()).thenReturn(groupId);
        when(module.hasProperty("groupId")).thenReturn(true);
        when(module.getProperty("groupId")).thenReturn(group);
        when(module.getPropertyAsString("groupId")).thenReturn(groupId);
        List<JCRNodeWrapper> versions = new ArrayList<>();
        when(module.getNodes()).thenAnswer(inv -> iterator(versions));
        when(module.getNodes(anyString())).thenAnswer(inv -> iterator(versions));
        // Remember the versions list on the mock so version() can append to it.
        VERSIONS.put(module, versions);
        return module;
    }

    private static final java.util.Map<JCRNodeWrapper, List<JCRNodeWrapper>> VERSIONS = new java.util.IdentityHashMap<>();

    private static JCRNodeWrapper version(JCRNodeWrapper module, String number, boolean published, boolean canWrite) throws Exception {
        String name = module.getName() + "-" + number;
        JCRNodeWrapper version = mock(JCRNodeWrapper.class);
        when(version.getName()).thenReturn(name);
        when(version.getParent()).thenReturn(module);
        when(version.isNodeType("jnt:forgeModuleVersion")).thenReturn(true);
        when(version.hasPermission("jcr:write")).thenReturn(canWrite);
        flag(version, published);
        JCRPropertyWrapper versionNumber = mock(JCRPropertyWrapper.class);
        when(versionNumber.getString()).thenReturn(number);
        when(version.hasProperty("versionNumber")).thenReturn(true);
        when(version.getProperty("versionNumber")).thenReturn(versionNumber);
        when(version.getPropertyAsString("versionNumber")).thenReturn(number);
        VERSIONS.get(module).add(version);
        return version;
    }

    private static void flag(JCRNodeWrapper node, boolean published) throws Exception {
        JCRPropertyWrapper prop = mock(JCRPropertyWrapper.class);
        when(prop.getBoolean()).thenReturn(published);
        when(node.hasProperty("published")).thenReturn(true);
        when(node.getProperty("published")).thenReturn(prop);
    }

    private static JCRNodeIteratorWrapper iterator(List<JCRNodeWrapper> list) {
        Iterator<JCRNodeWrapper> it = list.iterator();
        JCRNodeIteratorWrapper wrapper = mock(JCRNodeIteratorWrapper.class);
        when(wrapper.hasNext()).thenAnswer(inv -> it.hasNext());
        when(wrapper.nextNode()).thenAnswer(inv -> it.next());
        when(wrapper.next()).thenAnswer(inv -> it.next());
        return wrapper;
    }
}
