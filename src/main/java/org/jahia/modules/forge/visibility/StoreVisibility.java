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
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.visibility;

import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.sites.JahiaSitesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.regex.Pattern;

/**
 * The published-only rule of the store, shared by every path that serves store content
 * (JSTORE-SEC-005).
 *
 * <p>A module or package ({@code jmix:forgeElement}) and each of its versions carry a {@code published}
 * flag. Content under a draft is a draft too: a version under a draft module, the artifact
 * ({@code jnt:file}) of a draft version, the icon of a draft module. Draft nodes are readable in the
 * live workspace like any other content, so the rule is applied by the code that serves them, and every
 * such path applies THIS rule: the render filter ({@code PublishedModuleFilter}), the {@code /files}
 * filter ({@code PublishedFileFilter}), the Maven proxy and the module-list RSS view. Two readings:
 * <ul>
 *   <li>{@link #isPublished}: no draft at or above the node. What a public feed may list, whoever asks.</li>
 *   <li>{@link #isVisibleToCaller}: published, or the caller of the node's session can write every draft
 *       at or above it (the owner or an administrator previewing a draft).</li>
 * </ul>
 * Only the store types are governed by their {@code published} property: the security advisory keeps
 * a retired property of that name, which means nothing. The walk up stops at the site node. Errors
 * fail closed: a node the rule cannot evaluate is treated as a draft.
 */
public final class StoreVisibility {

    /** The store's root folder under a site: the catalogue, the feeds and the Maven proxy live there. */
    public static final String MODULES_REPOSITORY = "/contents/modules-repository";

    static final String PUBLISHED = "published";
    private static final String JCR_WRITE = "jcr:write";
    private static final String SITE_TYPE = "jnt:virtualsite";
    private static final String[] DRAFT_TYPES = {"jmix:forgeElement", "jnt:forgeModuleVersion", "jnt:forgePackageVersion"};
    private static final Pattern MODULES_REPOSITORY_PATH = Pattern.compile(
            Pattern.quote(JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR) + "[^/]+" + Pattern.quote(MODULES_REPOSITORY));
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreVisibility.class);

    private StoreVisibility() {
    }

    /**
     * @return true when neither the node nor any store element or version above it is a draft: what an
     *         anonymous feed may list. False for a null node and when the rule cannot be evaluated.
     */
    public static boolean isPublished(JCRNodeWrapper node) {
        return check(node, false);
    }

    /**
     * @return true when the node is published, or when the caller of the node's session can write every
     *         draft at or above it. False for a null node and when the rule cannot be evaluated.
     */
    public static boolean isVisibleToCaller(JCRNodeWrapper node) {
        return check(node, true);
    }

    /** @return the path of the store folder of a site: {@code /sites/<siteKey>/contents/modules-repository} */
    public static String modulesRepositoryPath(String siteKey) {
        return JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR + siteKey + MODULES_REPOSITORY;
    }

    /** @return true when the node is the store folder of a site (not a folder above, below or beside it) */
    public static boolean isModulesRepository(JCRNodeWrapper node) {
        return node != null && node.getPath() != null && MODULES_REPOSITORY_PATH.matcher(node.getPath()).matches();
    }

    private static boolean check(JCRNodeWrapper node, boolean writerSeesDrafts) {
        if (node == null) {
            return false;
        }
        try {
            for (JCRNodeWrapper current = node; current != null && !current.isNodeType(SITE_TYPE); current = parentOf(current)) {
                if (isDraft(current) && !(writerSeesDrafts && current.hasPermission(JCR_WRITE))) {
                    return false;
                }
            }
            return true;
        } catch (RepositoryException | RuntimeException e) {
            LOGGER.warn("Cannot evaluate the published state of {}, treated as a draft: {}", node.getPath(), e.getMessage());
            LOGGER.debug("Published state evaluation failure", e);
            return false;
        }
    }

    private static boolean isDraft(JCRNodeWrapper node) throws RepositoryException {
        for (String type : DRAFT_TYPES) {
            if (node.isNodeType(type)) {
                return !(node.hasProperty(PUBLISHED) && node.getProperty(PUBLISHED).getBoolean());
            }
        }
        return false;
    }

    /** The parent, or null at the root or when the caller cannot read it (the walk ends there). */
    private static JCRNodeWrapper parentOf(JCRNodeWrapper node) throws RepositoryException {
        try {
            return node.getParent();
        } catch (ItemNotFoundException | AccessDeniedException e) {
            return null;
        }
    }
}
