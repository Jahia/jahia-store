package org.jahia.modules.forge.proxy;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.jahia.modules.forge.settings.ForgeSettings;
import org.jahia.modules.forge.settings.ForgeSettingsService;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.notification.HttpClientService;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
        when(settings.get("store")).thenReturn(ForgeSettings.empty()); // url == null/blank

        try (MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            MavenProxy proxy = proxyWithSession(f, http, settings, session);
            proxy.doGet(request, response);
        }

        verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST);
        verify(http, never()).getHttpClient(anyString());
        verify(settings, times(1)).get("store");
    }
}
