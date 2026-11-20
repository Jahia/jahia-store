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

import java.util.Collection;

/**
 * The verdict of a check, or of a version as a whole (docs/validation.md). A version reaches the
 * public once it is {@link #APPROVED}.
 */
public enum ValidationStatus {
    /** Not run yet. */
    PENDING,
    APPROVED,
    /** Waiting for a human to review: a check could not decide, or needs an answer. */
    REVIEW,
    REJECTED;

    /** The key the records and the API carry. */
    public String key() {
        return name().toLowerCase();
    }

    /** The status of a key, in any case; null for an unknown key. */
    public static ValidationStatus of(String key) {
        if (key == null) {
            return null;
        }
        for (ValidationStatus status : values()) {
            if (status.name().equalsIgnoreCase(key.trim())) {
                return status;
            }
        }
        return null;
    }

    /**
     * The status of a version from the status of its checks: rejected when one check rejects,
     * else waiting for review when one check waits, else pending when one check has not run, else
     * approved. A version with no check is pending.
     */
    public static ValidationStatus overall(Collection<ValidationStatus> checks) {
        if (checks == null || checks.isEmpty()) {
            return PENDING;
        }
        ValidationStatus result = APPROVED;
        for (ValidationStatus status : checks) {
            if (status == REJECTED) {
                return REJECTED;
            }
            if (status == REVIEW || (status == PENDING && result != REVIEW)) {
                result = status;
            }
        }
        return result;
    }
}
