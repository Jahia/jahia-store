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

import org.jahia.services.content.JCRStoreService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/** Registers {@link VersionPublicationValidator} on the version type while the module runs. */
@Component(immediate = true)
public class VersionPublicationGate {

    static final String VERSION_TYPE = "jnt:forgeModuleVersion";

    @Reference
    private ValidationService validationService;

    @Activate
    public void activate() {
        JCRStoreService.getInstance().addValidator(VERSION_TYPE, VersionPublicationValidator.class);
    }

    @Deactivate
    public void deactivate() {
        JCRStoreService.getInstance().removeValidator(VERSION_TYPE);
    }
}
