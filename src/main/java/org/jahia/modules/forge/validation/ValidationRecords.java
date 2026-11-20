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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.sites.JahiaSitesService;

/**
 * The validation records in the JCR (docs/validation.md): one {@code jnt:forgeValidation} per
 * version under {@code <site>/contents/validations/<module>/<version>}, with one
 * {@code jnt:forgeValidationCheck} child per check. The store writes a record in both workspaces,
 * so the storefront reads it in the live workspace beside the version.
 */
public final class ValidationRecords {

    public static final String FOLDER = "validations";
    public static final String TYPE_RECORD = "jnt:forgeValidation";
    public static final String TYPE_CHECK = "jnt:forgeValidationCheck";
    private static final String CONTENT_FOLDER = "jnt:forgeValidationFolder";

    static final String P_STATUS = "status";
    static final String P_MODULE = "moduleName";
    static final String P_VERSION = "versionNumber";
    static final String P_ARTIFACT_URL = "artifactUrl";
    static final String P_REQUESTED_BY = "requestedBy";
    static final String P_REQUESTED_AT = "requestedAt";
    static final String P_REASON = "reason";
    static final String P_CHECK_KEY = "checkKey";
    static final String P_LABEL = "label";
    static final String P_COMMENTS = "comments";
    static final String P_PROMPT = "prompt";
    static final String P_UPDATED_BY = "updatedBy";
    static final String P_UPDATED_AT = "updatedAt";

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9._-]+$");

    private ValidationRecords() {
    }

    /** The path of the record of a version, whether it exists or not. */
    public static String path(String siteKey, String moduleName, String version) {
        for (String segment : new String[]{siteKey, moduleName, version}) {
            if (segment == null || !SAFE_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Not a name of a site, a module or a version: " + segment);
            }
        }
        return folderPath(siteKey) + "/" + moduleName + "/" + version;
    }

    static String folderPath(String siteKey) {
        return JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR + siteKey + "/contents/" + FOLDER;
    }

    /** The record of a version, or null when the version was never validated. */
    public static ValidationRecord read(JCRSessionWrapper session, String siteKey, String moduleName, String version) throws RepositoryException {
        String path = path(siteKey, moduleName, version);
        return session.nodeExists(path) ? read(session.getNode(path)) : null;
    }

    public static ValidationRecord read(JCRNodeWrapper node) throws RepositoryException {
        List<ValidationRecord.Check> checks = new ArrayList<>();
        for (JCRNodeWrapper child : node.getNodes()) {
            if (child.isNodeType(TYPE_CHECK)) {
                checks.add(new ValidationRecord.Check(string(child, P_CHECK_KEY), string(child, P_LABEL),
                        ValidationStatus.of(string(child, P_STATUS)), strings(child, P_COMMENTS), string(child, P_PROMPT),
                        string(child, P_UPDATED_BY), date(child, P_UPDATED_AT)));
            }
        }
        String siteKey = StringUtils.substringBetween(node.getPath(), JahiaSitesService.SITES_JCR_PATH + "/", "/");
        return new ValidationRecord(siteKey, string(node, P_MODULE), string(node, P_VERSION),
                ValidationStatus.of(string(node, P_STATUS)), string(node, P_ARTIFACT_URL), string(node, P_REQUESTED_BY),
                date(node, P_REQUESTED_AT), string(node, P_REASON), checks);
    }

    /** Every record of a site, the newest request first. */
    public static List<ValidationRecord> list(JCRSessionWrapper session, String siteKey) throws RepositoryException {
        List<ValidationRecord> records = new ArrayList<>();
        String folder = folderPath(siteKey);
        if (!session.nodeExists(folder)) {
            return records;
        }
        for (JCRNodeWrapper module : session.getNode(folder).getNodes()) {
            for (JCRNodeWrapper version : module.getNodes()) {
                if (version.isNodeType(TYPE_RECORD)) {
                    records.add(read(version));
                }
            }
        }
        records.sort((a, b) -> {
            Date da = a.getRequestedAt() == null ? new Date(0) : a.getRequestedAt();
            Date db = b.getRequestedAt() == null ? new Date(0) : b.getRequestedAt();
            return db.compareTo(da);
        });
        return records;
    }

    /**
     * Create or replace the record of a version with its checks all pending, and return its node.
     * The caller saves.
     */
    public static JCRNodeWrapper create(JCRSessionWrapper session, ModuleArtifact artifact, String requestedBy,
                                        List<ModuleValidator> validators) throws RepositoryException {
        String folder = folderPath(artifact.getSiteKey());
        JCRNodeWrapper contents = session.getNode(JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR + artifact.getSiteKey() + "/contents");
        JCRNodeWrapper validations = session.nodeExists(folder) ? session.getNode(folder) : contents.addNode(FOLDER, CONTENT_FOLDER);
        JCRNodeWrapper module = validations.hasNode(artifact.getModuleName()) ? validations.getNode(artifact.getModuleName())
                : validations.addNode(artifact.getModuleName(), CONTENT_FOLDER);
        if (module.hasNode(artifact.getVersion())) {
            module.getNode(artifact.getVersion()).remove();
        }
        JCRNodeWrapper record = module.addNode(artifact.getVersion(), TYPE_RECORD);
        record.setProperty(P_MODULE, artifact.getModuleName());
        record.setProperty(P_VERSION, artifact.getVersion());
        record.setProperty(P_ARTIFACT_URL, StringUtils.defaultString(artifact.getArtifactUrl()));
        record.setProperty(P_REQUESTED_BY, StringUtils.defaultString(requestedBy));
        record.setProperty(P_REQUESTED_AT, Calendar.getInstance());
        record.setProperty(P_STATUS, ValidationStatus.PENDING.key());
        for (ModuleValidator validator : validators) {
            JCRNodeWrapper check = record.addNode(validator.getKey(), TYPE_CHECK);
            check.setProperty(P_CHECK_KEY, validator.getKey());
            check.setProperty(P_LABEL, validator.getLabel());
            check.setProperty(P_STATUS, ValidationStatus.PENDING.key());
        }
        return record;
    }

    /** Write the result of a check and the status of the version that follows. The caller saves. */
    public static void writeCheck(JCRNodeWrapper record, String checkKey, String label, ValidationResult result, String by)
            throws RepositoryException {
        JCRNodeWrapper check = record.hasNode(checkKey) ? record.getNode(checkKey) : record.addNode(checkKey, TYPE_CHECK);
        check.setProperty(P_CHECK_KEY, checkKey);
        if (label != null) {
            check.setProperty(P_LABEL, label);
        }
        check.setProperty(P_STATUS, result.getStatus().key());
        check.setProperty(P_COMMENTS, result.getComments().toArray(new String[0]));
        check.setProperty(P_PROMPT, StringUtils.defaultString(result.getPrompt()));
        check.setProperty(P_UPDATED_BY, StringUtils.defaultString(by));
        check.setProperty(P_UPDATED_AT, Calendar.getInstance());
        writeOverall(record);
    }

    /** Set the status of the version from its checks. The caller saves. */
    public static ValidationStatus writeOverall(JCRNodeWrapper record) throws RepositoryException {
        List<ValidationStatus> statuses = new ArrayList<>();
        for (JCRNodeWrapper child : record.getNodes()) {
            if (child.isNodeType(TYPE_CHECK)) {
                statuses.add(ValidationStatus.of(string(child, P_STATUS)));
            }
        }
        ValidationStatus overall = ValidationStatus.overall(statuses);
        record.setProperty(P_STATUS, overall.key());
        return overall;
    }

    /** Reject the version whatever its checks say, with the reason of the administrator. The caller saves. */
    public static void writeInvalidation(JCRNodeWrapper record, String reason) throws RepositoryException {
        record.setProperty(P_STATUS, ValidationStatus.REJECTED.key());
        record.setProperty(P_REASON, StringUtils.defaultString(reason));
    }

    private static String string(JCRNodeWrapper node, String property) throws RepositoryException {
        return node.hasProperty(property) ? StringUtils.trimToNull(node.getProperty(property).getString()) : null;
    }

    private static Date date(JCRNodeWrapper node, String property) throws RepositoryException {
        return node.hasProperty(property) ? node.getProperty(property).getDate().getTime() : null;
    }

    private static List<String> strings(JCRNodeWrapper node, String property) throws RepositoryException {
        List<String> values = new ArrayList<>();
        if (node.hasProperty(property)) {
            for (Value value : node.getProperty(property).getValues()) {
                values.add(value.getString());
            }
        }
        return values;
    }
}
