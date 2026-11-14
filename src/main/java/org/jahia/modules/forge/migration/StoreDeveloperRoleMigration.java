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
package org.jahia.modules.forge.migration;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.services.content.RBACUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes {@code jcr:modifyAccessControl_live} from the {@code store-developer} role on installations that still
 * carry it (SEC-366 / GHSA-f882-3xwv-3439). With it, a store developer could rewrite the site's live ACL.
 *
 * <p><strong>When it matters.</strong> {@code roles.xml} no longer lists the permission. Jahia imports a module's
 * {@code roles.xml} once per module version and, for a role that already exists, overwrites its
 * {@code j:permissionNames} (see {@link StoreAdministratorPublishMigration}), so a normal upgrade to a new version
 * already removes the grant. This migration covers the installations where that import did not run: a redeployed
 * snapshot, a failed import, a role changed after the import. Elsewhere it is a silent no-op.
 *
 * <p><strong>Both permission lists.</strong> Jahia's access manager reads a role's {@code j:permissionNames} first,
 * and falls back to its {@code j:permissions} references only when {@code j:permissionNames} is absent
 * ({@code AccessManagerUtils.getPrivileges}). A role imported from {@code roles.xml} has {@code j:permissionNames},
 * so that is the list the grant is removed from (order and the other names kept). The stale reference is also
 * revoked from {@code j:permissions} when present, so a list ignored today cannot bring the grant back if
 * {@code j:permissionNames} is ever dropped. The references are revoked with
 * {@code RBACUtils.revokePermissionFromRole}, the platform's own implementation of that edit; the permission's path
 * is read back from the references the role holds, never hard-coded. Then the module saves and flushes Jahia's
 * role-privilege caches ({@link RolePermissions}), so the change applies to the next permission check.
 *
 * <p>{@code RBACUtils} is deprecated as a whole class (not {@code forRemoval}) and the platform exports no
 * replacement, so {@code java:S1874} is suppressed at the single call site. Revisit when Jahia ships a supported
 * role-permission API.
 *
 * <p><strong>Where and when.</strong> Roles exist in the edit workspace only ({@code jnt:role} is
 * {@code jmix:nolive}), so live is not touched. Unlike {@link StoreAdministratorPublishMigration}, which adds a
 * permission an administrator may remove on purpose and so runs once per module version, this migration removes a
 * security hole and runs at every activation: it is idempotent and cheap, silent when the role is clean, and logs
 * at INFO when it removed something. A failure is logged at ERROR with the manual fix and never fails the
 * activation.
 */
@Component(immediate = true)
public class StoreDeveloperRoleMigration {

    private static final Logger logger = LoggerFactory.getLogger(StoreDeveloperRoleMigration.class);

    private static final String ROLE_NAME = "store-developer";
    private static final String ROLE_PATH = RolePermissions.rolePath(ROLE_NAME);
    private static final String STALE_PERMISSION = "jcr:modifyAccessControl_live";

    private final JCRTemplate jcrTemplate;

    public StoreDeveloperRoleMigration() {
        this(null);
    }

    StoreDeveloperRoleMigration(JCRTemplate jcrTemplate) {
        this.jcrTemplate = jcrTemplate;
    }

    /**
     * Removes the stale grant, absorbing every failure. A migration that throws here would leave the bundle unable
     * to activate and take the whole store down, in exchange for a permission the operator can still remove by
     * hand. The error names the manual fix, because a failure means the advisory's fix is NOT applied here.
     */
    @Activate
    public void activate() {
        try {
            final int removed = template().doExecuteWithSystemSessionAsUser(null, Constants.EDIT_WORKSPACE, null,
                    StoreDeveloperRoleMigration::removeStalePermission);
            if (removed > 0) {
                logger.info("SEC-366: removed {} from role {} ({} occurrence(s))", STALE_PERMISSION, ROLE_NAME,
                        removed);
            }
        } catch (RepositoryException | RuntimeException e) {
            logger.error("SEC-366: could not remove {} from role {}. This installation is still exposed - remove"
                    + " the permission from {} by hand (Administration > Roles and permissions > {}).",
                    STALE_PERMISSION, ROLE_NAME, ROLE_PATH, ROLE_NAME, e);
        }
    }

