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

import java.util.ArrayList;
import java.util.List;
import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.modules.forge.validation.ModuleArtifact;
import org.jahia.modules.forge.validation.ValidationRecord;
import org.jahia.modules.forge.validation.ValidationResult;
import org.jahia.modules.forge.validation.ValidationService;
import org.jahia.modules.forge.validation.ValidationStatus;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.sites.JahiaSitesService;

/**
 * The GraphQL side of the validation of the versions (docs/validation.md): the developer who owns
 * a module requests the validation of a version and reads its record; a store administrator,
 * who holds {@code jahiaForgeModerateModule} on the site, lists every record, answers a check
 * that waits for a human, and invalidates a version.
 */
public final class ValidationExtension {

    static final String PERMISSION_MODERATE = "jahiaForgeModerateModule";
    static final String MODULES_REPOSITORY = "contents/modules-repository";
    private static final String SITES_PATH = JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR;

    private ValidationExtension() {
    }

    /** The record of a version, or null; for the owner of the module or an administrator. */
    public static GqlValidation validation(String siteKey, String moduleName, String version) {
        try {
            requireOwnerOrModerator(siteKey, moduleName);
            ValidationRecord record = service().read(siteKey, moduleName, version);
            return record == null ? null : new GqlValidation(record);
        } catch (RepositoryException e) {
            throw new ForgeSettingsException("Cannot read the validation of " + moduleName + " " + version, e);
        }
    }

    /** Every record of the site, the newest request first, for an administrator; a status narrows the list. */
    public static List<GqlValidation> validations(String siteKey, String status) {
        try {
            requireModerator(siteKey);
            ValidationStatus wanted = ValidationStatus.of(status);
            List<GqlValidation> result = new ArrayList<>();
            for (ValidationRecord record : service().list(siteKey)) {
                if (wanted == null || record.getStatus() == wanted) {
                    result.add(new GqlValidation(record));
                }
            }
            return result;
        } catch (RepositoryException e) {
            throw new ForgeSettingsException("Cannot list the validations of " + siteKey, e);
        }
    }

    /** Validate a version again, for the owner of the module or an administrator. */
    public static GqlValidation request(String siteKey, String moduleName, String version) {
        try {
            JCRNodeWrapper module = requireOwnerOrModerator(siteKey, moduleName);
            ModuleArtifact artifact = artifactOf(siteKey, module, version);
            return new GqlValidation(service().request(artifact, currentUser()));
        } catch (RepositoryException e) {
            throw new ForgeSettingsException("Cannot validate " + moduleName + " " + version, e);
        }
    }

    /** Answer a check that waits for a human, as an administrator. */
    public static GqlValidation answer(String siteKey, String moduleName, String version, String checkKey, String answer) {
        try {
            requireModerator(siteKey);
            ValidationResult result = ValidationResult.parseAnswer(answer);
            return new GqlValidation(service().answer(siteKey, moduleName, version, checkKey, result, currentUser()));
        } catch (RepositoryException e) {
            throw new ForgeSettingsException("Cannot answer the check " + checkKey + " of " + moduleName + " " + version, e);
        } catch (IllegalArgumentException e) {
            throw new ForgeSettingsException(e.getMessage(), e);
        }
    }

    /** Reject a version whatever its checks say, as an administrator, with the reason. */
    public static GqlValidation invalidate(String siteKey, String moduleName, String version, String reason) {
        try {
            requireModerator(siteKey);
            return new GqlValidation(service().invalidate(siteKey, moduleName, version, reason, currentUser()));
        } catch (RepositoryException e) {
            throw new ForgeSettingsException("Cannot invalidate " + moduleName + " " + version, e);
        }
    }

