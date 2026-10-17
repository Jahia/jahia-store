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
package org.jahia.modules.forge.graphql;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import javax.jcr.RepositoryException;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.modules.external.ExternalContentStoreProvider;
import org.jahia.modules.forge.git.GitModule;
import org.jahia.modules.forge.git.GitModuleLoader;
import org.jahia.modules.forge.git.GitRepositoryReader;
import org.jahia.modules.forge.git.GitStoreDataSource;
import org.jahia.modules.forge.git.GitStoreProviderFactory;
import org.jahia.modules.forge.git.GitStoreSettings;
import org.jahia.modules.forge.git.StoreManifestException;
import org.jahia.modules.forge.git.VersionTags;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.cache.CacheHelper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRStoreProvider;
import org.jahia.services.content.decorator.JCRMountPointNode;
import org.jahia.services.sites.JahiaSitesService;

/**
 * The registration of git-backed modules: a mount point node per repository, as
 * docs/git-backed-modules.md states. A registration reads the repository first, so a repository
 * that fails the contract is refused with the reason, and never mounted.
 */
public final class GitModuleMutationExtension {

    static final String PERMISSION = "siteAdminForgeSettings";
    static final String MOUNTS_PATH = "/mounts";
    static final String MODULES_REPOSITORY = "contents/modules-repository";

    private static final String SITES_PATH = JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR;

    private GitModuleMutationExtension() {
    }

    public static GqlGitModule register(String siteKey, String gitUrl, String tagPattern, String branch) {
        final String url = validateGitUrl(gitUrl);
        final String pattern = StringUtils.trimToNull(tagPattern);
        final String branchName = StringUtils.trimToNull(branch);
        final GitStoreSettings settings = settings();
        VersionTags.compile(pattern);

        // Read the repository before anything is written: the contract errors are the answer.
        final GitModule module;
        try (GitRepositoryReader reader = new GitRepositoryReader(settings.getCacheDir(), url)) {
            module = GitModuleLoader.load(reader, branchName,
                    VersionTags.compile(StringUtils.defaultIfBlank(pattern, settings.getDefaultTagPattern())));
        } catch (IOException | RuntimeException e) {
            // StoreManifestException is the expected refusal; any other runtime failure of the
            // git transport is reported the same way rather than as a bare server error.
            throw new ForgeSettingsException("The repository " + url + " cannot be registered: " + e.getMessage(), e);
        }
        final String name = module.getManifest().getName();

        return ForgeSiteAccess.executeAsSystemForSite(siteKey, PERMISSION, "Git module registration failed for site ", session -> {
            JCRNodeWrapper repository = modulesRepository(session, siteKey);
            if (repository.hasNode(name)) {
                throw new RepositoryException("A module named '" + name + "' already exists in the store");
            }
            JCRNodeWrapper mounts = session.getNode(MOUNTS_PATH);
            String mountName = name + JCRMountPointNode.MOUNT_SUFFIX;
            if (mounts.hasNode(mountName)) {
                throw new RepositoryException("A git-backed module named '" + name + "' is already registered");
            }
            JCRNodeWrapper mount = mounts.addNode(mountName, GitStoreProviderFactory.NODE_TYPE);
            mount.setProperty(GitStoreProviderFactory.P_GIT_URL, url);
            if (pattern != null) {
                mount.setProperty(GitStoreProviderFactory.P_TAG_PATTERN, pattern);
            }
            if (branchName != null) {
                mount.setProperty(GitStoreProviderFactory.P_BRANCH, branchName);
            }
            mount.setProperty(JCRMountPointNode.MOUNT_POINT_PROPERTY_NAME, repository);
            session.save();
            CacheHelper.flushOutputCachesForPath(SITES_PATH + siteKey, true);
            return describe(session.getNode(MOUNTS_PATH + "/" + mountName), repository.getPath());
        });
    }

    public static Boolean unregister(String siteKey, String name) {
        return ForgeSiteAccess.executeAsSystemForSite(siteKey, PERMISSION, "Git module removal failed for site ", session -> {
            JCRNodeWrapper repository = modulesRepository(session, siteKey);
            JCRNodeWrapper mount = mountOf(session, repository, name);
            if (mount == null) {
                return Boolean.FALSE;
            }
            // JCRMountPointNode.remove() stops the provider before the node goes.
            mount.remove();
            session.save();
            CacheHelper.flushOutputCachesForPath(SITES_PATH + siteKey, true);
            return Boolean.TRUE;
        });
    }

