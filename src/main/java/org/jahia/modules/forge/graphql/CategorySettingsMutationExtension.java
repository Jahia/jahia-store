package org.jahia.modules.forge.graphql;

import org.apache.commons.lang.StringUtils;
import org.jahia.modules.forge.settings.ForgeSettingsService;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.List;
import java.util.Locale;

/**
 * Logic holder for the Private App Store category-management write operations. The GraphQL surface
 * is {@code mutation { forge { setRootCategory / addCategory / updateCategoryTitles / deleteCategory } }}
 * — see {@link ForgeMutation}, which delegates here. (Formerly a flat {@code @GraphQLTypeExtension}
 * contributing those mutations straight onto the root Mutation.)
 */
public final class CategorySettingsMutationExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(CategorySettingsMutationExtension.class);
    private static final String PERMISSION = "siteAdminForgeSettings";
    private static final String JCR_TITLE = "jcr:title";
    private static final String JNT_CATEGORY = "jnt:category";
    private static final String WORKSPACE_DEFAULT = "default";
    private static final String CURRENT_SEGMENT = ".";
    private static final String PARENT_SEGMENT = "..";
    private static final String FORBIDDEN_NAME_CHARS = "/\\:[]|*";

    private CategorySettingsMutationExtension() {
    }

    public static Boolean setRootCategory(final String siteKey, final String rootCategoryUuid) {
        return execute(siteKey, session -> {
            // Validate the target is an actual category before storing it: the gate runs in a
            // system session that bypasses ACLs, so an unvalidated UUID could point the site's
            // root at any node in the repository.
            final JCRNodeWrapper category = resolveCategory(session, rootCategoryUuid);
            // JSTORE-SEC-001: only a category of the shared tree /sites/systemsite/categories may be
            // a store's root. Another site's subtree is refused, and so is the store's own site.
            if (!ForgeCategoryScope.isAllowedRoot(category)) {
                throw new AccessDeniedException("The root category must be under "
                        + ForgeCategoryScope.CATEGORIES_ROOT_PATH + ": " + rootCategoryUuid);
            }
            // The root reference now lives in per-site OSGi config (not jmix:forgeSettings),
            // preserving the connection/branding fields written by ForgeSettings.
            final ForgeSettingsService service = ForgeSettingsMutationExtension.service();
            service.save(siteKey, service.get(siteKey).toBuilder()
                    .rootCategoryUuid(category.getIdentifier())
                    .build());
            return Boolean.TRUE;
        });
    }

    public static String addForgeCategory(final String siteKey, final String name) {
        validateCategoryName(name);

        return execute(siteKey, session -> {
            final JCRNodeWrapper root = resolveRoot(session, siteKey);
            final String safeName = JCRContentUtils.findAvailableNodeName(root, name);
            final JCRNodeWrapper category = root.addNode(safeName, JNT_CATEGORY);
            session.save();
            return category.getIdentifier();
        });
    }

    public static Boolean updateForgeCategoryTitles(final String siteKey, final String uuid,
                                                    final List<InputCategoryTitle> titles) {
        return execute(siteKey, session -> {
            // Confine titling to a category within THIS site's root-category subtree
            // (the gate is site-scoped; validate the caller-supplied UUID).
            assertManagedBySite(session, siteKey, resolveCategory(session, uuid));
            for (InputCategoryTitle title : titles) {
                applyTitle(siteKey, uuid, title);
            }

            return Boolean.TRUE;
        });
    }

    private static void applyTitle(String siteKey, String uuid, InputCategoryTitle title) throws RepositoryException {
        if (title == null || StringUtils.isBlank(title.getLanguage())) {
            return;
        }

        // Resolve, re-validate scope, and write in the SAME localized session, closing the
        // check-then-write window (the node could otherwise be relocated outside the site's
        // subtree between a separate scope check and the write). The user session also enforces
        // JCR ACLs on the write. Locale.forLanguageTag avoids the deprecated Locale(String) ctor.
        final JCRSessionWrapper localized = JCRSessionFactory.getInstance()
                .getCurrentUserSession(WORKSPACE_DEFAULT, Locale.forLanguageTag(title.getLanguage()));
        final JCRNodeWrapper node = resolveCategory(localized, uuid);
        assertManagedBySite(localized, siteKey, node);
        if (StringUtils.isBlank(title.getTitle())) {
            if (node.hasProperty(JCR_TITLE)) {
                node.getProperty(JCR_TITLE).remove();
            }
        } else {
            node.setProperty(JCR_TITLE, title.getTitle());
        }
        localized.save();
    }

    public static Boolean deleteForgeCategory(final String siteKey, final String uuid) {
        return execute(siteKey, session -> {
            // Confine deletion to a category within THIS site's root-category subtree.
            // The gate authorizes against the site, but the system session bypasses
            // ACLs — without this a site admin could delete any node by UUID.
            final JCRNodeWrapper node = resolveCategory(session, uuid);
            assertManagedBySite(session, siteKey, node);
            node.remove();
            session.save();
            return Boolean.TRUE;
        });
    }

    /**
     * Require the new category's name to be ONE JCR name segment (JSTORE-SEC-001). {@code addNode}
     * takes a relative path, so a name such as {@code ../x} or {@code a/b} would create the node
     * outside the site's root category, in a system session that bypasses ACLs. Refuse the
     * {@code .} and {@code ..} segments, the JCR path and name delimiters ({@code / : [ ] | *}),
     * the backslash and control characters.
     */
    private static void validateCategoryName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Category name must not be blank");
        }
        if (CURRENT_SEGMENT.equals(name) || PARENT_SEGMENT.equals(name)
                || StringUtils.containsAny(name, FORBIDDEN_NAME_CHARS)) {
            throw new IllegalArgumentException(
                    "Category name must be a single name, without . or .. and without the characters / \\ : [ ] | *");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("Category name must not contain control characters");
            }
        }
    }

    /**
     * Resolve a caller-supplied category UUID, rejecting anything that is not an
     * existing jnt:category. The mutation gate authorizes against the SITE, but the
     * work then runs in a system session that bypasses JCR ACLs — so a UUID argument
     * must be validated or a site administrator could target arbitrary repository
     * nodes by id (SECURITY-571).
     */
    private static JCRNodeWrapper resolveCategory(JCRSessionWrapper session, String uuid) throws RepositoryException {
        final JCRNodeWrapper node;
        try {
            node = session.getNodeByIdentifier(uuid);
        } catch (ItemNotFoundException e) {
            throw new AccessDeniedException("No such category: " + uuid);
        }
        if (!node.isNodeType(JNT_CATEGORY)) {
            throw new AccessDeniedException("Not a category: " + uuid);
        }
        return node;
    }

    /**
     * Resolve the site's configured root category (UUID stored in per-site OSGi config). The stored
     * value is checked again on every use (JSTORE-SEC-001): a root stored before the check existed,
     * or written to the .cfg file by hand, that is not in /sites/systemsite/categories is refused,
     * so no write can happen under it.
     */
    private static JCRNodeWrapper resolveRoot(JCRSessionWrapper session, String siteKey) throws RepositoryException {
        final String rootUuid = ForgeSettingsMutationExtension.service().get(siteKey).getRootCategoryUuid();
        if (StringUtils.isBlank(rootUuid)) {
            throw new RepositoryException("No root category configured for site " + siteKey);
        }
        final JCRNodeWrapper root = resolveCategory(session, rootUuid);
        if (!ForgeCategoryScope.isAllowedRoot(root)) {
            LOGGER.warn("The root category of site {} is not under {}: refusing category changes. Set the root again.",
                    siteKey, ForgeCategoryScope.CATEGORIES_ROOT_PATH);
            throw new AccessDeniedException("The root category of site " + siteKey + " is not under "
                    + ForgeCategoryScope.CATEGORIES_ROOT_PATH);
        }
        return root;
    }

    /**
     * Enforce that a category lives within the site's configured root-category
     * subtree — i.e. one this site administrator actually manages — never the root
     * itself nor a node outside it. Without this a site admin could edit or delete
     * another site's categories (or arbitrary nodes) by passing their own siteKey
     * plus a foreign UUID. The root is resolved in {@code category}'s own session so
     * the path comparison is within one workspace.
     */
    private static void assertManagedBySite(JCRSessionWrapper session, String siteKey, JCRNodeWrapper category)
            throws RepositoryException {
        final JCRNodeWrapper root = resolveRoot(session, siteKey);
        if (!category.getPath().startsWith(root.getPath() + "/")) {
            throw new AccessDeniedException(
                    "Category " + category.getPath() + " is not within the site's root category");
        }
    }

    private static <T> T execute(String siteKey, JCRCallback<T> work) {
        return ForgeSiteAccess.executeAsSystemForSite(
                siteKey, PERMISSION, "Category settings mutation failed for site ", work);
    }
}
