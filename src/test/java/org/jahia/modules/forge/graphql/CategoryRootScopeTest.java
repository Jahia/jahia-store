package org.jahia.modules.forge.graphql;

import org.jahia.modules.forge.settings.ForgeSettings;
import org.jahia.modules.forge.settings.ForgeSettingsService;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JSTORE-SEC-001 — a store's root category must be a category of the shared tree
 * {@code /sites/systemsite/categories} (that node itself or below). The category operations run in
 * an ACL-bypassing system session, so the rule is checked when the root is stored
 * ({@code setRootCategory}, S1) and again on every use of the stored value: the read
 * ({@code categorySettings}, S2), the add ({@code addCategory}, S3), the title update and the delete
 * ({@code assertManagedBySite}, S5). A stored root outside the tree reads as "no root" and every write
 * under it is refused.
 */
class CategoryRootScopeTest {

    private static final String SITE = "store";
    private static final String SITE_PATH = "/sites/store";
    private static final String ROOT_UUID = "root-uuid";
    private static final String SHARED_ROOT = "/sites/systemsite/categories/store";

    private JCRSessionWrapper systemSession;
    private ForgeSettingsService service;
    private MockedStatic<JCRTemplate> template;
    private MockedStatic<JCRSessionFactory> factory;
    private MockedStatic<BundleUtils> bundles;

    @BeforeEach
    void wireGate() throws Exception {
        systemSession = mock(JCRSessionWrapper.class);
        when(systemSession.nodeExists(SITE_PATH)).thenReturn(true);

        final JCRSessionWrapper callerSession = mock(JCRSessionWrapper.class);
        ForgeSiteAccessTest.JCRNodeStub.wireAllowedCaller(callerSession, SITE_PATH);

        template = Mockito.mockStatic(JCRTemplate.class);
        final JCRTemplate jcrTemplate = mock(JCRTemplate.class);
        template.when(JCRTemplate::getInstance).thenReturn(jcrTemplate);
        when(jcrTemplate.doExecuteWithSystemSession(any())).thenAnswer(inv -> {
            final JCRCallback<?> cb = inv.getArgument(0);
            return cb.doInJCR(systemSession);
        });

        factory = Mockito.mockStatic(JCRSessionFactory.class);
        final JCRSessionFactory sessionFactory = mock(JCRSessionFactory.class);
        factory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);
        when(sessionFactory.getCurrentUserSession()).thenReturn(callerSession);

