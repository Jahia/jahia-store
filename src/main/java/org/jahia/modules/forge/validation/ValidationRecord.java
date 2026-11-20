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
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** The validation of one version as the store keeps it: its status and one check per validator. Immutable. */
public final class ValidationRecord {

    /** The state of one check of the version. */
    public static final class Check {
        private final String key;
        private final String label;
        private final ValidationStatus status;
        private final List<String> comments;
        private final String prompt;
        private final String updatedBy;
        private final Date updatedAt;

        public Check(String key, String label, ValidationStatus status, List<String> comments, String prompt, String updatedBy, Date updatedAt) {
            this.key = key;
            this.label = label;
            this.status = status;
            this.comments = comments == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(comments));
            this.prompt = prompt;
            this.updatedBy = updatedBy;
            this.updatedAt = updatedAt;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public ValidationStatus getStatus() {
            return status;
        }

        public List<String> getComments() {
            return comments;
        }

        /** The prompt of a check waiting for a human, or null. */
        public String getPrompt() {
            return prompt;
        }

        public String getUpdatedBy() {
            return updatedBy;
        }

        public Date getUpdatedAt() {
            return updatedAt;
        }
    }

    private final String siteKey;
    private final String moduleName;
    private final String version;
    private final ValidationStatus status;
    private final String artifactUrl;
    private final String requestedBy;
    private final Date requestedAt;
    private final String reason;
    private final List<Check> checks;

    public ValidationRecord(String siteKey, String moduleName, String version, ValidationStatus status, String artifactUrl,
                            String requestedBy, Date requestedAt, String reason, List<Check> checks) {
        this.siteKey = siteKey;
        this.moduleName = moduleName;
        this.version = version;
        this.status = status;
        this.artifactUrl = artifactUrl;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.reason = reason;
        this.checks = checks == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(checks));
    }

    public String getSiteKey() {
        return siteKey;
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getVersion() {
        return version;
    }

    public ValidationStatus getStatus() {
        return status;
    }

    public String getArtifactUrl() {
        return artifactUrl;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Date getRequestedAt() {
        return requestedAt;
    }

    /** The reason an administrator gave when invalidating the version, or null. */
    public String getReason() {
        return reason;
    }

    public List<Check> getChecks() {
        return checks;
    }

    public Check check(String key) {
        for (Check check : checks) {
            if (check.getKey().equals(key)) {
                return check;
            }
        }
        return null;
    }
}
