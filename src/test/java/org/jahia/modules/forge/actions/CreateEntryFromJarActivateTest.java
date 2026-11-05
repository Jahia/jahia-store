package org.jahia.modules.forge.actions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S14 / U5 — CreateEntryFromJar authorization wiring. The upload action must require an
 * authenticated user, the {@code jahiaForgeUploadModule} permission (NOT merely "any authenticated
 * user"), and POST only (CSRF posture). These are set in {@code activate()} and read back through
 * the inherited {@link org.jahia.bin.Action} getters. No Jahia container / mocks needed.
 */
class CreateEntryFromJarActivateTest {

    @Test
    @DisplayName("activate() wires name, auth, jahiaForgeUploadModule permission and POST-only")
    void activateWiresAuthorization() {
        // Arrange
        CreateEntryFromJar action = new CreateEntryFromJar();

        // Act
        action.activate();

        // Assert
        assertThat(action.getName()).isEqualTo("createEntryFromJar");
        assertThat(action.isRequireAuthenticatedUser()).isTrue();
        assertThat(action.getRequiredPermission()).isEqualTo("jahiaForgeUploadModule");
        assertThat(action.getRequiredMethods()).containsExactly("POST");
    }
}
