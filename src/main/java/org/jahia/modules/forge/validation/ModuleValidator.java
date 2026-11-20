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

/**
 * One check of a version of a module, registered as an OSGi service: the store runs every
 * registered check on a version and keeps the result of each beside the version
 * (docs/validation.md). A check either decides by itself, or asks a human to decide through a
 * prompt, which the store administration shows with a box for the answer.
 */
public interface ModuleValidator {

    /** The key of the check in the records, stable across releases: letters, digits and dashes. */
    String getKey();

    /** What the check means to a developer, such as "Code quality", never the tool behind it. */
    String getLabel();

    /** One sentence on what the check looks at, shown under its name on the steps of a version. */
    String getDescription();

    /** The place of the check among the steps of a version: a lower order runs and shows first. */
    default int getOrder() {
        return 100;
    }

    /** Run the check on a version. A thrown exception reads as a check waiting for review, with the message. */
    ValidationResult validate(ModuleArtifact artifact);
}
