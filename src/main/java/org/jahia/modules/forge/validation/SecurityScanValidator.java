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

import org.osgi.service.component.annotations.Component;

/**
 * The security check, run by the Jahia security scan on the archive. Until a service runs it, an
 * administrator gives the prompt to the scan and pastes its answer.
 */
@Component(service = ModuleValidator.class, immediate = true, property = "forge.validator=security")
public class SecurityScanValidator extends PromptValidator {

    public static final String KEY = "security";

    public SecurityScanValidator() {
        super("/validation/security-scan-prompt.md");
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public String getLabel() {
        return "Security";
    }

    @Override
    public String getDescription() {
        return "A security scan of the code and of the embedded libraries for known vulnerabilities.";
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