        service = mock(ForgeSettingsService.class);
        bundles = Mockito.mockStatic(BundleUtils.class);
        bundles.when(() -> BundleUtils.getOsgiService(eq(ForgeSettingsService.class), isNull())).thenReturn(service);
    }

    @AfterEach
    void closeStatics() {
        bundles.close();
        factory.close();
        template.close();
    }

    private JCRNodeWrapper category(String uuid, String path) throws Exception {
        final JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(systemSession.getNodeByIdentifier(uuid)).thenReturn(node);
        when(node.isNodeType("jnt:category")).thenReturn(true);
        when(node.getIdentifier()).thenReturn(uuid);
        when(node.getPath()).thenReturn(path);
        return node;
    }

    private void storeRoot(String uuid) {
        when(service.get(SITE)).thenReturn(ForgeSettings.builder().rootCategoryUuid(uuid).build());
    }

    // ---- S1: setRootCategory ---------------------------------------------------------------

    @ParameterizedTest(name = "setRootCategory refuses a root at {0}")
    @ValueSource(strings = {"/sites/other/categories/root", "/sites/store/cy-cats", "/sites/systemsite/categoriesX/a",
            "/sites/systemsite/other", "/sites/systemsite", "/foreign"})
    @DisplayName("S1: setRootCategory refuses a category outside /sites/systemsite/categories and stores nothing")
    void setRootCategory_outsideSharedTree_refused(String path) throws Exception {
        category("foreign-uuid", path);
        when(service.get(SITE)).thenReturn(ForgeSettings.builder().build());

        assertThatThrownBy(() -> CategorySettingsMutationExtension.setRootCategory(SITE, "foreign-uuid"))
                .isInstanceOf(ForgeSettingsException.class);
        verify(service, never()).save(anyString(), any());
    }

    @ParameterizedTest(name = "setRootCategory stores a root at {0}")
    @ValueSource(strings = {"/sites/systemsite/categories", SHARED_ROOT, "/sites/systemsite/categories/a/b"})
    @DisplayName("S1: setRootCategory stores a category of /sites/systemsite/categories (the tree root included)")
    void setRootCategory_inSharedTree_stored(String path) throws Exception {
        category(ROOT_UUID, path);
        when(service.get(SITE)).thenReturn(ForgeSettings.builder().build());

        assertThat(CategorySettingsMutationExtension.setRootCategory(SITE, ROOT_UUID)).isTrue();

        final ArgumentCaptor<ForgeSettings> saved = ArgumentCaptor.forClass(ForgeSettings.class);
        verify(service).save(eq(SITE), saved.capture());
        assertThat(saved.getValue().getRootCategoryUuid()).isEqualTo(ROOT_UUID);
    }

    // ---- S3: addCategory under a stored foreign root ---------------------------------------

    @Test
    @DisplayName("S3: addCategory refuses to add under a stored root outside the shared tree")
    void addForgeCategory_storedForeignRoot_refused() throws Exception {
        final JCRNodeWrapper root = category(ROOT_UUID, "/sites/other/categories/root");
        storeRoot(ROOT_UUID);

        assertThatThrownBy(() -> CategorySettingsMutationExtension.addForgeCategory(SITE, "widgets"))
                .isInstanceOf(ForgeSettingsException.class);
        verify(root, never()).addNode(anyString(), anyString());
        verify(systemSession, never()).save();
    }

    // ---- S5: delete / title update keyed to a stored foreign root --------------------------

    @Test
    @DisplayName("S5: deleteCategory refuses a child of a stored root outside the shared tree")
    void deleteForgeCategory_underStoredForeignRoot_refused() throws Exception {
        category(ROOT_UUID, "/sites/other/categories/root");
        final JCRNodeWrapper victim = category("victim", "/sites/other/categories/root/victim");
        storeRoot(ROOT_UUID);

        assertThatThrownBy(() -> CategorySettingsMutationExtension.deleteForgeCategory(SITE, "victim"))
                .isInstanceOf(ForgeSettingsException.class);
        verify(victim, never()).remove();
        verify(systemSession, never()).save();
    }

    @Test
    @DisplayName("S5: deleteCategory still deletes a child of a root in the shared tree")
    void deleteForgeCategory_underSharedRoot_deleted() throws Exception {
        category(ROOT_UUID, SHARED_ROOT);
        final JCRNodeWrapper child = category("child", SHARED_ROOT + "/child");
        storeRoot(ROOT_UUID);

        assertThat(CategorySettingsMutationExtension.deleteForgeCategory(SITE, "child")).isTrue();
        verify(child).remove();
        verify(systemSession).save();
    }

    @Test
    @DisplayName("S5: updateCategoryTitles refuses a child of a stored root outside the shared tree")
    void updateForgeCategoryTitles_underStoredForeignRoot_refused() throws Exception {
        category(ROOT_UUID, "/sites/other/categories/root");
        final JCRNodeWrapper victim = category("victim", "/sites/other/categories/root/victim");
        storeRoot(ROOT_UUID);

        assertThatThrownBy(() -> CategorySettingsMutationExtension.updateForgeCategoryTitles(SITE, "victim",
                Collections.singletonList(new InputCategoryTitle("en", "Hijacked"))))
                .isInstanceOf(ForgeSettingsException.class);
        verify(victim, never()).setProperty(anyString(), anyString());
    }

    // ---- S2: categorySettings read --------------------------------------------------------

    private void wireSiteForRead() throws Exception {
        final JCRSiteNode site = mock(JCRSiteNode.class);
        when(systemSession.getNode(SITE_PATH)).thenReturn(site);
        when(site.getLanguagesAsLocales()).thenReturn(Collections.singletonList(Locale.ENGLISH));
    }

    @ParameterizedTest(name = "categorySettings reads a stored root at {0} as no root")
    @ValueSource(strings = {"/sites/other/categories/root", "/sites/store/cy-cats", "/sites/systemsite/categoriesX/a"})
    @DisplayName("S2: categorySettings reads a stored root outside the shared tree as no root, without reading it")
    void getForgeCategorySettings_storedForeignRoot_readsAsNoRoot(String path) throws Exception {
        wireSiteForRead();
        final JCRNodeWrapper root = category(ROOT_UUID, path);
        storeRoot(ROOT_UUID);

        final GqlForgeCategorySettings settings = CategorySettingsQueryExtension.getForgeCategorySettings(SITE);

        assertThat(settings).isNotNull();
        assertThat(settings.getRootCategoryUuid()).isNull();
        assertThat(settings.getRootCategoryPath()).isNull();
        assertThat(settings.getRootCategoryDisplayName()).isNull();
        assertThat(settings.getCategories()).isEmpty();
        assertThat(settings.getSiteLanguages()).containsExactly("en");
        verify(root, never()).getNodes();
        verify(root, never()).getDisplayableName();
    }

    @Test
    @DisplayName("S2: categorySettings reads a stored root that is not a jnt:category as no root")
    void getForgeCategorySettings_storedNonCategoryRoot_readsAsNoRoot() throws Exception {
        wireSiteForRead();
        final JCRNodeWrapper root = category(ROOT_UUID, SHARED_ROOT);
        when(root.isNodeType("jnt:category")).thenReturn(false);
        storeRoot(ROOT_UUID);

        final GqlForgeCategorySettings settings = CategorySettingsQueryExtension.getForgeCategorySettings(SITE);

        assertThat(settings.getRootCategoryUuid()).isNull();
        verify(root, never()).getNodes();
    }

    @Test
    @DisplayName("S2: categorySettings returns a stored root of the shared tree")
    void getForgeCategorySettings_sharedRoot_returned() throws Exception {
        wireSiteForRead();
        final JCRNodeWrapper root = category(ROOT_UUID, SHARED_ROOT);
        when(root.getDisplayableName()).thenReturn("Store");
        final JCRNodeIteratorWrapper noChildren = mock(JCRNodeIteratorWrapper.class);
        when(noChildren.hasNext()).thenReturn(false);
        when(root.getNodes()).thenReturn(noChildren);
        storeRoot(ROOT_UUID);

        final GqlForgeCategorySettings settings = CategorySettingsQueryExtension.getForgeCategorySettings(SITE);

        assertThat(settings.getRootCategoryUuid()).isEqualTo(ROOT_UUID);
        assertThat(settings.getRootCategoryPath()).isEqualTo(SHARED_ROOT);
        assertThat(settings.getRootCategoryDisplayName()).isEqualTo("Store");
        assertThat(settings.getCategories()).isEmpty();
    }
}
