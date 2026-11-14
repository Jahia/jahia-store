package org.jahia.modules.forge.migration;

import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.utils.security.AccessManagerUtils;

import javax.jcr.RepositoryException;

/**
 * Reads and saves a role's permission list the way Jahia's access manager uses it, for the role migrations of
 * this package.
 *
 * <p>A {@code jnt:role} can list its permissions in two properties: {@code j:permissionNames} (permission names,
 * written by a module's {@code roles.xml} import) and {@code j:permissions} (weak references to the permission
 * nodes). {@code AccessManagerUtils.getPrivileges} reads {@code j:permissionNames} first and falls back to
 * {@code j:permissions} only when {@code j:permissionNames} is absent. Roles exist in the edit workspace only
 * ({@code jnt:role} is {@code jmix:nolive}; {@code AccessManagerUtils.findRoleNode} reads them there).
 *
 * <p>Jahia caches the privileges of each role: a change is seen only after
 * {@link #saveAndFlush(JCRSessionWrapper)}.
 */
final class RolePermissions {

    /**
     * Jahia stores role definitions under {@code /roles}. There is no exported platform constant for this path
     * (unlike {@code JahiaSitesService.SITES_JCR_PATH}), so it is named here once.
     */
    static final String ROLES_PATH = "/roles";
    static final String PERMISSION_NAMES = "j:permissionNames";
    static final String PERMISSIONS = "j:permissions";

    private RolePermissions() {
    }

    /**
     * @param roleName the role node name
     * @return the path of the role definition
     */
    static String rolePath(String roleName) {
        return ROLES_PATH + FileSystem.SEPARATOR + roleName;
    }

    /**
     * @param role a role that has {@code j:permissionNames}
     * @return its permission names, in order
     * @throws RepositoryException when the property cannot be read
     */
    static String[] names(JCRNodeWrapper role) throws RepositoryException {
        JCRValueWrapper[] values = role.getProperty(PERMISSION_NAMES).getValues();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].getString();
        }
        return names;
    }

    /**
     * Saves the session, then flushes Jahia's role-privilege caches so the change applies to the next permission
     * check.
     *
     * @param session the session holding the role change
     * @throws RepositoryException when the save fails
     */
    static void saveAndFlush(JCRSessionWrapper session) throws RepositoryException {
        session.save();
        AccessManagerUtils.flushPrivilegesInRoles();
        AccessManagerUtils.flushMatchingPermissions();
    }
}
