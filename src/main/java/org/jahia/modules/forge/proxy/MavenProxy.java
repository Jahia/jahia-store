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
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.xerces.impl.dv.util.Base64;
import org.jahia.data.templates.ModuleReleaseInfo;
import org.jahia.modules.forge.actions.ActionSecurityUtils;
import org.jahia.modules.forge.settings.ForgeSettings;
import org.jahia.modules.forge.settings.ForgeSettingsService;
import org.jahia.modules.forge.visibility.StoreVisibility;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;

import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
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
    private static final String MODULE_TYPE = "jnt:forgeModule";
    private static final String VERSION_TYPE = "jnt:forgeModuleVersion";

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
            // gate is repository-level, not an allow-list of catalogued artifacts; the only
            // artifact-level rule is the deny of catalogued DRAFTS below. Residual: a store whose catalogue is
            // PUBLIC but whose artifacts must stay private needs a restrictive ACL on its
            // modules-repository node (operational prerequisite).
            if (!callerCanAccessRepository(session, siteName)) {
                final String caller = (session.getUser() != null) ? session.getUser().getName() : "<none>";
                LOGGER.warn("MavenProxy: caller '{}' is not authorized for site '{}' module repository",
                        ActionSecurityUtils.sanitizeForLog(caller), ActionSecurityUtils.sanitizeForLog(siteName));
                trySendError(response, HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // Published-only rule (JSTORE-SEC-005): an artifact of a catalogued module or version that is
            // still a draft answers 404, like a missing artifact, unless the caller can write the draft
            // (owner preview). Raw coordinates the catalogue does not list are still served (see above).
            if (isDraftArtifact(session, siteName, path)) {
                LOGGER.debug("MavenProxy: refused an artifact of a draft store module on site '{}'",
                        ActionSecurityUtils.sanitizeForLog(siteName));
                trySendError(response, HttpServletResponse.SC_NOT_FOUND);
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
        httpMethod.addHeader("Authorization", "Basic " + Base64.encode((releaseInfo.getUsername() + ":" + releaseInfo.getPassword()).getBytes()));
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
        return session.nodeExists(StoreVisibility.modulesRepositoryPath(siteName));
    }

    /**
     * True when {@code path} addresses an artifact of a module of the site's catalogue that the caller
     * may not see: the module itself is a draft (every path under {@code /<groupPath>/<name>/}, its
     * {@code maven-metadata.xml} included), or the version directory {@code /<groupPath>/<name>/<version>/}
     * belongs to a draft version. The catalogue is read in the caller's own session, so a module the caller
     * cannot read is not listed and its coordinates are served as raw ones, the documented residual.
     * Candidate modules are found by groupId: every prefix of the path's directories, dots for slashes.
     */
    static boolean isDraftArtifact(JCRSessionWrapper session, String siteName, String path) throws RepositoryException {
        String[] segments = StringUtils.split(path, '/');
        if (segments.length < 3) {
            return false;
        }
        JCRNodeIteratorWrapper modules = session.getWorkspace().getQueryManager()
                .createQuery(catalogueQuery(siteName, segments), Query.JCR_SQL2).execute().getNodes();
        while (modules.hasNext()) {
            JCRNodeWrapper module = (JCRNodeWrapper) modules.nextNode();
            String groupId = module.hasProperty("groupId") ? module.getProperty("groupId").getString() : null;
            String prefix = FileSystem.SEPARATOR + StringUtils.replace(StringUtils.defaultString(groupId), ".", FileSystem.SEPARATOR)
                    + FileSystem.SEPARATOR + module.getName() + FileSystem.SEPARATOR;
            if (StringUtils.isNotBlank(groupId) && path.startsWith(prefix) && isHidden(module, path.substring(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    /** The module is hidden, or the version directory {@code rest} starts with belongs to a hidden version. */
    private static boolean isHidden(JCRNodeWrapper module, String rest) throws RepositoryException {
        if (!StoreVisibility.isVisibleToCaller(module)) {
            return true;
        }
        String versionDirectory = StringUtils.substringBefore(rest, FileSystem.SEPARATOR);
        if (versionDirectory.equals(rest)) {
            return false; // a file of the module directory (maven-metadata.xml): the module is visible
        }
        JCRNodeIteratorWrapper children = module.getNodes();
        while (children.hasNext()) {
            JCRNodeWrapper child = (JCRNodeWrapper) children.nextNode();
            if (child.isNodeType(VERSION_TYPE) && child.hasProperty("versionNumber")
                    && versionDirectory.equals(child.getProperty("versionNumber").getString())
                    && !StoreVisibility.isVisibleToCaller(child)) {
                return true;
            }
        }
        return false;
    }

    /** The modules under the site's repository whose groupId is one of the path's directory prefixes. */
    private static String catalogueQuery(String siteName, String[] segments) {
        StringBuilder query = new StringBuilder("SELECT * FROM [").append(MODULE_TYPE)
                .append("] AS m WHERE ISDESCENDANTNODE(m, '")
                .append(escape(StoreVisibility.modulesRepositoryPath(siteName))).append("') AND (");
        StringBuilder groupId = new StringBuilder();
        // The last segment is a file and the one before it at least the artifactId: never part of the groupId.
        for (int i = 0; i < segments.length - 2; i++) {
            if (i > 0) {
                groupId.append('.');
                query.append(" OR ");
            }
            groupId.append(segments[i]);
            query.append("m.[groupId] = '").append(escape(groupId.toString())).append('\'');
        }
        return query.append(')').toString();
    }

    private static String escape(String literal) {
        return StringUtils.replace(literal, "'", "''");
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
