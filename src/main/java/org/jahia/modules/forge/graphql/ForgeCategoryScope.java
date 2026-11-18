package org.jahia.modules.forge.graphql;

import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.sites.JahiaSitesService;

import javax.jcr.RepositoryException;

/**
 * Where a store's root category may live (JSTORE-SEC-001). Jahia keeps categories in one shared
 * tree, {@code /sites/systemsite/categories}, not per site. A store's root category must be that
 * node or a category below it. Anything else (another site's subtree, the store's own site, any
 * other path) is refused when the root is stored and again each time the stored value is used,
 * because the category operations run in a system session that bypasses JCR ACLs.
 */
final class ForgeCategoryScope {

    static final String CATEGORIES_ROOT_PATH = JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR
            + JahiaSitesService.SYSTEM_SITE_KEY + FileSystem.SEPARATOR + "categories";

    private static final String JNT_CATEGORY = "jnt:category";

    private ForgeCategoryScope() {
        // Utility class — not instantiable.
    }

    /** True when {@code node} is a {@code jnt:category} at or below {@link #CATEGORIES_ROOT_PATH}. */
    static boolean isAllowedRoot(JCRNodeWrapper node) throws RepositoryException {
        if (!node.isNodeType(JNT_CATEGORY)) {
            return false;
        }
        final String path = node.getPath();
        return CATEGORIES_ROOT_PATH.equals(path) || path.startsWith(CATEGORIES_ROOT_PATH + FileSystem.SEPARATOR);
    }
}
