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

import org.apache.jackrabbit.core.fs.FileSystem;
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
import java.util.Arrays;
import java.util.List;

/**
 * Strips {@code jcr:modifyAccessControl_live} from the {@code store-developer} role on
 * installations that already carry it (SEC-366 / GHSA-f882-3xwv-3439).
 *
 * <p><strong>Why this exists.</strong> Fixing {@code META-INF/import.zip!roles.xml} only helps a
 * fresh install. Module role data is imported once per module version, so upgrading does not
 * rewrite a role node already present in the JCR: every existing installation would keep the
 * vulnerable grant, and the site's live ACL would stay writable by anyone allowed to upload a
 * module. Telling operators to hand-edit {@code /roles/store-developer} does not scale, so the
 * module repairs itself on start.
 *
 * <p><strong>Why {@link RBACUtils}.</strong> The role's permissions live in {@code j:permissions}
 * as weak references, not in the {@code j:permissionNames} attribute used at import time.
 * {@code RBACUtils.revokePermissionFromRole} is the platform's own implementation of that
 * removal — it matches by permission identifier, rewrites the property, and handles the
 * becomes-empty case — so it is reused rather than reimplemented. It does not save; this class
 * does.
 *
 * <p>{@code RBACUtils} is deprecated as a whole class — not {@code forRemoval} — and the
 * platform exports no replacement, so {@code java:S1874} is suppressed at the single call site
 * rather than worked around. It is kept deliberately: reimplementing a weak-reference ACL edit
 * by hand to satisfy a style rule would put more risk into a security fix than the deprecation
 * carries. Revisit when Jahia ships a supported role-permission API.
 *
 * <p>The permission's own path is never hard-coded. It is read back from the references the role
 * actually holds, so the migration does not depend on where Jahia nests JCR privileges under
 * {@code /permissions}.
 *
 * <p>Idempotent: a role that is already clean is a silent no-op, so restarts and redeploys stay
 * quiet. Failure is logged and never propagated — a migration must not stop the module from
 * activating.
 */
@Component(immediate = true)
public class StoreDeveloperRoleMigration {

    private static final Logger logger = LoggerFactory.getLogger(StoreDeveloperRoleMigration.class);

    /**
     * Jahia stores role definitions under {@code /roles}. There is no exported platform constant
     * for this path (unlike {@code JahiaSitesService.SITES_JCR_PATH}), so it is named here once.
     */
    private static final String ROLES_PATH = "/roles";
    private static final String ROLE_NAME = "store-developer";
    private static final String STALE_PERMISSION = "jcr:modifyAccessControl_live";
    private static final String PERMISSIONS_PROPERTY = "j:permissions";

    /**
     * Role definitions live in the default workspace, but the live workspace is checked too: the
     * whole point of SEC-366 is that this module's live-workspace state diverged unnoticed from
     * its default-workspace state, so neither is assumed clean on the strength of the other.
     * A workspace with no {@code /roles/store-developer} is simply skipped.
     */
    private static final List<String> WORKSPACES = Arrays.asList("default", "live");

    @Activate
    public void activate() {
        for (String workspace : WORKSPACES) {
            migrateQuietly(workspace);
        }
    }

    /**
     * Run the migration for one workspace, absorbing every failure. A migration that throws here
     * would leave the bundle unable to activate, which would take the whole store down in
     * exchange for a permission the operator can still remove by hand — so the trade is refused.
     * The error names the manual remediation, because a failure here means the advisory's fix has
     * NOT been applied to this installation.
     */
    private void migrateQuietly(String workspace) {
        try {
            final int removed = JCRTemplate.getInstance()
                    .doExecuteWithSystemSessionAsUser(null, workspace, null, this::revokeStalePermission);
            if (removed > 0) {
                logger.info("SEC-366: removed {} from role {} in workspace {} ({} reference(s))",
                        STALE_PERMISSION, ROLE_NAME, workspace, removed);
            }
        } catch (RepositoryException | RuntimeException e) {
            logger.error("SEC-366: could not remove {} from role {} in workspace {}."
                            + " This installation is still exposed - remove the permission from {}{}{} by hand.",
                    STALE_PERMISSION, ROLE_NAME, workspace, ROLES_PATH, FileSystem.SEPARATOR, ROLE_NAME, e);
        }
    }

    /** @return how many stale permission references were revoked (0 when already clean). */
    // java:S1874 - RBACUtils is deprecated with no exported replacement; see the class javadoc.
    @SuppressWarnings("java:S1874")
    private Integer revokeStalePermission(JCRSessionWrapper session) throws RepositoryException {
        final String rolePath = ROLES_PATH + FileSystem.SEPARATOR + ROLE_NAME;
        if (!session.nodeExists(rolePath)) {
            // Fresh install (roles not imported yet) or a workspace that holds no role tree.
            return 0;
        }

        final JCRNodeWrapper role = session.getNode(rolePath);
        final List<String> paths = stalePermissionPaths(referencedPermissions(session, role));
        if (paths.isEmpty()) {
            return 0;
        }

        int revoked = 0;
        for (String permissionPath : paths) {
            if (RBACUtils.revokePermissionFromRole(permissionPath, rolePath, session)) {
                revoked++;
            }
        }
        if (revoked > 0) {
            session.save();
        }
        return revoked;
    }

    /**
     * Resolve the role's {@code j:permissions} weak references into {@code [name, path]} pairs,
     * mirroring the {@code List<String[]>} shape the module already uses for ACL entries in
     * {@code ManageRolesMutationExtension}.
     *
     * <p>A reference that no longer resolves is skipped rather than treated as a match: this
     * migration only ever removes a permission it has positively identified.
     */
    private static List<String[]> referencedPermissions(JCRSessionWrapper session, JCRNodeWrapper role)
            throws RepositoryException {
        final List<String[]> permissions = new ArrayList<>();
        if (!role.hasProperty(PERMISSIONS_PROPERTY)) {
            return permissions;
        }

        final JCRPropertyWrapper property = role.getProperty(PERMISSIONS_PROPERTY);
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
     * The paths of the permissions that must be revoked, given the role's referenced permissions
     * as {@code [name, path]} pairs. Pure, so the decision is unit-testable without a repository.
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
}
