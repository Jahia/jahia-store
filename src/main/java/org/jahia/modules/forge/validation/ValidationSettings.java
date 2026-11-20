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

import java.util.Map;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The configuration of the validation of the versions, from
 * {@code org.jahia.modules.forge.validation.cfg}: whether a version must be approved before it
 * is published.
 */
@Component(service = ValidationSettings.class, immediate = true, configurationPid = ValidationSettings.PID)
public class ValidationSettings {

    static final String PID = "org.jahia.modules.forge.validation";
    static final String P_REQUIRE_APPROVAL = "requireApproval";

    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationSettings.class);

    private volatile boolean requireApproval = true;

    @Activate
    public void activate(Map<String, Object> properties) {
        apply(properties);
    }

    @Modified
    public void modified(Map<String, Object> properties) {
        apply(properties);
    }

    void apply(Map<String, Object> properties) {
        Object value = properties == null ? null : properties.get(P_REQUIRE_APPROVAL);
        requireApproval = value == null || String.valueOf(value).isBlank() || Boolean.parseBoolean(String.valueOf(value).trim());
        LOGGER.info("Validation of the versions: a version {} approved before it is published",
                requireApproval ? "must be" : "need not be");
    }

    /** True when a version reaches the public once approved only, which is the default. */
    public boolean isApprovalRequired() {
        return requireApproval;
    }
}
