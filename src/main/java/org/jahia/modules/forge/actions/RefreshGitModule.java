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
package org.jahia.modules.forge.actions;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.modules.forge.git.GitModule;
import org.jahia.modules.forge.git.GitModuleRegistrations;
import org.jahia.modules.forge.git.GitStoreDataSource;
import org.jahia.modules.forge.git.StoreManifestException;
import org.jahia.services.cache.CacheHelper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the repository of a git-backed module again, now: {@code <module>.refreshGitModule.do},
 * as docs/git-backed-modules.md states. The storefront calls it over XMLHttpRequest from the
 * "Refresh" button of the module page, which is the one way through the CSRF guard; a process
 * outside the store calls the GraphQL mutation instead.
 *
 * <p>The caller holds {@code jahiaForgeUploadModule} on the modules repository, and is the
 * developer who registered the module or holds {@code siteAdminForgeSettings} on the site. A
 * repository that cannot be read keeps the previous reading. Failures answer HTTP 200 with
 * {@code {error}}, as the registration does; success answers {@code {module, versions}}.
 */
@Component(service = Action.class, immediate = true)
public class RefreshGitModule extends Action {

    static final String PERMISSION_UPLOAD = "jahiaForgeUploadModule";
    static final String PERMISSION_ADMIN = "siteAdminForgeSettings";

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshGitModule.class);
    private static final String ERR_NOT_GIT = "forge.gitModule.error.not.git";
    private static final String ERR_NOT_OWNER = "forge.gitModule.error.not.owner";
    private static final String ERR_REFRESH = "forge.gitModule.error.refresh";

    @Activate
    public void activate() {
        setName("refreshGitModule");
        setRequireAuthenticatedUser(true);
        // The target is an external node; the permission is checked on the modules repository below.
        setRequiredMethods("POST");
    }

    @Override
    public ActionResult doExecute(HttpServletRequest request, RenderContext renderContext, Resource resource,
                                  JCRSessionWrapper session, Map<String, List<String>> parameters, URLResolver urlResolver)
            throws Exception {
        final JCRNodeWrapper module = resource.getNode();
        if (!module.isNodeType("jnt:forgeModule") || module.getProvider().isDefault()) {
            return GitModuleActions.error(session, ERR_NOT_GIT);
        }
        final JCRNodeWrapper repository = module.getParent();
        if (!repository.hasPermission(PERMISSION_UPLOAD)) {
            throw new javax.jcr.AccessDeniedException(PERMISSION_UPLOAD);
        }
        final String name = module.getName();
        final String username = session.getUser().getUsername();
        final boolean admin = renderContext.getSite().hasPermission(PERMISSION_ADMIN);
        final String repositoryPath = repository.getPath();

        // The mount point lives under /mounts, which the developer cannot read: resolve it as system.
        final Registration registration = JCRTemplate.getInstance().doExecuteWithSystemSession(systemSession -> {
            JCRNodeWrapper mount = GitModuleRegistrations.mountOf(systemSession, systemSession.getNode(repositoryPath), name);
            return mount == null ? null
                    : new Registration(GitModuleRegistrations.registeredBy(mount), GitModuleRegistrations.dataSourceOf(mount));
        });
        if (registration == null || registration.dataSource == null) {
            return GitModuleActions.error(session, ERR_NOT_GIT);
        }
        if (!admin && !username.equals(registration.registeredBy)) {
            LOGGER.warn("RefreshGitModule: {} is not the developer who registered {} ({})", username, name, registration.registeredBy);
            return GitModuleActions.error(session, ERR_NOT_OWNER);
        }
        final GitStoreDataSource dataSource = registration.dataSource;

        final GitModule reading;
        try {
            reading = dataSource.refresh();
        } catch (IOException | StoreManifestException e) {
            LOGGER.warn("RefreshGitModule: {} could not refresh {}: {}", username, name, e.getMessage());
            return GitModuleActions.errorWithReason(session, ERR_REFRESH, e.getMessage());
        }
        CacheHelper.flushOutputCachesForPath(renderContext.getSite().getPath(), true);
        LOGGER.info("RefreshGitModule: {} refreshed the git-backed module {}: {} versions", username, name, reading.getVersions().size());
        return new ActionResult(HttpServletResponse.SC_OK, null,
                new JSONObject().put("module", name).put("versions", reading.getVersions().size()));
    }

    /** What the system session reads of the mount point: who registered it, and its data source once mounted. */
    private static final class Registration {
        private final String registeredBy;
        private final GitStoreDataSource dataSource;

        private Registration(String registeredBy, GitStoreDataSource dataSource) {
            this.registeredBy = registeredBy;
            this.dataSource = dataSource;
        }
    }
}
