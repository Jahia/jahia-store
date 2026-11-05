package org.jahia.modules.forge.graphql;

import org.jahia.modules.forge.settings.ForgeSettings;
import org.jahia.modules.forge.settings.ForgeSettingsService;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S25 / U9 — the category mutations run in an ACL-bypassing system session, so a caller-supplied
 * category UUID must be re-validated: it must resolve to a real {@code jnt:category}
 * ({@code resolveCategory}) AND live inside THIS site's configured root-category subtree
 * ({@code assertManagedBySite}). Both are private static; invoked by reflection.
 */
class CategorySettingsMutationExtensionTest {

    private static Method declared(String name, Class<?>... params) throws Exception {
        Method m = CategorySettingsMutationExtension.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    // ---- resolveCategory type / existence check --------------------------------------------

    @Test
    @DisplayName("resolveCategory rejects a UUID that is not a jnt:category")
    void resolveCategory_nonCategory_rejected() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(session.getNodeByIdentifier("uuid-1")).thenReturn(node);
        when(node.isNodeType("jnt:category")).thenReturn(false);

        assertThatThrownBy(() ->
                declared("resolveCategory", JCRSessionWrapper.class, String.class).invoke(null, session, "uuid-1"))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("resolveCategory maps a missing UUID to access-denied (no leak)")
    void resolveCategory_missing_rejected() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNodeByIdentifier("ghost")).thenThrow(new ItemNotFoundException("ghost"));

        assertThatThrownBy(() ->
                declared("resolveCategory", JCRSessionWrapper.class, String.class).invoke(null, session, "ghost"))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("resolveCategory returns a valid category node")
    void resolveCategory_valid_returnsNode() throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(session.getNodeByIdentifier("cat")).thenReturn(node);
        when(node.isNodeType("jnt:category")).thenReturn(true);

        Object result = declared("resolveCategory", JCRSessionWrapper.class, String.class)
                .invoke(null, session, "cat");
        assertThat(result).isSameAs(node);
    }

    // ---- assertManagedBySite subtree confinement -------------------------------------------

    private static JCRSessionWrapper sessionWithRoot(String rootUuid, String rootPath) throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(session.getNodeByIdentifier(rootUuid)).thenReturn(root);
        when(root.isNodeType("jnt:category")).thenReturn(true);
        when(root.getPath()).thenReturn(rootPath);
        return session;
    }

    private static ForgeSettingsService serviceWithRoot(String rootUuid) {
        ForgeSettingsService svc = mock(ForgeSettingsService.class);
        when(svc.get("store")).thenReturn(ForgeSettings.builder().rootCategoryUuid(rootUuid).build());
        return svc;
    }

    @Test
    @DisplayName("assertManagedBySite rejects a category outside the site's root-category subtree")
    void assertManagedBySite_foreignCategory_rejected() throws Exception {
        JCRSessionWrapper session = sessionWithRoot("root-uuid", "/sites/store/categories/root");
        JCRNodeWrapper foreign = mock(JCRNodeWrapper.class);
        when(foreign.getPath()).thenReturn("/sites/other/categories/root/x");
        ForgeSettingsService svc = serviceWithRoot("root-uuid");

        try (MockedStatic<BundleUtils> b = org.mockito.Mockito.mockStatic(BundleUtils.class)) {
            b.when(() -> BundleUtils.getOsgiService(eq(ForgeSettingsService.class), isNull()))
                    .thenReturn(svc);

            assertThatThrownBy(() ->
                    declared("assertManagedBySite", JCRSessionWrapper.class, String.class, JCRNodeWrapper.class)
                            .invoke(null, session, "store", foreign))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    @DisplayName("assertManagedBySite accepts a category inside the site's root-category subtree")
    void assertManagedBySite_ownCategory_accepted() throws Exception {
        JCRSessionWrapper session = sessionWithRoot("root-uuid", "/sites/store/categories/root");
        JCRNodeWrapper child = mock(JCRNodeWrapper.class);
        when(child.getPath()).thenReturn("/sites/store/categories/root/child");
        ForgeSettingsService svc = serviceWithRoot("root-uuid");

        try (MockedStatic<BundleUtils> b = org.mockito.Mockito.mockStatic(BundleUtils.class)) {
            b.when(() -> BundleUtils.getOsgiService(eq(ForgeSettingsService.class), isNull()))
                    .thenReturn(svc);

            // No exception expected.
            declared("assertManagedBySite", JCRSessionWrapper.class, String.class, JCRNodeWrapper.class)
                    .invoke(null, session, "store", child);
        }
    }
}