    /** @return how many occurrences of the stale permission were removed (0 when already clean). */
    private static Integer removeStalePermission(JCRSessionWrapper session) throws RepositoryException {
        if (!session.nodeExists(ROLE_PATH)) {
            // Fresh install: the roles are not imported yet.
            return 0;
        }
        final JCRNodeWrapper role = session.getNode(ROLE_PATH);
        final int removed = removeFromPermissionNames(role) + revokeFromPermissions(session, role);
        if (removed > 0) {
            RolePermissions.saveAndFlush(session);
        }
        return removed;
    }

    /** Edits {@code j:permissionNames}, the list the access manager reads; unsaved. */
    private static int removeFromPermissionNames(JCRNodeWrapper role) throws RepositoryException {
        if (!role.hasProperty(RolePermissions.PERMISSION_NAMES)) {
            return 0;
        }
        final String[] names = RolePermissions.names(role);
        final String[] kept = withoutStalePermission(names);
        if (kept.length == names.length) {
            return 0;
        }
        role.setProperty(RolePermissions.PERMISSION_NAMES, kept);
        return names.length - kept.length;
    }

    /** Revokes the stale {@code j:permissions} references; unsaved. */
    // java:S1874 - RBACUtils is deprecated with no exported replacement; see the class javadoc.
    @SuppressWarnings("java:S1874")
    private static int revokeFromPermissions(JCRSessionWrapper session, JCRNodeWrapper role)
            throws RepositoryException {
        int revoked = 0;
        for (String permissionPath : stalePermissionPaths(referencedPermissions(session, role))) {
            if (RBACUtils.revokePermissionFromRole(permissionPath, ROLE_PATH, session)) {
                revoked++;
            }
        }
        return revoked;
    }

    /**
     * Resolves the role's {@code j:permissions} weak references into {@code [name, path]} pairs. A reference that
     * no longer resolves is skipped rather than treated as a match: only a positively identified permission is
     * removed.
     */
    private static List<String[]> referencedPermissions(JCRSessionWrapper session, JCRNodeWrapper role)
            throws RepositoryException {
        final List<String[]> permissions = new ArrayList<>();
        if (!role.hasProperty(RolePermissions.PERMISSIONS)) {
            return permissions;
        }
        final JCRPropertyWrapper property = role.getProperty(RolePermissions.PERMISSIONS);
        final JCRValueWrapper[] values = property.getValues();
        if (values == null) {
            return permissions;
        }
        for (JCRValueWrapper value : values) {
            final String identifier = value.getString();
            try {
                final JCRNodeWrapper permission = session.getNodeByIdentifier(identifier);
                permissions.add(new String[]{permission.getName(), permission.getPath()});
            } catch (ItemNotFoundException e) {
                logger.debug("SEC-366: role {} references permission {} which no longer exists - ignored",
                        ROLE_NAME, identifier);
            }
        }
        return permissions;
    }

    /**
     * @param names the role's permission names
     * @return a new array without {@code jcr:modifyAccessControl_live}, the other names in their order; the same
     * length as {@code names} when it does not hold it
     */
    // package-private for unit testing
    static String[] withoutStalePermission(String[] names) {
        final List<String> kept = new ArrayList<>(names.length);
        for (String name : names) {
            if (!STALE_PERMISSION.equals(name)) {
                kept.add(name);
            }
        }
        return kept.toArray(new String[0]);
    }

    /**
     * The paths of the permissions that must be revoked, given the role's referenced permissions as
     * {@code [name, path]} pairs. Pure, so the decision is unit-testable without a repository.
     */
    // package-private for unit testing
    static List<String> stalePermissionPaths(List<String[]> referencedPermissions) {
        final List<String> stale = new ArrayList<>();
        for (String[] permission : referencedPermissions) {
            if (permission.length >= 2 && STALE_PERMISSION.equals(permission[0])) {
                stale.add(permission[1]);
            }
        }
        return stale;
    }

    private JCRTemplate template() {
        return jcrTemplate != null ? jcrTemplate : JCRTemplate.getInstance();
    }
}
