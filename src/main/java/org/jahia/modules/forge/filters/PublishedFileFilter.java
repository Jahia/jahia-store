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
package org.jahia.modules.forge.filters;

import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.modules.forge.visibility.StoreVisibility;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.render.filter.ContextPlaceholdersReplacer;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriUtils;

import javax.jcr.RepositoryException;
import javax.servlet.DispatcherType;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Applies the store's published-only rule ({@link StoreVisibility}) to the file servlet
 * ({@code /files/<workspace>/<path>}), which does not run the render chain and so never ran
 * {@link PublishedModuleFilter}: the artifact ({@code jnt:file}) of a draft version, or of any version
 * of a draft module, was downloadable by anyone at its predictable URL (JSTORE-SEC-005).
 *
 * <p>The filter resolves the node the file servlet would serve, in the caller's own session, the same
 * way the servlet does. When the node sits under a draft the caller cannot write, it answers 404, like a
 * missing file. In every other case (a published artifact, any file outside the store, a missing or
 * unreadable node, an unknown workspace, a repository error) the request goes on to the file servlet,
 * which answers as it always did.
 */
@Component(service = AbstractServletFilter.class, immediate = true)
public class PublishedFileFilter extends AbstractServletFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublishedFileFilter.class);
    private static final String FILES_SERVLET = "/files";
    /** After Jahia's own filters (JcrSessionFilter sets the caller at -1, UrlRewriteFilter runs at 1). */
    private static final float ORDER = 10f;

    @Activate
    public void activate() {
        setFilterName("JahiaStorePublishedFileFilter");
        setUrlPatterns(new String[] {FILES_SERVLET + "/*"});
        setDispatcherTypes(new HashSet<>(Arrays.asList(DispatcherType.REQUEST.name(), DispatcherType.FORWARD.name())));
        setOrder(ORDER);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to initialize
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest && isHiddenDraft((HttpServletRequest) request)) {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // nothing to release
    }

    private static boolean isHiddenDraft(HttpServletRequest request) {
        String filePath = filePath(request);
        // "/<workspace>/<node path>", as the file servlet parses it.
        int pathStart = filePath == null ? -1 : filePath.indexOf('/', 1);
        if (pathStart <= 1) {
            return false;
        }
        String workspace = workspace(filePath.substring(1, pathStart));
        if (workspace == null) {
            return false;
        }
        String nodePath = JCRContentUtils.escapeNodePath(filePath.substring(pathStart));
        JCRNodeWrapper node;
        try {
            node = JCRSessionFactory.getInstance().getCurrentUserSession(workspace).getNode(nodePath);
        } catch (RepositoryException e) {
            // Missing, unreadable or malformed: the file servlet resolves the same node and fails the same way.
            return false;
        }
        boolean hidden = !StoreVisibility.isVisibleToCaller(node);
        if (hidden) {
            LOGGER.debug("Refused the file {} of a draft store element", nodePath);
        }
        return hidden;
    }

    /** The part after {@code /files}, decoded like the servlet container decodes the servlet's path info. */
    private static String filePath(HttpServletRequest request) {
        if (FILES_SERVLET.equals(request.getServletPath())) {
            return request.getPathInfo();
        }
        String uri = StringUtils.substringAfter(StringUtils.defaultString(request.getRequestURI()),
                StringUtils.defaultString(request.getContextPath()));
        if (!uri.startsWith(FILES_SERVLET + "/")) {
            return null;
        }
        try {
            return UriUtils.decode(uri.substring(FILES_SERVLET.length()), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }

    /** The workspace the file servlet would read, or null for one it does not know. */
    private static String workspace(String segment) {
        if (ContextPlaceholdersReplacer.WORKSPACE_PLACEHOLDER.equals(segment)) {
            return Constants.EDIT_WORKSPACE;
        }
        if (Constants.LIVE_WORKSPACE.equals(segment) || Constants.EDIT_WORKSPACE.equals(segment)) {
            return segment;
        }
        return null;
    }
}
