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
import java.net.URI;
import java.net.URISyntaxException;
import javax.jcr.RepositoryException;
import org.apache.commons.lang.StringUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRMountPointNode;

/**
 * The registration of a git-backed module, as docs/git-backed-modules.md states it: one mount
 * point node per repository under {@code /mounts}, served under the modules repository of the
 * site. The GraphQL API of the administration and the action of the storefront both go through
 * here, so a repository is read and refused the same way whoever registers it.
 */
public final class GitModuleRegistrations {

    public static final String MOUNTS_PATH = "/mounts";

    private GitModuleRegistrations() {
    }

    /**
     * @return the URL, trimmed
     * @throws StoreManifestException when it is not an absolute http or https URL
     */
    public static String validateGitUrl(String gitUrl) {
        String url = StringUtils.trimToEmpty(gitUrl);
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new StoreManifestException("The git URL is not a valid URL: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!uri.isAbsolute() || uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new StoreManifestException("The git URL must be an absolute http or https URL: " + url);
        }
        return url;
    }

    /**
     * Read the repository as the provider will, so a repository that fails the contract is refused
     * before anything is written.
     *
     * @throws IOException            when the remote cannot be read
     * @throws StoreManifestException when the repository fails the contract, or the pattern is broken
     */
    public static GitModule read(GitStoreSettings settings, String url, String tagPattern, String branch) throws IOException {
        VersionTags.compile(tagPattern);
        try (GitRepositoryReader reader = new GitRepositoryReader(settings.getCacheDir(), url)) {
            return GitModuleLoader.load(reader, branch,
                    VersionTags.compile(StringUtils.defaultIfBlank(tagPattern, settings.getDefaultTagPattern())));
        }
    }

    /** The name of the mount point node of a module. */
    public static String mountName(String moduleName) {
        return moduleName + JCRMountPointNode.MOUNT_SUFFIX;
    }

    /** True when the store already holds a module of that name, local or git-backed. */
    public static boolean exists(JCRSessionWrapper session, JCRNodeWrapper repository, String moduleName) throws RepositoryException {
        return repository.hasNode(moduleName) || session.getNode(MOUNTS_PATH).hasNode(mountName(moduleName));
    }

    /**
     * Create the mount point node and save the session. The caller checks {@link #exists} first,
     * and reads the repository first. The provider is mounted by Jahia when the save lands.
     *
     * @param registeredBy the user the module is registered for, who the module reads as its creator
     */
    public static JCRNodeWrapper register(JCRSessionWrapper session, JCRNodeWrapper repository, String url, String tagPattern,
                                          String branch, String registeredBy, String moduleName) throws RepositoryException {
        JCRNodeWrapper mount = session.getNode(MOUNTS_PATH).addNode(mountName(moduleName), GitStoreProviderFactory.NODE_TYPE);
        mount.setProperty(GitStoreProviderFactory.P_GIT_URL, url);
        if (StringUtils.isNotBlank(tagPattern)) {
            mount.setProperty(GitStoreProviderFactory.P_TAG_PATTERN, tagPattern.trim());
        }
        if (StringUtils.isNotBlank(branch)) {
            mount.setProperty(GitStoreProviderFactory.P_BRANCH, branch.trim());
        }
        if (StringUtils.isNotBlank(registeredBy)) {
            mount.setProperty(GitStoreProviderFactory.P_REGISTERED_BY, registeredBy);
        }
        mount.setProperty(JCRMountPointNode.MOUNT_POINT_PROPERTY_NAME, repository);
        session.save();
        return mount;
    }

    /** The mount point node of a module of the repository, or null when the module is not git-backed. */
    public static JCRNodeWrapper mountOf(JCRSessionWrapper session, JCRNodeWrapper repository, String moduleName) throws RepositoryException {
        if (StringUtils.isBlank(moduleName) || moduleName.contains("/") || moduleName.contains("..")) {
            throw new RepositoryException("Invalid module name: " + moduleName);
        }
        String path = MOUNTS_PATH + "/" + mountName(moduleName);
        if (!session.nodeExists(path)) {
            return null;
        }
        JCRNodeWrapper mount = session.getNode(path);
        if (!mount.isNodeType(GitStoreProviderFactory.NODE_TYPE) || !targets(mount, repository)) {
            return null;
        }
        return mount;
    }

    /** True when the mount point serves its module under this repository. */
    public static boolean targets(JCRNodeWrapper mount, JCRNodeWrapper repository) throws RepositoryException {
        if (!mount.hasProperty(JCRMountPointNode.MOUNT_POINT_PROPERTY_NAME)) {
            return false;
        }
        try {
            return repository.getIdentifier().equals(mount.getProperty(JCRMountPointNode.MOUNT_POINT_PROPERTY_NAME).getNode().getIdentifier());
        } catch (RepositoryException e) {
            return false;
        }
    }
}
