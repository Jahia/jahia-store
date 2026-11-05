package org.jahia.modules.forge.graphql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S24 / U10 / D8 — the manage-roles gate must only ever grant/revoke the store-managed roles
 * ({@code store-administrator}, {@code store-developer}, {@code reader}). Any other role — notably
 * {@code site-administrator} and {@code owner} (owner is granted only by the upload action) — must
 * be refused BEFORE any system-session work runs. The allow-list check precedes
 * {@code ForgeSiteAccess.executeAsSystemForSite}, so this is unit-testable without a JCR container.
 * The allowed-role happy path (grant applied) is covered end-to-end by Cypress 07/09.
 */
class ManageRolesMutationExtensionTest {

    @ParameterizedTest
    @ValueSource(strings = {"site-administrator", "owner", "privileged", "editor", "arbitrary-role", ""})
    @DisplayName("grantSiteRole refuses any role outside the store allow-list")
    void grantSiteRole_rejectsDisallowedRole(String role) {
        assertThatThrownBy(() ->
                ManageRolesMutationExtension.grantSiteRole("mysite", role, "alice", PrincipalType.USER))
                .isInstanceOf(ForgeSettingsException.class)
                .hasMessageContaining("Role not permitted");
    }

    @ParameterizedTest
    @ValueSource(strings = {"site-administrator", "owner", "privileged", "arbitrary-role"})
    @DisplayName("revokeSiteRole refuses any role outside the store allow-list")
    void revokeSiteRole_rejectsDisallowedRole(String role) {
        assertThatThrownBy(() ->
                ManageRolesMutationExtension.revokeSiteRole("mysite", role, "alice", PrincipalType.USER))
                .isInstanceOf(ForgeSettingsException.class)
                .hasMessageContaining("Role not permitted");
    }

    @Test
    @DisplayName("the store allow-list contains exactly the three managed roles and never owner/site-administrator")
    void allowListIsTheThreeStoreRoles() {
        assertThat(ManageRolesQueryExtension.FORGE_ROLES)
                .containsExactly("store-administrator", "store-developer", "reader")
                .doesNotContain("owner", "site-administrator");
    }

    @Test
    @DisplayName("directGrantsExcluding keeps only direct site grants for other roles, dropping the revoked role and inherited/DENY entries")
    void directGrantsExcluding_filtersCorrectly() {
        // Arrange: ACL rows are [nodePath, aceType, roleName].
        final String sitePath = "/sites/mysite";
        final List<String[]> grants = Arrays.asList(
                new String[]{sitePath, "GRANT", "store-developer"}, // revoked -> dropped
                new String[]{sitePath, "GRANT", "reader"},          // direct other role -> kept
                new String[]{sitePath, "DENY", "reader"},           // DENY -> dropped
                new String[]{"/ancestor", "GRANT", "store-administrator"} // inherited -> dropped
        );

        // Act
        Set<String> remaining =
                ManageRolesMutationExtension.directGrantsExcluding(grants, sitePath, "store-developer");

        // Assert
        assertThat(remaining).containsExactly("reader");
    }
}
