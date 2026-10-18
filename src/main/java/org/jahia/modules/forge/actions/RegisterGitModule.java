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
import org.apache.commons.lang.StringUtils;
import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.modules.forge.git.GitModule;
import org.jahia.modules.forge.git.GitModuleRegistrations;
import org.jahia.modules.forge.git.GitStoreSettings;
import org.jahia.modules.forge.git.StoreManifestException;
import org.jahia.modules.forge.git.VersionTags;
import org.jahia.services.cache.CacheHelper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.jahia.utils.i18n.Messages;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers a git repository as a module of the store, for a store developer, from the
 * storefront: {@code <modules-repository>.registerGitModule.do}, posted over XMLHttpRequest with
 * {@code gitUrl}, and optionally {@code tagPattern} and {@code branch}. The repository is read
 * first, and one that fails the contract of docs/git-backed-modules.md is refused with the
 * reason. The mount point node lives under {@code /mounts}, which a developer cannot write, so
 * it is created in a system session once the caller passed the permission of this action.
 * Failures answer HTTP 200 with {@code {error}}, as the upload action does; success answers
 * {@code {module, path}}.
 */
@Component(service = Action.class, immediate = true)
public class RegisterGitModule extends Action {

    static final String PARAM_GIT_URL = "gitUrl";
    static final String PARAM_TAG_PATTERN = "tagPattern";
    static final String PARAM_BRANCH = "branch";
    static final String MODULES_REPOSITORY = "modules-repository";

    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterGitModule.class);
    private static final String RESOURCES_JAHIA_STORE = "resources.jahia-store";
    private static final String ERROR = "error";
    private static final String ERR_WRONG_TARGET = "forge.gitModule.error.wrong.target";
    private static final String ERR_INVALID_URL = "forge.gitModule.error.invalid.url";
    private static final String ERR_INVALID_PATTERN = "forge.gitModule.error.invalid.pattern";
    private static final String ERR_REPOSITORY = "forge.gitModule.error.repository";
    private static final String ERR_ALREADY_EXISTS = "forge.gitModule.error.already.exists";
    /** The longest reason echoed to the browser; the whole message is in the log. */
    private static final int MAX_REASON_LENGTH = 250;

    @Reference
    private GitStoreSettings settings;

    @Activate
    public void activate() {
        setName("registerGitModule");
        setRequireAuthenticatedUser(true);
        setRequiredPermission("jahiaForgeUploadModule");
        setRequiredMethods("POST");
    }

    @Override
    public ActionResult doExecute(HttpServletRequest request, RenderContext renderContext, Resource resource,
                                  JCRSessionWrapper session, Map<String, List<String>> parameters, URLResolver urlResolver)
            throws Exception {
        final JCRNodeWrapper target = resource.getNode();
        if (!MODULES_REPOSITORY.equals(target.getName()) || !target.isNodeType("jnt:contentFolder")) {
            return error(session, ERR_WRONG_TARGET);
        }
        final String url;
        try {
            url = GitModuleRegistrations.validateGitUrl(getParameter(parameters, PARAM_GIT_URL));
        } catch (StoreManifestException e) {
            return error(session, ERR_INVALID_URL);
        }
        final String tagPattern = StringUtils.trimToNull(getParameter(parameters, PARAM_TAG_PATTERN));
        final String branch = StringUtils.trimToNull(getParameter(parameters, PARAM_BRANCH));
        try {
            VersionTags.compile(tagPattern);
        } catch (StoreManifestException e) {
            return error(session, ERR_INVALID_PATTERN);
        }

        final GitModule module;
        try {
            module = GitModuleRegistrations.read(settings, url, tagPattern, branch);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("RegisterGitModule: refused {} for user {}: {}", ActionSecurityUtils.sanitizeForLog(url),
                    session.getUser().getUsername(), e.getMessage());
            return errorWithReason(session, ERR_REPOSITORY, e.getMessage());
        }
        final String name = module.getManifest().getName();
        final String username = session.getUser().getUsername();
        final String repositoryPath = target.getPath();

        final boolean created = JCRTemplate.getInstance().doExecuteWithSystemSession(systemSession -> {
            JCRNodeWrapper repository = systemSession.getNode(repositoryPath);
            if (GitModuleRegistrations.exists(systemSession, repository, name)) {
                return Boolean.FALSE;
            }
            GitModuleRegistrations.register(systemSession, repository, url, tagPattern, branch, username, name);
            return Boolean.TRUE;
        });
        if (!created) {
            return errorWithReason(session, ERR_ALREADY_EXISTS, name);
        }
        // The storefront lists and renders modules from cached fragments; the new module must
        // show on the page the browser reloads right after this answer.
        CacheHelper.flushOutputCachesForPath(renderContext.getSite().getPath(), true);
        LOGGER.info("RegisterGitModule: {} registered the git-backed module {} from {}", username, name, ActionSecurityUtils.sanitizeForLog(url));
        return new ActionResult(HttpServletResponse.SC_OK, null,
                new JSONObject().put("module", name).put("path", repositoryPath + "/" + name));
    }

    private static ActionResult error(JCRSessionWrapper session, String key) throws Exception {
        return new ActionResult(HttpServletResponse.SC_OK, null,
                new JSONObject().put(ERROR, Messages.get(RESOURCES_JAHIA_STORE, key, session.getLocale())));
    }

    private static ActionResult errorWithReason(JCRSessionWrapper session, String key, String reason) throws Exception {
        String text = StringUtils.abbreviate(StringUtils.defaultString(reason), MAX_REASON_LENGTH);
        return new ActionResult(HttpServletResponse.SC_OK, null,
                new JSONObject().put(ERROR, Messages.getWithArgs(RESOURCES_JAHIA_STORE, key, session.getLocale(), text)));
    }
}
