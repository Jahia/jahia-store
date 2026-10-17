/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2026 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms & Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.xerces.impl.dv.util.Base64;
import org.jahia.data.templates.ModuleReleaseInfo;
import org.jahia.modules.forge.actions.ActionSecurityUtils;
import org.jahia.modules.forge.settings.ForgeSettings;
import org.jahia.modules.forge.settings.ForgeSettingsService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.sites.JahiaSitesService;

import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.jahia.services.notification.HttpClientService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP servlet exposed on /modules/mavenproxy/{siteName}/** that streams artifacts from the
 * site's configured Maven repository, forwarding credentials from the site's forgeSettings.
 */
@Component(service = { HttpServlet.class, Servlet.class }, property = { "alias=/mavenproxy" })
public class MavenProxy extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(MavenProxy.class);
    private static final String SITES_PATH = JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR;
    private static final String MODULES_REPOSITORY = "/contents/modules-repository";

    private final HttpClientService httpClientService;
    private final ForgeSettingsService forgeSettingsService;

    @Activate
    public MavenProxy(@Reference HttpClientService httpClientService,
                      @Reference ForgeSettingsService forgeSettingsService) {
        this.httpClientService = httpClientService;
        this.forgeSettingsService = forgeSettingsService;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        try {
            JCRSessionWrapper session = JCRSessionFactory.getInstance().getCurrentUserSession("live");

            String pathInfo = StringUtils.defaultString(request.getPathInfo());
            if (pathInfo.startsWith("/")) {
                pathInfo = pathInfo.substring(1);
            }
            String siteName = StringUtils.substringBefore(pathInfo, "/");
            String path = "/" + StringUtils.substringAfter(pathInfo, "/");

            // Reject path traversal and absolute-URL injection to prevent SSRF: the caller must
            // not be able to escape the configured repository root or point at another host. The
            // siteName segment is validated separately (it is concatenated into a JCR path).
            if (isSuspicious(path) || !isValidSiteName(siteName)) {
                // siteName/path are raw request input reaching this sink BEFORE validation (a CR/LF
                // in path is exactly what trips isSuspicious), so sanitize to prevent log forging
                // (CWE-117) on this anonymous-reachable branch (SECURITY-571 #58).
                LOGGER.warn("MavenProxy: rejected suspicious request site='{}' path='{}'",
                        ActionSecurityUtils.sanitizeForLog(siteName), ActionSecurityUtils.sanitizeForLog(path));
                trySendError(response, HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            // Authorization (SECURITY-571 B1): serve a site's modules only to a caller who can
            // READ that site's module repository in their OWN session. modules-repository is
            // jmix:accessControlled, so this single repository-level gate handles both cases:
            //   - PUBLIC store (world-readable repository): anonymous/guest visitors can download
            //     — the public-app-store use case. The site's STORED Maven credentials are used
            //     SERVER-SIDE only (to fetch from Nexus) and are NEVER returned to the client, so
            //     forwarding them on behalf of an anonymous request is safe.
            //   - PRIVATE store (restrictive inherited ACL): guest/foreign callers are blocked.
            // (A blanket "no anonymous" rule used to sit in front of this; it broke downloads on
            // public stores and was redundant with this gate, so it was removed.) The proxy
            // intentionally serves raw Maven coordinates (not only catalogued modules), so the
            // gate is repository-level, not artifact-level. Residual: a store whose catalogue is
            // PUBLIC but whose artifacts must stay private needs a restrictive ACL on its
            // modules-repository node (operational prerequisite).
            if (!callerCanAccessRepository(session, siteName)) {
                final String caller = (session.getUser() != null) ? session.getUser().getName() : "<none>";
                LOGGER.warn("MavenProxy: caller '{}' is not authorized for site '{}' module repository",
                        ActionSecurityUtils.sanitizeForLog(caller), ActionSecurityUtils.sanitizeForLog(siteName));
                trySendError(response, HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // A git-backed module declares the artefact URL of each version on its version node
            // (docs/git-backed-modules.md), and the store holds no Maven repository for it.
            final URI declared = declaredArtifact(session, siteName, path);
            if (declared != null) {
                streamArtifact(declared, null, declared.toString(), response);
                return;
            }

            ModuleReleaseInfo releaseInfo = getModuleReleaseInfo(siteName);

            String repositoryUrl = releaseInfo.getRepositoryUrl();
            if (StringUtils.isBlank(repositoryUrl)) {
                LOGGER.warn("MavenProxy: site '{}' has no configured repository URL",
                        ActionSecurityUtils.sanitizeForLog(siteName));
                trySendError(response, HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            // Normalize the root to a single trailing slash so the boundary check below cannot be
            // satisfied by a sibling-prefix host (e.g. root ".../foo" must not accept ".../foobar").
            final String repositoryRoot = StringUtils.removeEnd(repositoryUrl, "/") + "/";
            String url = StringUtils.removeEnd(repositoryUrl, "/") + path;
            // Canonicalize and confirm the resolved URL still lives under the repository root.
            final URI resolvedUri = UriComponentsBuilder.fromHttpUrl(url).build(false).toUri().normalize();
            if (!resolvedUri.toString().startsWith(repositoryRoot)) {
                LOGGER.warn("MavenProxy: resolved URL '{}' escapes repository root '{}'",
                        ActionSecurityUtils.sanitizeForLog(resolvedUri.toString()), repositoryRoot);
                trySendError(response, HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            streamArtifact(resolvedUri, releaseInfo, url, response);
        } catch (RepositoryException ex) {
            LOGGER.error("MavenProxy: repository access failed", ex);
            trySendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } catch (IOException ex) {
            LOGGER.error(ex.getMessage(), ex);
            trySendError(response, HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private void streamArtifact(URI resolvedUri, ModuleReleaseInfo releaseInfo, String url, HttpServletResponse response) throws IOException {
        final CloseableHttpClient httpClient = httpClientService.getHttpClient(url);
        final HttpGet httpMethod = new HttpGet(resolvedUri);
        if (releaseInfo != null) {
            httpMethod.addHeader("Authorization", "Basic " + Base64.encode((releaseInfo.getUsername() + ":" + releaseInfo.getPassword()).getBytes()));
        }
        try (final CloseableHttpResponse httpResponse = httpClient.execute(httpMethod)) {
            if (httpResponse.getCode() != HttpServletResponse.SC_OK) {
                trySendError(response, httpResponse.getCode());
                return;
            }
            // Force a download and forbid MIME sniffing: the proxy serves anonymous callers and
            // mirrors the upstream Content-Type, so a compromised/misconfigured Nexus returning
            // text/html with a script body must not be rendered in the Jahia origin (stored XSS).
            response.setHeader("Content-Disposition", "attachment");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentType(httpResponse.getEntity().getContentType());
            try {
                IOUtils.copy(httpResponse.getEntity().getContent(), response.getOutputStream());
            } catch (UnsupportedOperationException ex) {
                LOGGER.error("MavenProxy: response entity content not readable", ex);
                trySendError(response, HttpServletResponse.SC_BAD_GATEWAY);
            }
        }
    }

    private static void trySendError(HttpServletResponse response, int code) {
        try {
            response.sendError(code);
        } catch (IOException ex) {
            LOGGER.warn("MavenProxy: failed to send error {} to client", code, ex);
        }
    }

    static boolean isSuspicious(String path) {
        // Block raw AND percent-encoded traversal / scheme-injection sequences. The proxy now
        // serves anonymous callers on public stores, so harden the SSRF guard against encoded
        // variants regardless of how the downstream URI builder normalizes them. Legitimate
        // Maven coordinate paths never contain these.
        final String p = path.toLowerCase();
        return p.contains("..") || p.contains("\\") || p.contains("://")
                || p.contains("\n") || p.contains("\r")
                || p.contains("%2e") || p.contains("%2f") || p.contains("%5c");
    }

    /** A site key is a simple identifier; reject anything that could alter the JCR path. The
     *  charset permits dots, so ".." is rejected explicitly (it would otherwise match the pattern). */
    static boolean isValidSiteName(String siteName) {
        return StringUtils.isNotBlank(siteName) && siteName.matches("[A-Za-z0-9._-]+")
                && !siteName.contains("..");
    }

    /**
     * True when the caller's own session can read the site's (access-controlled) module
     * repository node — the prerequisite for using that site's stored Maven credentials.
     * {@code nodeExists} returns false both when the node is absent and when the caller
     * lacks read access, which is exactly the gate we want for a private site.
     */
    private static boolean callerCanAccessRepository(final JCRSessionWrapper session, final String siteName)
            throws RepositoryException {
        return session.nodeExists(SITES_PATH + siteName + MODULES_REPOSITORY);
    }

    /**
     * The artefact URL a version node of the site declares for the requested coordinates, or null
     * when the coordinates name no such version. The coordinates follow the proxy grammar
     * {@code /<groupId as path>/<name>/<version>/<file>}, so the name and the version are the
     * third- and second-to-last segments. The URL is the one the repository of a git-backed
     * module declares, and it is served only with an http or https scheme.
     */
    static URI declaredArtifact(final JCRSessionWrapper session, final String siteName, final String path)
            throws RepositoryException {
        final String[] segments = StringUtils.split(path, '/');
        if (segments.length < 4) {
            return null;
        }
        final String name = segments[segments.length - 3];
        final String version = segments[segments.length - 2];
        final String modulePath = SITES_PATH + siteName + MODULES_REPOSITORY + "/" + name;
        if (!session.nodeExists(modulePath)) {
            return null;
        }
        final JCRNodeWrapper module = session.getNode(modulePath);
        if (!module.isNodeType("jnt:forgeModule")) {
            return null;
        }
        for (JCRNodeWrapper child : module.getNodes()) {
            if (child.isNodeType("jnt:forgeModuleVersion") && child.hasProperty("versionNumber")
                    && version.equals(child.getProperty("versionNumber").getString()) && child.hasProperty("url")) {
                final String url = child.getProperty("url").getString();
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    try {
                        return new URI(url);
                    } catch (URISyntaxException e) {
                        LOGGER.warn("MavenProxy: version {} of {} declares an unusable artefact URL", version, name);
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** Build the upstream Maven coordinates + credentials from the site's OSGi-config settings. */
    private ModuleReleaseInfo getModuleReleaseInfo(final String siteName) {
        final ForgeSettings settings = forgeSettingsService.get(siteName);
        ModuleReleaseInfo info = new ModuleReleaseInfo();
        info.setRepositoryUrl(settings.getUrl());
        info.setUsername(settings.getUser());
        info.setPassword(settings.getPassword());
        return info;
    }
}
