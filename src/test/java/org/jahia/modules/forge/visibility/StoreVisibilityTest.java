package org.jahia.modules.forge.visibility;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JSTORE-SEC-005 — the one published-only rule that every anonymous path to store content applies (the
 * render filter, the module-list RSS view, the {@code /files} filter, the Maven proxy). The draft state
 * lives on the {@code published} flag of the store elements ({@code jmix:forgeElement}: modules,
 * packages) and of their versions; content under a draft (a version's artifact, a module's icon) is a
 * draft too. Nodes of other types are not governed by the flag, even when they carry a {@code published}
 * property (the security advisory keeps a retired one).
 */
class StoreVisibilityTest {

    private static final String REPO = "/sites/store/contents/modules-repository";

    // ------------------------------------------------------------------------------- node builders

    private static JCRNodeWrapper site() throws Exception {
        JCRNodeWrapper site = mock(JCRNodeWrapper.class);
        when(site.isNodeType("jnt:virtualsite")).thenReturn(true);
        when(site.getPath()).thenReturn("/sites/store");
        return site;
    }

    private static JCRNodeWrapper folder(JCRNodeWrapper parent, String path) throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        when(folder.getPath()).thenReturn(path);
        when(folder.getParent()).thenReturn(parent);
        return folder;
    }

    private static JCRNodeWrapper repository() throws Exception {
        return folder(site(), REPO);
    }

    private static JCRNodeWrapper module(JCRNodeWrapper parent, boolean published, boolean canWrite) throws Exception {
        return flagged(parent, "jmix:forgeElement", REPO + "/m", published, canWrite);
    }

    private static JCRNodeWrapper version(JCRNodeWrapper module, boolean published, boolean canWrite) throws Exception {
        return flagged(module, "jnt:forgeModuleVersion", REPO + "/m/m-1.0", published, canWrite);
    }

    private static JCRNodeWrapper flagged(JCRNodeWrapper parent, String type, String path, boolean published, boolean canWrite)
            throws Exception {
        JCRNodeWrapper node = folder(parent, path);
        when(node.isNodeType(type)).thenReturn(true);
        when(node.hasPermission("jcr:write")).thenReturn(canWrite);
        JCRPropertyWrapper flag = mock(JCRPropertyWrapper.class);
        when(flag.getBoolean()).thenReturn(published);
        when(node.hasProperty("published")).thenReturn(true);
        when(node.getProperty("published")).thenReturn(flag);
        return node;
    }

    private static JCRNodeWrapper file(JCRNodeWrapper parent) throws Exception {
        JCRNodeWrapper file = folder(parent, parent.getPath() + "/m-1.0.jar");
        when(file.isNodeType("jnt:file")).thenReturn(true);
        return file;
    }

    // ------------------------------------------------------------------------------------ the rule

    @Test
    @DisplayName("a published module is public")
    void publishedModuleIsPublic() throws Exception {
        JCRNodeWrapper module = module(repository(), true, false);

        assertThat(StoreVisibility.isPublished(module)).isTrue();
        assertThat(StoreVisibility.isVisibleToCaller(module)).isTrue();
    }

    @Test
    @DisplayName("a draft module is hidden from a caller who cannot write it, visible to its owner")
    void draftModuleHiddenExceptFromItsWriter() throws Exception {
        JCRNodeWrapper anonymousView = module(repository(), false, false);
        JCRNodeWrapper ownerView = module(repository(), false, true);

        assertThat(StoreVisibility.isPublished(anonymousView)).isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(anonymousView)).isFalse();
        assertThat(StoreVisibility.isPublished(ownerView)).as("a feed never lists a draft, even for its owner").isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(ownerView)).isTrue();
    }

    @Test
    @DisplayName("S1: a PUBLISHED version of a DRAFT module is not public (the RSS feed listed it)")
    void publishedVersionOfDraftModuleIsNotPublic() throws Exception {
        JCRNodeWrapper version = version(module(repository(), false, false), true, false);

        assertThat(StoreVisibility.isPublished(version)).isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(version)).isFalse();
    }

    @Test
    @DisplayName("a DRAFT version of a published module is not public")
    void draftVersionOfPublishedModuleIsNotPublic() throws Exception {
        JCRNodeWrapper version = version(module(repository(), true, false), false, false);

        assertThat(StoreVisibility.isPublished(version)).isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(version)).isFalse();
    }

    @Test
    @DisplayName("a published version of a published module is public")
    void publishedVersionOfPublishedModuleIsPublic() throws Exception {
        JCRNodeWrapper version = version(module(repository(), true, false), true, false);

        assertThat(StoreVisibility.isPublished(version)).isTrue();
        assertThat(StoreVisibility.isVisibleToCaller(version)).isTrue();
    }

    @Test
    @DisplayName("S2: the artifact (jnt:file) under a draft version, or under a version of a draft module, is hidden")
    void artifactUnderADraftIsHidden() throws Exception {
        JCRNodeWrapper underDraftVersion = file(version(module(repository(), true, false), false, false));
        JCRNodeWrapper underDraftModule = file(version(module(repository(), false, false), true, false));
        JCRNodeWrapper released = file(version(module(repository(), true, false), true, false));

        assertThat(StoreVisibility.isVisibleToCaller(underDraftVersion)).isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(underDraftModule)).isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(released)).isTrue();
    }

    @Test
    @DisplayName("the icon of a draft module (a file in a plain folder under it) is hidden too")
    void iconOfDraftModuleIsHidden() throws Exception {
        JCRNodeWrapper icon = file(folder(module(repository(), false, false), REPO + "/m/icon"));

        assertThat(StoreVisibility.isVisibleToCaller(icon)).isFalse();
    }

    @Test
    @DisplayName("the owner needs write access on EVERY draft above the node")
    void ownerMustWriteEveryDraft() throws Exception {
        JCRNodeWrapper version = version(module(repository(), false, true), false, false);

        assertThat(StoreVisibility.isVisibleToCaller(version)).isFalse();
    }

    @Test
    @DisplayName("a file with no store element above it (a DAM file) is not governed by the rule")
    void damFileIsNotGoverned() throws Exception {
        JCRNodeWrapper damFile = file(folder(site(), "/sites/store/files/images"));

        assertThat(StoreVisibility.isPublished(damFile)).isTrue();
        assertThat(StoreVisibility.isVisibleToCaller(damFile)).isTrue();
    }

    @Test
    @DisplayName("a node of another type is not governed by its own 'published' property (the advisory's retired box)")
    void otherTypesIgnoreTheirPublishedProperty() throws Exception {
        JCRNodeWrapper advisory = flagged(folder(site(), "/sites/store/contents/security-advisories"),
                "jnt:forgeSecurityAdvisory", "/sites/store/contents/security-advisories/a", false, false);

        assertThat(StoreVisibility.isPublished(advisory)).isTrue();
    }

    @Test
    @DisplayName("the walk stops at the site node")
    void walkStopsAtTheSite() throws Exception {
        JCRNodeWrapper site = site();
        JCRNodeWrapper module = module(folder(site, REPO), true, false);

        StoreVisibility.isVisibleToCaller(module);

        verify(site, never()).getParent();
    }

    @Test
    @DisplayName("a parent the caller cannot read ends the walk; the store elements below it still count")
    void unreadableParentEndsTheWalk() throws Exception {
        JCRNodeWrapper hiddenRepository = mock(JCRNodeWrapper.class);
        when(hiddenRepository.getParent()).thenThrow(new AccessDeniedException());
        JCRNodeWrapper draft = module(hiddenRepository, false, false);
        JCRNodeWrapper published = module(hiddenRepository, true, false);

        assertThat(StoreVisibility.isVisibleToCaller(draft)).isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(published)).isTrue();
    }

    @Test
    @DisplayName("a repository error fails closed: the node is treated as hidden")
    void repositoryErrorFailsClosed() throws Exception {
        JCRNodeWrapper broken = mock(JCRNodeWrapper.class);
        when(broken.getPath()).thenReturn(REPO + "/broken");
        when(broken.isNodeType("jmix:forgeElement")).thenThrow(new RepositoryException("boom"));

        assertThat(StoreVisibility.isPublished(broken)).isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(broken)).isFalse();
    }

    @Test
    @DisplayName("a null node is never public")
    void nullIsNeverPublic() {
        assertThat(StoreVisibility.isPublished(null)).isFalse();
        assertThat(StoreVisibility.isVisibleToCaller(null)).isFalse();
    }

    // ------------------------------------------------------------------------ the store's root folder

    @Test
    @DisplayName("modulesRepositoryPath builds the site's store folder path")
    void modulesRepositoryPath() {
        assertThat(StoreVisibility.modulesRepositoryPath("store")).isEqualTo(REPO);
    }

    @ParameterizedTest
    @ValueSource(strings = {REPO, "/sites/other-store/contents/modules-repository"})
    @DisplayName("isModulesRepository accepts a site's modules-repository folder")
    void isModulesRepositoryAccepts(String path) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getPath()).thenReturn(path);

        assertThat(StoreVisibility.isModulesRepository(node)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/sites/store/contents",
            "/sites/store/contents/security-advisories",
            "/sites/store/contents/modules-repository/org",
            "/sites/store/contents/x/modules-repository",
            "/sites/a/b/contents/modules-repository",
            "/modules-repository"
    })
    @DisplayName("isModulesRepository refuses any other folder")
    void isModulesRepositoryRefuses(String path) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getPath()).thenReturn(path);

        assertThat(StoreVisibility.isModulesRepository(node)).isFalse();
        assertThat(StoreVisibility.isModulesRepository(null)).isFalse();
    }
}
