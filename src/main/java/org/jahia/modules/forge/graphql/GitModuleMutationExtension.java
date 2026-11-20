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
import java.util.ArrayList;
import java.util.List;
import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.modules.forge.git.GitModule;
import org.jahia.modules.forge.git.GitModuleRegistrations;
import org.jahia.modules.forge.git.GitStoreDataSource;
import org.jahia.modules.forge.git.GitStoreProviderFactory;
import org.jahia.modules.forge.git.GitStoreSettings;
import org.jahia.modules.forge.git.StoreManifestException;
import org.jahia.modules.forge.validation.ValidationService;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.cache.CacheHelper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRMountPointNode;
import org.jahia.services.sites.JahiaSitesService;

/**
 * The GraphQL side of git-backed modules (docs/git-backed-modules.md): register, list and remove,
 * for a caller who administers the store of the site, and refresh, for the developer who
 * registered the module too. A store developer registers and refreshes a repository from the
 * storefront through the actions.
 */
public final class GitModuleMutationExtension {

    static final String PERMISSION = "siteAdminForgeSettings";
    static final String PERMISSION_UPLOAD = "jahiaForgeUploadModule";
    static final String MODULES_REPOSITORY = "contents/modules-repository";

    private static final String SITES_PATH = JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR;

    private GitModuleMutationExtension() {
    }

    public static GqlGitModule register(String siteKey, String gitUrl) {
        final String url;
        final GitModule module;
        try {
            url = GitModuleRegistrations.validateGitUrl(gitUrl);
            module = GitModuleRegistrations.read(settings(), url);
        } catch (IOException | RuntimeException e) {
            // StoreManifestException is the expected refusal; any other runtime failure of the
            // git transport is reported the same way rather than as a bare server error.
            throw new ForgeSettingsException("The repository " + StringUtils.trimToEmpty(gitUrl) + " cannot be registered: " + e.getMessage(), e);
        }
        final String name = module.getManifest().getName();
        final String registeredBy = JCRSessionFactory.getInstance().getCurrentUser() == null ? null
                : JCRSessionFactory.getInstance().getCurrentUser().getUsername();

        GqlGitModule registered = ForgeSiteAccess.executeAsSystemForSite(siteKey, PERMISSION, "Git module registration failed for site ", session -> {
            JCRNodeWrapper repository = modulesRepository(session, siteKey);
            if (GitModuleRegistrations.exists(session, repository, name)) {
                throw new RepositoryException("A module named '" + name + "' already exists in the store");
            }
            JCRNodeWrapper mount = GitModuleRegistrations.register(session, repository, url, registeredBy, name);
            CacheHelper.flushOutputCachesForPath(SITES_PATH + siteKey, true);
            return describe(session.getNode(mount.getPath()), repository.getPath());
        });
        // Every version the repository lists goes through the validation before it reaches the public.
        validationService().requestMissing(GitModuleRegistrations.artifactsOf(siteKey, module), registeredBy);
        return registered;
    }

    private static ValidationService validationService() {
        ValidationService service = BundleUtils.getOsgiService(ValidationService.class, null);
        if (service == null) {
            throw new StoreManifestException("ValidationService is not available");
        }
        return service;
    }

    public static Boolean unregister(String siteKey, String name) {
        return ForgeSiteAccess.executeAsSystemForSite(siteKey, PERMISSION, "Git module removal failed for site ", session -> {
            JCRNodeWrapper repository = modulesRepository(session, siteKey);
            JCRNodeWrapper mount = GitModuleRegistrations.mountOf(session, repository, name);
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

    /**
     * Read the repository of a module again, now, for the developer who registered it or a store
     * administrator: the entry point of a process outside the store, such as the release workflow
     * of the repository. False when the module is not a mounted git-backed module; a repository
     * that cannot be read keeps the previous reading and the mutation fails with the cause.
     */
    public static Boolean refresh(String siteKey, String name) {
        return ForgeSiteAccess.executeAsSystemForSite(siteKey, PERMISSION_UPLOAD, "Git module refresh failed for site ", session -> {
            JCRNodeWrapper repository = modulesRepository(session, siteKey);
            JCRNodeWrapper mount = GitModuleRegistrations.mountOf(session, repository, name);
            GitStoreDataSource dataSource = mount == null ? null : GitModuleRegistrations.dataSourceOf(mount);
            if (dataSource == null) {
                return Boolean.FALSE;
            }
            JCRSessionWrapper caller = JCRSessionFactory.getInstance().getCurrentUserSession();
            String username = caller.getUser().getUsername();
            if (!username.equals(GitModuleRegistrations.registeredBy(mount))
                    && !caller.getNode(SITES_PATH + siteKey).hasPermission(PERMISSION)) {
                throw new AccessDeniedException("Only the developer who registered " + name + ", or a store administrator, can refresh it");
            }
            GitModule reading;
            try {
                reading = dataSource.refresh();
            } catch (IOException | StoreManifestException e) {
                throw new RepositoryException("The repository of " + name + " cannot be read: " + e.getMessage(), e);
            }
            validationService().requestMissing(GitModuleRegistrations.artifactsOf(siteKey, reading), username);
            CacheHelper.flushOutputCachesForPath(SITES_PATH + siteKey, true);
            return Boolean.TRUE;
        });
    }

    /** The registrations whose module is served under the modules-repository of a site. */
    public static List<GqlGitModule> list(String siteKey) {
        return ForgeSiteAccess.executeAsSystemForSite(siteKey, PERMISSION, "Git module listing failed for site ", session -> {
            JCRNodeWrapper repository = modulesRepository(session, siteKey);
            List<GqlGitModule> result = new ArrayList<>();
            for (JCRNodeWrapper mount : session.getNode(GitModuleRegistrations.MOUNTS_PATH).getNodes()) {
                if (mount.isNodeType(GitStoreProviderFactory.NODE_TYPE) && GitModuleRegistrations.targets(mount, repository)) {
                    result.add(describe(mount, repository.getPath()));
                }
            }
            return result;
        });
    }

    private static JCRNodeWrapper modulesRepository(JCRSessionWrapper session, String siteKey) throws RepositoryException {
        String path = SITES_PATH + siteKey + FileSystem.SEPARATOR + MODULES_REPOSITORY;
        if (!session.nodeExists(path)) {
            throw new RepositoryException("The site " + siteKey + " has no modules repository");
        }
        return session.getNode(path);
    }

    private static GqlGitModule describe(JCRNodeWrapper mount, String repositoryPath) throws RepositoryException {
        String name = StringUtils.removeEnd(mount.getName(), JCRMountPointNode.MOUNT_SUFFIX);
        String status = mount instanceof JCRMountPointNode ? ((JCRMountPointNode) mount).getMountStatus().name() : "unknown";
        return new GqlGitModule(name,
                mount.getProperty(GitStoreProviderFactory.P_GIT_URL).getString(),
                GitModuleRegistrations.registeredBy(mount),
                repositoryPath + "/" + name, status);
    }

    private static GitStoreSettings settings() {
        GitStoreSettings settings = BundleUtils.getOsgiService(GitStoreSettings.class, null);
        if (settings == null) {
            throw new StoreManifestException("GitStoreSettings is not available");
        }
        return settings;
    }
}
