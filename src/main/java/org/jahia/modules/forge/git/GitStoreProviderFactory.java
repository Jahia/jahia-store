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
 *     For questions regarding licensing, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.git;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import javax.jcr.RepositoryException;
import org.apache.commons.lang.StringUtils;
import org.jahia.exceptions.JahiaException;
import org.jahia.exceptions.JahiaInitializationException;
import org.jahia.modules.external.ExternalContentStoreProvider;
import org.jahia.modules.external.ExternalContentStoreProviderFactory;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRStoreProvider;
import org.jahia.services.content.ProviderFactory;
import org.jahia.services.sites.JahiaSite;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mounts a {@code jnt:gitStoreMountPoint} node as a read-only external provider that serves the
 * module of the repository the node names. Jahia calls it for each such node at startup and
 * when one is created.
 */
@Component(service = ProviderFactory.class, immediate = true)
public class GitStoreProviderFactory implements ProviderFactory {

    public static final String NODE_TYPE = "jnt:gitStoreMountPoint";
    public static final String P_GIT_URL = "j:gitUrl";
    public static final String P_TAG_PATTERN = "j:tagPattern";
    public static final String P_BRANCH = "j:branch";
    public static final String P_REGISTERED_BY = "j:registeredBy";

    private static final Logger LOGGER = LoggerFactory.getLogger(GitStoreProviderFactory.class);

    @Reference
    private ExternalContentStoreProviderFactory externalContentStoreProviderFactory;

    @Reference
    private GitStoreSettings settings;

    @Override
    public String getNodeTypeName() {
        return NODE_TYPE;
    }

    @Override
    public JCRStoreProvider mountProvider(JCRNodeWrapper mountPoint) throws RepositoryException {
        String url = mountPoint.getProperty(P_GIT_URL).getString();
        String pattern = optional(mountPoint, P_TAG_PATTERN);
        String branch = optional(mountPoint, P_BRANCH);
        String path = mountPoint.getPath();

        GitRepositoryReader reader;
        try {
            reader = new GitRepositoryReader(settings.getCacheDir(), url);
        } catch (IOException e) {
            throw new RepositoryException("Cannot open the git cache for " + url, e);
        }
        GitStoreDataSource dataSource = new GitStoreDataSource(reader, branch,
                VersionTags.compile(StringUtils.defaultIfBlank(pattern, settings.getDefaultTagPattern())),
                settings.getRefreshMillis(), siteLanguages(path), path);
        dataSource.setRegisteredBy(optional(mountPoint, P_REGISTERED_BY));

        ExternalContentStoreProvider provider = externalContentStoreProviderFactory.newProvider();
        provider.setKey(mountPoint.getIdentifier());
        provider.setMountPoint(path);
        provider.setDataSource(dataSource);
        provider.setDynamicallyMounted(true);
        provider.setSessionFactory(JCRSessionFactory.getInstance());
        // The store keeps beside a git-backed node what the repository does not hold.
        provider.setExtendableTypes(Arrays.asList(GitStoreDataSource.TYPE_MODULE, GitStoreDataSource.TYPE_VERSION));
        try {
            provider.start();
        } catch (JahiaInitializationException e) {
            throw new RepositoryException("Cannot mount the git-backed module of " + url + " at " + path, e);
        }
        LOGGER.info("Mounted the git-backed module of {} at {}", url, path);
        return provider;
    }

    private static String optional(JCRNodeWrapper node, String property) throws RepositoryException {
        return node.hasProperty(property) ? StringUtils.trimToNull(node.getProperty(property).getString()) : null;
    }

    /** The languages of the site a mounted path belongs to, or English when the path is outside a site. */
    private static Set<String> siteLanguages(String path) {
        String siteKey = StringUtils.substringBetween(path, JahiaSitesService.SITES_JCR_PATH + "/", "/");
        if (StringUtils.isNotBlank(siteKey)) {
            try {
                JahiaSite site = JahiaSitesService.getInstance().getSiteByKey(siteKey);
                if (site != null && site.getLanguages() != null && !site.getLanguages().isEmpty()) {
                    return site.getLanguages();
                }
            } catch (JahiaException e) {
                LOGGER.warn("Cannot read the languages of the site {}: {}", siteKey, e.getMessage());
            }
        }
        return Collections.singleton("en");
    }
}