    /** The version of a module as the validation sees it: its coordinates and where its archive is. */
    static ModuleArtifact artifactOf(String siteKey, JCRNodeWrapper module, String version) throws RepositoryException {
        String groupId = module.hasProperty("groupId") ? module.getProperty("groupId").getString() : null;
        String sourceUrl = module.hasProperty("codeRepository") ? StringUtils.trimToNull(module.getProperty("codeRepository").getString()) : null;
        String artifactUrl = null;
        for (JCRNodeWrapper child : module.getNodes()) {
            if (child.isNodeType("jnt:forgeModuleVersion") && version.equals(child.hasProperty("versionNumber")
                    ? child.getProperty("versionNumber").getString() : child.getName())) {
                artifactUrl = artifactUrlOf(child);
                break;
            }
        }
        if (artifactUrl == null) {
            throw new ForgeSettingsException("The module " + module.getName() + " has no version " + version, null);
        }
        if (!artifactUrl.contains("://") && groupId != null && !artifactUrl.startsWith("/files/")) {
            artifactUrl = "/modules/mavenproxy/" + siteKey + "/" + groupId.replace('.', '/') + "/" + module.getName()
                    + "/" + version + "/" + module.getName() + "-" + version + ".jar";
        }
        return new ModuleArtifact(siteKey, module.getName(), version, groupId, artifactUrl, sourceUrl);
    }

    /** The declared URL of a git-backed version, the attached file of an uploaded one, or empty for the proxy. */
    static String artifactUrlOf(JCRNodeWrapper versionNode) throws RepositoryException {
        if (!versionNode.getProvider().isDefault() && versionNode.hasProperty("url")) {
            return versionNode.getProperty("url").getString();
        }
        for (JCRNodeWrapper file : versionNode.getNodes()) {
            if (file.isNodeType("jnt:file")) {
                return "/files/live" + file.getPath();
            }
        }
        return "";
    }

    private static JCRNodeWrapper requireOwnerOrModerator(String siteKey, String moduleName) throws RepositoryException {
        ForgeSettingsMutationExtension.validateSiteKey(siteKey);
        JCRSessionWrapper session = JCRSessionFactory.getInstance().getCurrentUserSession();
        String sitePath = SITES_PATH + siteKey;
        if (!session.nodeExists(sitePath)) {
            throw new AccessDeniedException(siteKey);
        }
        String modulePath = sitePath + FileSystem.SEPARATOR + MODULES_REPOSITORY + FileSystem.SEPARATOR + moduleName;
        if (!session.nodeExists(modulePath)) {
            throw new ForgeSettingsException("The site " + siteKey + " has no module " + moduleName, null);
        }
        JCRNodeWrapper module = session.getNode(modulePath);
        boolean moderator = session.getNode(sitePath).hasPermission(PERMISSION_MODERATE);
        boolean owner = module.hasProperty("jcr:createdBy") && currentUser().equals(module.getProperty("jcr:createdBy").getString());
        if (!moderator && !owner && !module.hasPermission("jcr:write")) {
            throw new AccessDeniedException("Only the owner of " + moduleName + " or a store administrator may read its validation");
        }
        return module;
    }

    private static void requireModerator(String siteKey) throws RepositoryException {
        ForgeSettingsMutationExtension.validateSiteKey(siteKey);
        JCRSessionWrapper session = JCRSessionFactory.getInstance().getCurrentUserSession();
        String sitePath = SITES_PATH + siteKey;
        if (!session.nodeExists(sitePath) || !session.getNode(sitePath).hasPermission(PERMISSION_MODERATE)) {
            throw new AccessDeniedException(PERMISSION_MODERATE);
        }
    }

    private static String currentUser() {
        return JCRSessionFactory.getInstance().getCurrentUser() == null ? "" : JCRSessionFactory.getInstance().getCurrentUser().getUsername();
    }

    private static ValidationService service() {
        ValidationService service = BundleUtils.getOsgiService(ValidationService.class, null);
        if (service == null) {
            throw new ForgeSettingsException("ValidationService is not available", null);
        }
        return service;
    }
}
