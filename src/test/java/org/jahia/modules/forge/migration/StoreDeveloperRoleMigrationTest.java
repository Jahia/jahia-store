package org.jahia.modules.forge.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the decision the SEC-366 role migration makes: given the permissions the
 * {@code store-developer} role currently references, which ones must be revoked.
 *
 * <p>Kept as a pure function over {@code [name, path]} pairs so the choice is testable without a
 * repository — the JCR shell around it (resolving weak references, calling
 * {@code RBACUtils.revokePermissionFromRole}, saving) is covered by the e2e harness instead.
 *
 * <p>The point being pinned down is that the migration removes <em>only</em>
 * {@code jcr:modifyAccessControl_live}. A migration that over-removes would silently break
 * uploads on every existing installation, which is a worse outcome than the vulnerability it is
 * closing.
 */
class StoreDeveloperRoleMigrationTest {

    private static final String STALE = "jcr:modifyAccessControl_live";
    private static final String STALE_PATH = "/permissions/repository-permissions/"
            + "accessControl-management/jcr:modifyAccessControl_live";

    private static String[] permission(String name, String path) {
        return new String[]{name, path};
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
