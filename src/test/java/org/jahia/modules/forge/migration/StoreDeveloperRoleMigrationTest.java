package org.jahia.modules.forge.migration;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.services.content.RBACUtils;
import org.jahia.utils.security.AccessManagerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SEC-366 role migration, which removes {@code jcr:modifyAccessControl_live} from the
 * {@code store-developer} role.
 *
 * <p>Jahia's access manager reads a role's {@code j:permissionNames} first and falls back to its
 * {@code j:permissions} references only when {@code j:permissionNames} is absent
 * ({@code AccessManagerUtils.getPrivileges}). An imported role has {@code j:permissionNames}: removing the grant
 * from {@code j:permissions} alone leaves it in effect. The repository is mocked here; the e2e spec
 * {@code 33-storeDeveloperRoleMigration} measures the effect on a live Jahia.
 *
 * <p>The point also pinned down is that the migration removes <em>only</em>
 * {@code jcr:modifyAccessControl_live}. A migration that over-removes would silently break
 * uploads on every existing installation, which is a worse outcome than the vulnerability it is
 * closing.
 */
class StoreDeveloperRoleMigrationTest {

    private static final String STALE = "jcr:modifyAccessControl_live";
    private static final String STALE_PATH = "/permissions/repository-permissions/"
            + "accessControl-management/jcr:modifyAccessControl_live";

    private static final String ROLE = "/roles/store-developer";
    private static final String NAMES = "j:permissionNames";
    private static final String REFERENCES = "j:permissions";
    private static final String STALE_ID = "stale-permission-uuid";

    private JCRSessionWrapper session;
    private JCRNodeWrapper role;
    private StoreDeveloperRoleMigration migration;
    private MockedStatic<RBACUtils> rbac;
    private MockedStatic<AccessManagerUtils> accessManager;

    @BeforeEach
    void setUp() throws Exception {
        JCRTemplate jcrTemplate = mock(JCRTemplate.class);
        session = mock(JCRSessionWrapper.class);
        role = mock(JCRNodeWrapper.class);
        // Roles exist in the edit workspace only: the migration must open "default", never "live".
        when(jcrTemplate.doExecuteWithSystemSessionAsUser(isNull(), eq("default"), isNull(), any()))
                .thenAnswer(inv -> inv.<JCRCallback<?>>getArgument(3).doInJCR(session));
        when(session.nodeExists(ROLE)).thenReturn(true);
        when(session.getNode(ROLE)).thenReturn(role);
        migration = new StoreDeveloperRoleMigration(jcrTemplate);
        rbac = mockStatic(RBACUtils.class);
        accessManager = mockStatic(AccessManagerUtils.class);
    }

    @AfterEach
    void tearDown() {
        rbac.close();
        accessManager.close();
    }

    private static String[] permission(String name, String path) {
        return new String[]{name, path};
    }

    @Test
    @DisplayName("removes the stale name from j:permissionNames, keeps the others in order, saves and flushes")
    void activate_staleName_removedFromPermissionNames() throws Exception {
        // Arrange — the vulnerable role as roles.xml imported it before SEC-366.
        names("jahiaForgeUploadModule", "jcr:write_live", STALE);

        // Act
        migration.activate();

        // Assert
        verify(role).setProperty(NAMES, new String[]{"jahiaForgeUploadModule", "jcr:write_live"});
        verify(session).save();
        accessManager.verify(AccessManagerUtils::flushPrivilegesInRoles);
        accessManager.verify(AccessManagerUtils::flushMatchingPermissions);
        rbac.verifyNoInteractions();
    }

    @Test
    @DisplayName("a role already clean is a silent no-op: nothing written, nothing saved, no cache flushed")
    void activate_alreadyClean_noOp() throws Exception {
        names("jahiaForgeUploadModule", "jcr:write_live");

        migration.activate();

        verify(role, never()).setProperty(anyString(), any(String[].class));
        verify(session, never()).save();
        accessManager.verifyNoInteractions();
    }

    @Test
    @DisplayName("a role with neither list, or no role yet (fresh install), is left untouched")
    void activate_nothingToMigrate_untouched() throws Exception {
        migration.activate();
        when(session.nodeExists(ROLE)).thenReturn(false);
        migration.activate();

        verify(role, never()).setProperty(anyString(), any(String[].class));
        verify(session, never()).save();
        rbac.verifyNoInteractions();
    }

    @Test
    @DisplayName("a role holding only j:permissions references gets the stale reference revoked through RBACUtils")
    void activate_onlyReferences_revokedThroughRbacUtils() throws Exception {
        references();
        rbac.when(() -> RBACUtils.revokePermissionFromRole(STALE_PATH, ROLE, session)).thenReturn(true);

        migration.activate();

        rbac.verify(() -> RBACUtils.revokePermissionFromRole(STALE_PATH, ROLE, session));
        verify(role, never()).setProperty(anyString(), any(String[].class));
        verify(session).save();
        accessManager.verify(AccessManagerUtils::flushPrivilegesInRoles);
    }

    @Test
    @DisplayName("both lists present: j:permissionNames, the one Jahia reads, is edited, and the reference revoked")
    void activate_bothLists_permissionNamesEdited() throws Exception {
        names("jahiaForgeUploadModule", STALE, "jcr:write_live");
        references();
        rbac.when(() -> RBACUtils.revokePermissionFromRole(STALE_PATH, ROLE, session)).thenReturn(true);

        migration.activate();

        verify(role).setProperty(NAMES, new String[]{"jahiaForgeUploadModule", "jcr:write_live"});
        rbac.verify(() -> RBACUtils.revokePermissionFromRole(STALE_PATH, ROLE, session));
        verify(session).save();
    }

