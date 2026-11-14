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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

import javax.jcr.RepositoryException;
import java.io.File;
import java.nio.file.Path;

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
 * The store-administrator role gains Jahia's {@code publish} permission (needed to publish security
 * advisories). Roles live in the edit workspace only ({@code jnt:role} is {@code jmix:nolive}); Jahia reads a
 * role's {@code j:permissionNames} first and falls back to {@code j:permissions}
 * ({@code AccessManagerUtils.getPrivileges}), so the migration edits whichever the role carries.
 */
class StoreAdministratorPublishMigrationTest {

    private static final String ROLE = "/roles/store-administrator";
    private static final String NAMES = "j:permissionNames";

    private JCRSessionWrapper session;
    private JCRNodeWrapper role;
    private StoreAdministratorPublishMigration migration;
    private MockedStatic<RBACUtils> rbac;
    private MockedStatic<AccessManagerUtils> accessManager;
    private BundleContext context;

    @TempDir
    Path dataArea;

    @BeforeEach
    void setUp() throws Exception {
        JCRTemplate jcrTemplate = mock(JCRTemplate.class);
        session = mock(JCRSessionWrapper.class);
        role = mock(JCRNodeWrapper.class);
        when(jcrTemplate.doExecuteWithSystemSessionAsUser(isNull(), eq("default"), isNull(), any()))
                .thenAnswer(inv -> inv.<JCRCallback<?>>getArgument(3).doInJCR(session));
        when(session.nodeExists(ROLE)).thenReturn(true);
        when(session.getNode(ROLE)).thenReturn(role);
        migration = new StoreAdministratorPublishMigration(jcrTemplate);
        context = mock(BundleContext.class);
        Bundle bundle = mock(Bundle.class);
        when(context.getBundle()).thenReturn(bundle);
        when(bundle.getVersion()).thenReturn(new Version("5.2.0"));
        when(context.getDataFile(anyString())).thenAnswer(inv -> dataArea.resolve(inv.<String>getArgument(0)).toFile());
        rbac = mockStatic(RBACUtils.class);
        accessManager = mockStatic(AccessManagerUtils.class);
    }

    @AfterEach
    void tearDown() {
        rbac.close();
        accessManager.close();
    }

    @Test
    @DisplayName("adds publish to j:permissionNames, keeping every other permission, and saves")
    void addsPublishToPermissionNames() throws Exception {
        names("jahiaForgeModerateModule", "jahiaForgeUploadModule", "jcr:all_live", "jcr:all_default");

        migration.activate(context);

        verify(role).setProperty(NAMES, new String[]{
                "jahiaForgeModerateModule", "jahiaForgeUploadModule", "jcr:all_live", "jcr:all_default", "publish"});
        verify(session).save();
        accessManager.verify(AccessManagerUtils::flushPrivilegesInRoles);
    }

    @Test
    @DisplayName("is idempotent: a role that already has publish is not written")
    void alreadyPresent() throws Exception {
        names("jcr:all_default", "publish");

        migration.activate(context);

        verify(role, never()).setProperty(anyString(), any(String[].class));
        verify(session, never()).save();
    }

    @Test
    @DisplayName("a role holding only j:permissions references gets a reference to the publish permission")
    void addsReferenceWhenNoPermissionNames() throws Exception {
        when(role.hasProperty("j:permissions")).thenReturn(true);
        rbac.when(() -> RBACUtils.grantPermissionToRole("/permissions/repository-permissions/publish", ROLE, session))
                .thenReturn(true);

        migration.activate(context);

        rbac.verify(() -> RBACUtils.grantPermissionToRole("/permissions/repository-permissions/publish", ROLE, session));
        verify(session).save();
    }

    @Test
    @DisplayName("a role whose j:permissions already references publish is not saved")
    void referenceAlreadyPresent() throws Exception {
        when(role.hasProperty("j:permissions")).thenReturn(true);
        rbac.when(() -> RBACUtils.grantPermissionToRole(anyString(), anyString(), any())).thenReturn(false);

        migration.activate(context);

        verify(session, never()).save();
    }

    @Test
    @DisplayName("no role yet (fresh install before the import), or a role with no permission list: nothing written")
    void nothingToMigrate() throws Exception {
        when(role.hasProperty(anyString())).thenReturn(false);
        migration.activate(context);
        when(session.nodeExists(ROLE)).thenReturn(false);
        migration.activate(context);

        verify(role, never()).setProperty(anyString(), any(String[].class));
        verify(session, never()).save();
        rbac.verifyNoInteractions();
    }

    @Test
    @DisplayName("a repository failure never fails the activation")
    void failureIsAbsorbed() throws Exception {
        names("jcr:all_default");
        when(role.setProperty(anyString(), any(String[].class))).thenThrow(new RepositoryException("boom"));

        assertThatCode(() -> migration.activate(context)).doesNotThrowAnyException();
        verify(session, never()).save();
    }

    @Test
    @DisplayName("runs once per module version: publish removed by an administrator afterwards is not re-added")
    void runsOncePerModuleVersion() throws Exception {
        names("jcr:all_default");
        migration.activate(context);
        verify(session).save();

        names("jcr:all_default");
        migration.activate(context);

        verify(role, org.mockito.Mockito.times(1)).setProperty(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("a role not imported yet is not marked done: the next start applies the migration")
    void noRoleIsRetried() throws Exception {
        when(session.nodeExists(ROLE)).thenReturn(false);
        migration.activate(context);

        when(session.nodeExists(ROLE)).thenReturn(true);
        names("jcr:all_default");
        migration.activate(context);

        verify(session).save();
    }

    @Test
    @DisplayName("a failed migration is not marked done")
    void failureIsRetried() throws Exception {
        names("jcr:all_default");
        when(role.setProperty(anyString(), any(String[].class))).thenThrow(new RepositoryException("boom"));
        migration.activate(context);

        File[] marks = dataArea.toFile().listFiles();
        assertThat(marks).isEmpty();
    }

    @Test
    @DisplayName("withPublish appends publish when missing, and answers the same array when it is there")
    void withPublish() {
        assertThat(StoreAdministratorPublishMigration.withPublish(new String[]{"a", "b"}))
                .containsExactly("a", "b", "publish");
        String[] present = {"publish", "b"};
        assertThat(StoreAdministratorPublishMigration.withPublish(present)).isSameAs(present);
        assertThat(StoreAdministratorPublishMigration.withPublish(new String[0])).containsExactly("publish");
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
}
