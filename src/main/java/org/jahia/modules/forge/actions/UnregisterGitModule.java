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

import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.modules.forge.git.GitModuleRegistrations;
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
 * Removes a git-backed module from the store: {@code <module>.unregisterGitModule.do}, posted over
 * XMLHttpRequest from the "Remove from the store" button of the module page, as
 * docs/git-backed-modules.md states. The registration goes, the repository is untouched. The
 * caller holds {@code jahiaForgeUploadModule} on the modules repository, and is the developer who
 * registered the module or holds {@code siteAdminForgeSettings} on the site. Failures answer HTTP
 * 200 with {@code {error}}, as the registration does; success answers {@code {module}}.
 */
@Component(service = Action.class, immediate = true)
public class UnregisterGitModule extends Action {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnregisterGitModule.class);
    private static final String ERR_NOT_GIT = "forge.gitModule.error.not.git";
    private static final String ERR_NOT_OWNER = "forge.gitModule.error.not.owner";

    @Activate
    public void activate() {
        setName("unregisterGitModule");
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
        if (!repository.hasPermission(RefreshGitModule.PERMISSION_UPLOAD)) {
            throw new javax.jcr.AccessDeniedException(RefreshGitModule.PERMISSION_UPLOAD);
        }
        final String name = module.getName();
        final String username = session.getUser().getUsername();
        final boolean admin = renderContext.getSite().hasPermission(RefreshGitModule.PERMISSION_ADMIN);
        final String repositoryPath = repository.getPath();

        // The mount point lives under /mounts, which the developer cannot write: remove it as system.
        final String outcome = JCRTemplate.getInstance().doExecuteWithSystemSession(systemSession -> {
            JCRNodeWrapper mount = GitModuleRegistrations.mountOf(systemSession, systemSession.getNode(repositoryPath), name);
            if (mount == null) {
                return ERR_NOT_GIT;
            }
            if (!admin && !username.equals(GitModuleRegistrations.registeredBy(mount))) {
                return ERR_NOT_OWNER;
            }
            // JCRMountPointNode.remove() stops the provider before the node goes.
            mount.remove();
            systemSession.save();
            return null;
        });
        if (outcome != null) {
            LOGGER.warn("UnregisterGitModule: refused {} for user {}: {}", name, username, outcome);
            return GitModuleActions.error(session, outcome);
        }
        CacheHelper.flushOutputCachesForPath(renderContext.getSite().getPath(), true);
        LOGGER.info("UnregisterGitModule: {} removed the git-backed module {} from the store", username, name);
        return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject().put("module", name));
    }
}
