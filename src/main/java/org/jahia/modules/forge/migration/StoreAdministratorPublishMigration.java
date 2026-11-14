package org.jahia.modules.forge.migration;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.RBACUtils;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Adds Jahia's {@code publish} permission to the {@code store-administrator} role on installations whose role
 * predates it (5.2.0), so that store administrators can publish security advisories. This widens the role:
 * a store administrator can publish any content of the store site they can edit.
 *
 * <p><b>Why, although {@code roles.xml} carries it.</b> Jahia imports a module's {@code roles.xml} once per
 * module version ({@code Activator.checkInitialImport}: only when {@code /modules/<id>/<version>} is absent, or
 * the bundle was just installed). For a role that already exists, {@code DocumentViewImportHandler} updates the
 * node in place ({@code updatesAllowedOnExistingChild}: {@code jnt:role} is not in its no-update list) and
 * overwrites {@code j:permissionNames}, so upgrading to a new version does bring the permission. It does not
 * when that version's import already ran (a redeployed snapshot), when the import failed, or on a role changed
 * after the import. This migration covers those cases and is otherwise a silent no-op.
 *
 * <p><b>Where.</b> Roles exist in the edit workspace only ({@code jnt:role} and {@code jnt:roles} are
 * {@code jmix:nolive}; {@code AccessManagerUtils.findRoleNode} reads them from the edit workspace), so live is
 * not touched. Jahia reads a role's {@code j:permissionNames} first and falls back to its
 * {@code j:permissions} references ({@code AccessManagerUtils.getPrivileges}): the migration edits the list
 * the role actually uses, then flushes Jahia's role-privilege caches (see {@link RolePermissions}).
 *
 * <p>Idempotent, run at activation in a system session, once per module version (see
 * {@link #activate(BundleContext)}); never fails the activation: an error is logged with the manual fix.
 */
@Component(immediate = true)
public class StoreAdministratorPublishMigration {

    static final String PERMISSION = "publish";

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreAdministratorPublishMigration.class);
    private static final String ROLE_NAME = "store-administrator";
    private static final String ROLE_PATH = RolePermissions.rolePath(ROLE_NAME);
    /** Where Jahia declares the permission: root-permissions.xml, under repository-permissions. */
    private static final String PERMISSION_PATH = "/permissions/repository-permissions/" + PERMISSION;
    private static final String DONE_PREFIX = "store-administrator-publish-done-";

    /** What the migration did. */
    enum Outcome { ADDED, ALREADY_PRESENT, NO_ROLE, NO_PERMISSION_LIST }

    private final JCRTemplate jcrTemplate;

    public StoreAdministratorPublishMigration() {
        this(null);
    }

    StoreAdministratorPublishMigration(JCRTemplate jcrTemplate) {
        this.jcrTemplate = jcrTemplate;
    }

    /**
     * Runs once per module version, like the {@code roles.xml} import: a {@code publish} an administrator
     * removed afterwards is not put back at the next start. Done is recorded in the bundle's data area; a
     * role not imported yet, or a failure, is not recorded, so the next start tries again.
     *
     * @param context the bundle context, for the data area and the module version
     */
    @Activate
    public void activate(BundleContext context) {
        File done = context.getDataFile(DONE_PREFIX + context.getBundle().getVersion());
        if (done != null && done.exists()) {
            return;
        }
        Outcome outcome = migrate();
        if (done != null && outcome != null && outcome != Outcome.NO_ROLE) {
            markDone(done);
        }
    }

    private Outcome migrate() {
        try {
            Outcome outcome = template().doExecuteWithSystemSessionAsUser(null, Constants.EDIT_WORKSPACE, null,
                    StoreAdministratorPublishMigration::grantPublish);
            if (outcome == Outcome.ADDED) {
                LOGGER.info("Added the {} permission to role {}, so that store administrators can publish"
                        + " security advisories", PERMISSION, ROLE_NAME);
            } else if (outcome == Outcome.NO_PERMISSION_LIST) {
                LOGGER.warn("Role {} lists no permission; {} not added. Add it by hand in the role settings if"
                        + " store administrators must publish security advisories.", ROLE_PATH, PERMISSION);
            }
            return outcome;
        } catch (RepositoryException | RuntimeException e) {
            LOGGER.error("Could not add the {} permission to role {}: store administrators cannot publish"
                    + " security advisories. Add the {} permission to the role by hand (Administration > Roles"
                    + " and permissions > {}).", PERMISSION, ROLE_NAME, PERMISSION, ROLE_NAME, e);
            return null;
        }
    }

    private static void markDone(File done) {
        try {
            Files.createFile(done.toPath());
        } catch (IOException e) {
            LOGGER.warn("Cannot record that the {} migration of role {} ran; it will run again at the next start",
                    PERMISSION, ROLE_NAME, e);
        }
    }

    // java:S1874 - RBACUtils is deprecated with no exported replacement; see StoreDeveloperRoleMigration.
    @SuppressWarnings("java:S1874")
    private static Outcome grantPublish(JCRSessionWrapper session) throws RepositoryException {
        if (!session.nodeExists(ROLE_PATH)) {
            return Outcome.NO_ROLE;
        }
        JCRNodeWrapper role = session.getNode(ROLE_PATH);
        boolean added;
        if (role.hasProperty(RolePermissions.PERMISSION_NAMES)) {
            String[] names = RolePermissions.names(role);
            String[] updated = withPublish(names);
            added = updated.length != names.length;
            if (added) {
                role.setProperty(RolePermissions.PERMISSION_NAMES, updated);
            }
        } else if (role.hasProperty(RolePermissions.PERMISSIONS)) {
            added = RBACUtils.grantPermissionToRole(PERMISSION_PATH, ROLE_PATH, session);
        } else {
            return Outcome.NO_PERMISSION_LIST;
        }
        if (!added) {
            return Outcome.ALREADY_PRESENT;
        }
        RolePermissions.saveAndFlush(session);
        return Outcome.ADDED;
    }

    /**
     * @param names the role's permission names
     * @return a new array with {@code publish} appended, or {@code names} itself when it already holds it
     */
    static String[] withPublish(String[] names) {
        if (Arrays.asList(names).contains(PERMISSION)) {
            return names;
        }
        String[] updated = Arrays.copyOf(names, names.length + 1);
        updated[names.length] = PERMISSION;
        return updated;
    }

    private JCRTemplate template() {
        return jcrTemplate != null ? jcrTemplate : JCRTemplate.getInstance();
    }
}