    @Test
    @DisplayName("a repository failure is logged, never thrown: the module still activates")
    void activate_failure_absorbed() throws Exception {
        names(STALE);
        when(role.setProperty(anyString(), any(String[].class))).thenThrow(new RepositoryException("boom"));

        assertThatCode(() -> migration.activate()).doesNotThrowAnyException();
        verify(session, never()).save();
    }

    @Test
    @DisplayName("withoutStalePermission drops every occurrence of the stale name only, in order")
    void withoutStalePermission_keepsOthersInOrder() {
        assertThat(StoreDeveloperRoleMigration.withoutStalePermission(
                new String[]{STALE, "a", STALE, "jcr:modifyAccessControl_default", "b"}))
                .containsExactly("a", "jcr:modifyAccessControl_default", "b");
        assertThat(StoreDeveloperRoleMigration.withoutStalePermission(new String[]{"a", "b"}))
                .containsExactly("a", "b");
        assertThat(StoreDeveloperRoleMigration.withoutStalePermission(new String[]{STALE})).isEmpty();
    }

    private void names(String... values) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        JCRValueWrapper[] wrapped = new JCRValueWrapper[values.length];
        for (int i = 0; i < values.length; i++) {
            wrapped[i] = mock(JCRValueWrapper.class);
            when(wrapped[i].getString()).thenReturn(values[i]);
        }
        when(property.getValues()).thenReturn(wrapped);
        when(role.hasProperty(NAMES)).thenReturn(true);
        when(role.getProperty(NAMES)).thenReturn(property);
    }

    /** The role references the stale permission node in j:permissions. */
    private void references() throws Exception {
        JCRValueWrapper value = mock(JCRValueWrapper.class);
        when(value.getString()).thenReturn(STALE_ID);
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getValues()).thenReturn(new JCRValueWrapper[]{value});
        when(role.hasProperty(REFERENCES)).thenReturn(true);
        when(role.getProperty(REFERENCES)).thenReturn(property);
        JCRNodeWrapper permissionNode = mock(JCRNodeWrapper.class);
        when(permissionNode.getName()).thenReturn(STALE);
        when(permissionNode.getPath()).thenReturn(STALE_PATH);
        when(session.getNodeByIdentifier(STALE_ID)).thenReturn(permissionNode);
    }

    @Test
    @DisplayName("returns the stale permission's path when the role still references it")
    void stalePermissionPaths_roleStillGranted_returnsPath() {
        // Arrange — the vulnerable 5.1.1 role: upload + live write + live ACL authority.
        List<String[]> referenced = Arrays.asList(
                permission("jahiaForgeUploadModule", "/permissions/actions/jahiaForge/jahiaForgeUploadModule"),
                permission("jcr:write_live", "/permissions/repository-permissions/jcr:write_live"),
                permission(STALE, STALE_PATH));

        // Act
        List<String> paths = StoreDeveloperRoleMigration.stalePermissionPaths(referenced);

        // Assert — exactly the one permission, addressed by the path the role itself pointed at.
        assertThat(paths).containsExactly(STALE_PATH);
    }

    @Test
    @DisplayName("returns nothing when the role is already clean")
    void stalePermissionPaths_alreadyRemediated_returnsEmpty() {
        List<String[]> referenced = Arrays.asList(
                permission("jahiaForgeUploadModule", "/permissions/actions/jahiaForge/jahiaForgeUploadModule"),
                permission("jcr:write_live", "/permissions/repository-permissions/jcr:write_live"));

        assertThat(StoreDeveloperRoleMigration.stalePermissionPaths(referenced)).isEmpty();
    }

    @Test
    @DisplayName("returns nothing when the role references no permission at all")
    void stalePermissionPaths_noReferences_returnsEmpty() {
        assertThat(StoreDeveloperRoleMigration.stalePermissionPaths(Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("leaves the default-workspace ACL permission alone")
    void stalePermissionPaths_defaultWorkspaceCounterpart_isNotRemoved() {
        // The _default counterpart is a different permission and is not what the advisory reports:
        // an ACL change in the default workspace still goes through publication review. Matching on
        // a prefix rather than the exact name would strip it too.
        List<String[]> referenced = Collections.singletonList(
                permission("jcr:modifyAccessControl_default",
                        "/permissions/repository-permissions/jcr:modifyAccessControl_default"));

        assertThat(StoreDeveloperRoleMigration.stalePermissionPaths(referenced)).isEmpty();
    }

    @Test
    @DisplayName("collects every occurrence when the role references the permission more than once")
    void stalePermissionPaths_duplicateReferences_returnsAll() {
        // revokePermissionFromRole matches by identifier, so a role carrying two distinct
        // permission nodes of the same name needs both paths returned or one survives the migration.
        String otherPath = "/permissions/legacy/jcr:modifyAccessControl_live";
        List<String[]> referenced = Arrays.asList(
                permission(STALE, STALE_PATH),
                permission(STALE, otherPath));

        assertThat(StoreDeveloperRoleMigration.stalePermissionPaths(referenced))
                .containsExactly(STALE_PATH, otherPath);
    }

    @Test
    @DisplayName("ignores a malformed pair rather than throwing")
    void stalePermissionPaths_malformedPair_isSkipped() {
        // A reference whose node could not be fully resolved must not abort the migration for the
        // references that did resolve.
        List<String[]> referenced = new ArrayList<>();
        referenced.add(new String[]{STALE});
        referenced.add(permission(STALE, STALE_PATH));

        assertThat(StoreDeveloperRoleMigration.stalePermissionPaths(referenced)).containsExactly(STALE_PATH);
    }
}
