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

import javax.jcr.RepositoryException;
import javax.validation.constraints.AssertTrue;
import org.apache.commons.lang.StringUtils;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.validation.JCRNodeValidator;
import org.jahia.services.sites.JahiaSitesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gate on the publication of a local version: a {@code jnt:forgeModuleVersion} saved with
 * {@code published} set to true must be approved, whatever path writes it. Jahia instantiates
 * this validator for each saved node of the type, which {@link VersionPublicationGate} registers.
 */
public class VersionPublicationValidator implements JCRNodeValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(VersionPublicationValidator.class);

    private final JCRNodeWrapper node;

    public VersionPublicationValidator(JCRNodeWrapper node) {
        this.node = node;
    }

    public JCRNodeWrapper getNode() {
        return node;
    }

    @AssertTrue(message = "forge.validation.publication.notApproved")
    public boolean isApprovedWhenPublished() {
        try {
            if (!node.hasProperty("published") || !node.getProperty("published").getBoolean()) {
                return true;
            }
            ValidationService service = BundleUtils.getOsgiService(ValidationService.class, null);
            if (service == null) {
                return true;
            }
            String siteKey = StringUtils.substringBetween(node.getPath(), JahiaSitesService.SITES_JCR_PATH + "/", "/");
            String version = node.hasProperty("versionNumber") ? node.getProperty("versionNumber").getString() : node.getName();
            String moduleName = node.getParent().getName();
            boolean allowed = siteKey == null || service.mayPublish(siteKey, moduleName, version);
            if (!allowed) {
                LOGGER.info("Publication of {} refused: the version {} of {} is not approved", node.getPath(), version, moduleName);
            }
            return allowed;
        } catch (RepositoryException e) {
            LOGGER.warn("Cannot check the validation of {}: {}", node.getPath(), e.getMessage());
            return true;
        }
    }
}
