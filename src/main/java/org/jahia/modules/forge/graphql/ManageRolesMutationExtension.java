package org.jahia.modules.forge.graphql;

import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.modules.forge.security.AdvisoriesLock;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.sites.JahiaSitesService;

import javax.jcr.RepositoryException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Logic holder for the Private App Store role-management write operations. The GraphQL surface is
 * {@code mutation { forge { grantRole / revokeRole } }} — see {@link ForgeMutation}, which delegates
 * here. (Formerly a flat {@code @GraphQLTypeExtension} contributing {@code grantSiteRole} and
 * {@code revokeSiteRole} straight onto the root Mutation.)
 */
public final class ManageRolesMutationExtension {

    private static final String PERMISSION = "siteAdminForgeSettings";
    private static final String SITES_PATH = JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR;
    private static final String GRANT = "GRANT";

    private ManageRolesMutationExtension() {
    }

    public static Boolean grantSiteRole(final String siteKey, final String role,
                                        final String principalName, final PrincipalType principalType) {
        // Only the store-managed roles may be granted through this gate. Without this an admin
        // with siteAdminForgeSettings could pass an arbitrary role name and create ACL entries
        // beyond the store's intended set (SECURITY-571). The allowlist is the same set the
        // admin UI renders (ManageRolesQueryExtension.FORGE_ROLES).
        if (!ManageRolesQueryExtension.FORGE_ROLES.contains(role)) {
            throw new ForgeSettingsException("Role not permitted: " + role, null);
        }
        return execute(siteKey, session -> {
            final JCRNodeWrapper site = session.getNode(SITES_PATH + siteKey);
            site.grantRoles(principalType.toPrincipalKey(principalName),
                    new HashSet<>(Collections.singletonList(role)));
            session.save();
            lockAdvisories(siteKey, role);
            return Boolean.TRUE;
        });
    }

    public static Boolean revokeSiteRole(final String siteKey, final String role,
                                         final String principalName, final PrincipalType principalType) {
        // Same allowlist as grantSiteRole: this gate manages only the store roles, so it must
        // never revoke a non-store role (e.g. strip site-administrator from a principal). Without
        // this a store admin could downgrade arbitrary site grants. (SECURITY-571 review NEW-1)
        if (!ManageRolesQueryExtension.FORGE_ROLES.contains(role)) {
            throw new ForgeSettingsException("Role not permitted: " + role, null);
        }
        return execute(siteKey, session -> {
            final JCRNodeWrapper site = session.getNode(SITES_PATH + siteKey);
            final String principalKey = principalType.toPrincipalKey(principalName);
            // JCRNodeWrapper has no per-role revoke. Strategy: pull the principal's
            // current role set on this node, drop the target role, then write the
            // remainder back. If nothing remains, drop the principal entirely.
            final List<String[]> grants = site.getAclEntries().get(principalKey);
            if (grants == null) {
                return Boolean.TRUE;
            }

            final Set<String> remainingRoles = directGrantsExcluding(grants, site.getPath(), role);
            site.revokeRolesForPrincipal(principalKey);
            if (!remainingRoles.isEmpty()) {
                site.grantRoles(principalKey, remainingRoles);
            }

            session.save();
            lockAdvisories(siteKey, role);
            return Boolean.TRUE;
        });
    }

    /**
     * A store developer must not write in the site's security advisories folder: once its grants change,
     * bring the folder's DENY entries in line (see {@link AdvisoriesLock}). The JCR listener does it too;
     * calling it here makes the mutation fail loudly if the lock cannot be written.
     */
    private static void lockAdvisories(String siteKey, String role) throws RepositoryException {
        if (AdvisoriesLock.ROLE.equals(role)) {
            AdvisoriesLock.syncSite(siteKey);
        }
    }

    /**
     * Collects the principal's currently-directly-granted roles on the given node,
     * minus the one being revoked. Inherited entries are intentionally excluded —
     * they can only be edited at their source node.
     */
    static Set<String> directGrantsExcluding(List<String[]> grants, String sitePath, String roleToRevoke) {
        final Set<String> remaining = new HashSet<>();
        for (String[] grant : grants) {
            if (isDirectGrantForOtherRole(grant, sitePath, roleToRevoke)) {
                remaining.add(grant[2]);
            }
        }

        return remaining;
    }

    private static boolean isDirectGrantForOtherRole(String[] grant, String sitePath, String roleToRevoke) {
        // JCRNodeWrapperImpl.getAclEntries returns String[] of [path, aceType, roleName].
        return grant.length >= 3
                && GRANT.equals(grant[1])
                && sitePath.equals(grant[0])
                && !grant[2].equals(roleToRevoke);
    }

    private static <T> T execute(String siteKey, JCRCallback<T> work) {
        return ForgeSiteAccess.executeAsSystemForSite(
                siteKey, PERMISSION, "Manage-roles mutation failed for site ", work);
    }
}
