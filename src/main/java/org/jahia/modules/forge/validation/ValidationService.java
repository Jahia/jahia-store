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
package org.jahia.modules.forge.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.jcr.RepositoryException;
import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.services.cache.CacheHelper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The validation of the versions of the modules (docs/validation.md): runs every registered
 * {@link ModuleValidator} on a version, keeps the record of each check beside the version, takes
 * the answer of a human for a check that waits for one, and says whether a version may reach the
 * public. Every write runs in a system session and is published, and the statuses are cached.
 */
@Component(service = ValidationService.class, immediate = true)
public class ValidationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationService.class);

    private final List<ModuleValidator> validators = new CopyOnWriteArrayList<>();
    /** The status of each version this instance read or wrote, by {@code site/module/version}. */
    private final Map<String, ValidationStatus> statuses = new ConcurrentHashMap<>();

    @Reference
    private ValidationSettings settings;

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void addValidator(ModuleValidator validator) {
        validators.add(validator);
        LOGGER.info("Validation check registered: {} ({})", validator.getKey(), validator.getLabel());
    }

    public void removeValidator(ModuleValidator validator) {
        validators.remove(validator);
    }

    /** The registered checks, in registration order. */
    public List<ModuleValidator> getValidators() {
        return new ArrayList<>(validators);
    }

    public ValidationSettings getSettings() {
        return settings;
    }

    /**
     * Validate a version: a new record with one check per validator, then every validator run,
     * each failure read as a check waiting for review. Returns the record.
     */
    public ValidationRecord request(ModuleArtifact artifact, String requestedBy) throws RepositoryException {
        List<ModuleValidator> checks = getValidators();
        Map<String, ValidationResult> results = new LinkedHashMap<>();
        for (ModuleValidator validator : checks) {
            ValidationResult result;
            try {
                result = validator.validate(artifact);
            } catch (RuntimeException e) {
                LOGGER.warn("The check {} failed on {} {}: {}", validator.getKey(), artifact.getModuleName(), artifact.getVersion(), e.getMessage());
                result = ValidationResult.review(null, "The check failed: " + e.getMessage());
            }
            results.put(validator.getKey(), result);
        }
        ValidationRecord record = writeInBothWorkspaces(session -> {
            JCRNodeWrapper node = ValidationRecords.create(session, artifact, requestedBy, checks);
            for (ModuleValidator validator : checks) {
                ValidationRecords.writeCheck(node, validator.getKey(), validator.getLabel(), results.get(validator.getKey()), validator.getKey());
            }
            return node;
        });
        remember(record);
        flush(artifact.getSiteKey());
        LOGGER.info("Validation of {} {} requested by {}: {}", artifact.getModuleName(), artifact.getVersion(), requestedBy, record.getStatus());
        return record;
    }

    /** Validate the versions of a module that were never validated, each once. */
    public void requestMissing(List<ModuleArtifact> artifacts, String requestedBy) {
        for (ModuleArtifact artifact : artifacts) {
            try {
                if (read(artifact.getSiteKey(), artifact.getModuleName(), artifact.getVersion()) == null) {
                    request(artifact, requestedBy);
                }
            } catch (RepositoryException e) {
                LOGGER.warn("Cannot validate {} {}: {}", artifact.getModuleName(), artifact.getVersion(), e.getMessage());
            }
        }
    }

    /**
     * Take the answer of a human for a check: the status and the comments it carries replace the
     * ones of the check, and the status of the version follows.
     *
     * @throws IllegalArgumentException when the version has no record or no such check
     */
    public ValidationRecord answer(String siteKey, String moduleName, String version, String checkKey, ValidationResult result, String by)
            throws RepositoryException {
        ValidationRecord record = writeInBothWorkspaces(session -> {
            String path = ValidationRecords.path(siteKey, moduleName, version);
            if (!session.nodeExists(path)) {
                throw new IllegalArgumentException("The version " + version + " of " + moduleName + " has no validation");
            }
            JCRNodeWrapper node = session.getNode(path);
            if (!node.hasNode(checkKey)) {
                throw new IllegalArgumentException("The version " + version + " of " + moduleName + " has no check " + checkKey);
            }
            ValidationRecords.writeCheck(node, checkKey, null, result, by);
            return node;
        });
        remember(record);
        flush(siteKey);
        LOGGER.info("Check {} of {} {} answered by {}: {}", checkKey, moduleName, version, by, result.getStatus());
        return record;
    }

    /**
     * Reject a version whatever its checks say, with a reason, and withdraw it from the public: a
     * local version is unpublished, a git-backed one stops being served as published.
     */
    public ValidationRecord invalidate(String siteKey, String moduleName, String version, String reason, String by) throws RepositoryException {
        ValidationRecord record = writeInBothWorkspaces(session -> {
            String path = ValidationRecords.path(siteKey, moduleName, version);
            JCRNodeWrapper node;
            if (session.nodeExists(path)) {
                node = session.getNode(path);
            } else {
                node = ValidationRecords.create(session, new ModuleArtifact(siteKey, moduleName, version, null, null, null), by, getValidators());
            }
            ValidationRecords.writeInvalidation(node, reason);
            unpublishLocalVersion(session, siteKey, moduleName, version);
            return node;
        });
        remember(record);
        flush(siteKey);
        LOGGER.info("Version {} of {} invalidated by {}: {}", version, moduleName, by, reason);
        return record;
    }

    /** The record of a version, or null when it was never validated. */
    public ValidationRecord read(String siteKey, String moduleName, String version) throws RepositoryException {
        ValidationRecord record = JCRTemplate.getInstance().doExecuteWithSystemSession(session ->
                ValidationRecords.read(session, siteKey, moduleName, version));
        if (record != null) {
            remember(record);
        }
        return record;
    }

    /** Every record of a site, the newest request first. */
    public List<ValidationRecord> list(String siteKey) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> ValidationRecords.list(session, siteKey));
    }

    /** The status of a version, pending when it was never validated. */
    public ValidationStatus status(String siteKey, String moduleName, String version) {
        String key = key(siteKey, moduleName, version);
        ValidationStatus status = statuses.get(key);
        if (status != null) {
            return status;
        }
        try {
            ValidationRecord record = read(siteKey, moduleName, version);
            status = record == null ? ValidationStatus.PENDING : record.getStatus();
        } catch (RepositoryException | IllegalArgumentException e) {
            LOGGER.debug("Cannot read the validation of {} {}: {}", moduleName, version, e.getMessage());
            status = ValidationStatus.PENDING;
        }
        statuses.put(key, status);
        return status;
    }

    /** True when the version may reach the public: approved, or approval not required. */
    public boolean mayPublish(String siteKey, String moduleName, String version) {
        return !settings.isApprovalRequired() || status(siteKey, moduleName, version) == ValidationStatus.APPROVED;
    }

    /** One write of a record, applied to a workspace; returns the node it wrote. */
    private interface RecordWrite {
        JCRNodeWrapper apply(JCRSessionWrapper session) throws RepositoryException;
    }

    /**
     * Apply a write in the default workspace, then the same write in the live one, each in a
     * system session, so the storefront reads the record beside the version whatever the rights
     * of the user who asked. The records are the store's own: no publication is involved.
     */
    private static ValidationRecord writeInBothWorkspaces(RecordWrite write) throws RepositoryException {
        ValidationRecord record = JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            JCRNodeWrapper node = write.apply(session);
            session.save();
            return ValidationRecords.read(node);
        });
        JCRTemplate.getInstance().doExecuteWithSystemSession(null, Constants.LIVE_WORKSPACE, session -> {
            write.apply(session);
            session.save();
            return null;
        });
        return record;
    }

    private void remember(ValidationRecord record) {
        statuses.put(key(record.getSiteKey(), record.getModuleName(), record.getVersion()), record.getStatus());
    }

    private static String key(String siteKey, String moduleName, String version) {
        return siteKey + "/" + moduleName + "/" + version;
    }

    /** The storefront renders versions from cached fragments; a verdict must show on the next page. */
    private static void flush(String siteKey) {
        CacheHelper.flushOutputCachesForPath(JahiaSitesService.SITES_JCR_PATH + "/" + siteKey, true);
    }

    /** Withdraw a local version from the public, in both workspaces; a git-backed one has no node to write. */
    private static void unpublishLocalVersion(JCRSessionWrapper session, String siteKey, String moduleName, String version) throws RepositoryException {
        String repository = JahiaSitesService.SITES_JCR_PATH + "/" + siteKey + "/contents/modules-repository";
        if (!session.nodeExists(repository)) {
            return;
        }
        JCRNodeWrapper module = session.getNode(repository).hasNode(moduleName) ? session.getNode(repository).getNode(moduleName) : null;
        if (module == null || !module.getProvider().isDefault()) {
            return;
        }
        for (JCRNodeWrapper child : module.getNodes()) {
            if (child.isNodeType("jnt:forgeModuleVersion") && version.equals(property(child, "versionNumber"))) {
                // The write runs in each workspace in turn, so the version is withdrawn from both.
                child.setProperty("published", false);
            }
        }
    }

    private static String property(JCRNodeWrapper node, String name) throws RepositoryException {
        return node.hasProperty(name) ? StringUtils.trimToNull(node.getProperty(name).getString()) : null;
    }
}