    public static Boolean refresh(String siteKey, String name) {
        return ForgeSiteAccess.executeAsSystemForSite(siteKey, PERMISSION, "Git module refresh failed for site ", session -> {
            JCRNodeWrapper repository = modulesRepository(session, siteKey);
            JCRNodeWrapper mount = mountOf(session, repository, name);
            if (!(mount instanceof JCRMountPointNode)) {
                return Boolean.FALSE;
            }
            JCRStoreProvider provider = ((JCRMountPointNode) mount).getMountProvider();
            if (provider instanceof ExternalContentStoreProvider
                    && ((ExternalContentStoreProvider) provider).getDataSource() instanceof GitStoreDataSource) {
                ((GitStoreDataSource) ((ExternalContentStoreProvider) provider).getDataSource()).requestRefresh();
            }
            CacheHelper.flushOutputCachesForPath(repository.getPath() + "/" + name, true);
            return Boolean.TRUE;
        });
    }

    /** The registrations whose module is served under the modules-repository of a site. */
    public static List<GqlGitModule> list(String siteKey) {
        return ForgeSiteAccess.executeAsSystemForSite(siteKey, PERMISSION, "Git module listing failed for site ", session -> {
            JCRNodeWrapper repository = modulesRepository(session, siteKey);
            List<GqlGitModule> result = new ArrayList<>();
            for (JCRNodeWrapper mount : session.getNode(MOUNTS_PATH).getNodes()) {
                if (mount.isNodeType(GitStoreProviderFactory.NODE_TYPE) && targets(mount, repository)) {
                    result.add(describe(mount, repository.getPath()));
                }
            }
            return result;
        });
    }

    static String validateGitUrl(String gitUrl) {
        String url = StringUtils.trimToEmpty(gitUrl);
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ForgeSettingsException("The git URL is not a valid URL: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!uri.isAbsolute() || uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new ForgeSettingsException("The git URL must be an absolute http or https URL: " + url, null);
        }
        return url;
    }

    private static JCRNodeWrapper modulesRepository(JCRSessionWrapper session, String siteKey) throws RepositoryException {
        String path = SITES_PATH + siteKey + FileSystem.SEPARATOR + MODULES_REPOSITORY;
        if (!session.nodeExists(path)) {
            throw new RepositoryException("The site " + siteKey + " has no modules repository");
        }
        return session.getNode(path);
    }

    private static JCRNodeWrapper mountOf(JCRSessionWrapper session, JCRNodeWrapper repository, String name) throws RepositoryException {
        if (StringUtils.isBlank(name) || name.contains("/") || name.contains("..")) {
            throw new RepositoryException("Invalid module name: " + name);
        }
        String path = MOUNTS_PATH + "/" + name + JCRMountPointNode.MOUNT_SUFFIX;
        if (!session.nodeExists(path)) {
            return null;
        }
        JCRNodeWrapper mount = session.getNode(path);
        if (!mount.isNodeType(GitStoreProviderFactory.NODE_TYPE) || !targets(mount, repository)) {
            return null;
        }
        return mount;
    }

    private static boolean targets(JCRNodeWrapper mount, JCRNodeWrapper repository) throws RepositoryException {
        if (!mount.hasProperty(JCRMountPointNode.MOUNT_POINT_PROPERTY_NAME)) {
            return false;
        }
        try {
            return repository.getIdentifier().equals(mount.getProperty(JCRMountPointNode.MOUNT_POINT_PROPERTY_NAME).getNode().getIdentifier());
        } catch (RepositoryException e) {
            return false;
        }
    }

    private static GqlGitModule describe(JCRNodeWrapper mount, String repositoryPath) throws RepositoryException {
        String name = StringUtils.removeEnd(mount.getName(), JCRMountPointNode.MOUNT_SUFFIX);
        String status = mount instanceof JCRMountPointNode ? ((JCRMountPointNode) mount).getMountStatus().name() : "unknown";
        return new GqlGitModule(name,
                mount.getProperty(GitStoreProviderFactory.P_GIT_URL).getString(),
                optional(mount, GitStoreProviderFactory.P_TAG_PATTERN),
                optional(mount, GitStoreProviderFactory.P_BRANCH),
                repositoryPath + "/" + name, status);
    }

    private static String optional(JCRNodeWrapper node, String property) throws RepositoryException {
        return node.hasProperty(property) ? StringUtils.trimToNull(node.getProperty(property).getString()) : null;
    }

    private static GitStoreSettings settings() {
        GitStoreSettings settings = BundleUtils.getOsgiService(GitStoreSettings.class, null);
        if (settings == null) {
            throw new ForgeSettingsException("GitStoreSettings is not available", null);
        }
        return settings;
    }
}
