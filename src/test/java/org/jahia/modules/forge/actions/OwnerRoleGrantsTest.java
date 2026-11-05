package org.jahia.modules.forge.actions;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.jcr.RepositoryException;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the two checks that bound {@link OwnerRoleGrants}' elevated writes: WHERE a grant
 * may land, and WHICH node it may cover.
 *
 * <p>This is the constraint that keeps the SEC-366 fix from trading one over-privilege for
 * another: the owner grant now runs in a <em>system</em> session, so the only thing standing
 * between it and the rest of the site is the requirement that the target live under the
 * repository node the Action already gated with {@code jahiaForgeUploadModule}. A sloppy
 * {@code startsWith} would let a sibling path escape that bound, so the boundary case is pinned
 * down explicitly.
 */
class OwnerRoleGrantsTest {

    private static final String REPO = "/sites/store/contents/modules-repository";
    private static final String UPLOADER = "storedev";

    @Test
    @DisplayName("the scope root itself is within scope")
    void isWithin_rootItself_isTrue() {
        assertThat(OwnerRoleGrants.isWithin(REPO, REPO)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "/sites/store/contents/modules-repository/org/jahia/modules/widget",
            "/sites/store/contents/modules-repository/packages/my-package",
            "/sites/store/contents/modules-repository/org/jahia/widget/widget-1.0"
    })
    @DisplayName("descendants of the upload repository are within scope")
    void isWithin_descendants_areTrue(String path) {
        assertThat(OwnerRoleGrants.isWithin(path, REPO)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // A sibling whose name merely starts with the root's name - the case a bare
            // startsWith() would wrongly accept.
            "/sites/store/contents/modules-repository-archive/widget",
            "/sites/store/contents/modules-repositoryX",
            // Elsewhere in the same site, including the ACL-bearing site node itself.
            "/sites/store/contents/other-folder/widget",
            "/sites/store",
            // Another site, and the platform's own trees.
            "/sites/othersite/contents/modules-repository/widget",
            "/roles/store-developer",
            "/users/devuser"
    })
    @DisplayName("anything outside the upload repository is refused")
    void isWithin_outsideScope_isFalse(String path) {
        assertThat(OwnerRoleGrants.isWithin(path, REPO)).isFalse();
    }

    @Test
    @DisplayName("a null path or root is refused rather than throwing")
    void isWithin_nullArguments_areFalse() {
        assertThat(OwnerRoleGrants.isWithin(null, REPO)).isFalse();
        assertThat(OwnerRoleGrants.isWithin(REPO, null)).isFalse();
        assertThat(OwnerRoleGrants.isWithin(null, null)).isFalse();
    }

    // ── which node a grant may cover ────────────────────────────────────────────
    // The subtree check above says WHERE. It cannot say WHICH: another developer's module sits
    // under the same upload repository, so it passes for a node this upload merely reused.

    @Test
    @DisplayName("a node this upload created is recorded")
    void shouldRecord_newNode_isTrue() {
        assertThat(OwnerRoleGrants.shouldRecord(UPLOADER, true)).isTrue();
    }

    @Test
    @DisplayName("a node that was already there is NOT recorded, whoever uploaded")
    void shouldRecord_existingNode_isFalse() {
        // upsertModuleNode returns the stored node when a module of that name exists, so this is
        // the second developer adding a version to somebody else's module.
        assertThat(OwnerRoleGrants.shouldRecord(UPLOADER, false)).isFalse();
    }

    @Test
    @DisplayName("a guest records nothing, new node or not")
    void shouldRecord_guest_isFalse() {
        assertThat(OwnerRoleGrants.shouldRecord(Constants.GUEST_USERNAME, true)).isFalse();
        assertThat(OwnerRoleGrants.shouldRecord(Constants.GUEST_USERNAME, false)).isFalse();
    }

    // ── recordOwner wires the predicate to the node's own isNew() ──────────────
    // The truth table above states the predicate. These state that recordOwner asks it with the
    // node's real isNew(), so dropping or hard-coding that argument turns them red.

    @Test
    @DisplayName("recordOwner queues a node the upload created")
    void recordOwner_newNode_isQueued() throws RepositoryException {
        final OwnerRoleGrants grants = new OwnerRoleGrants(null, REPO, UPLOADER);

        grants.recordOwner(node("new-module", true));

        assertThat(grants.getPendingIdentifiers()).containsExactly("new-module");
    }

    @Test
    @DisplayName("recordOwner skips a node that was already there, and keeps the new one")
    void recordOwner_reusedNode_isSkipped() throws RepositoryException {
        final OwnerRoleGrants grants = new OwnerRoleGrants(null, REPO, UPLOADER);

        // The second developer's upload: DEV's stored module, then the version created under it.
        grants.recordOwner(node("someone-elses-module", false));
        grants.recordOwner(node("my-new-version", true));

        assertThat(grants.getPendingIdentifiers()).containsExactly("my-new-version");
    }

    @Test
    @DisplayName("recordOwner queues nothing for a guest")
    void recordOwner_guest_queuesNothing() throws RepositoryException {
        final OwnerRoleGrants grants = new OwnerRoleGrants(null, REPO, Constants.GUEST_USERNAME);

        grants.recordOwner(node("new-module", true));

        assertThat(grants.getPendingIdentifiers()).isEmpty();
    }

    /** A node answering only isNew() and getIdentifier(), which is all recordOwner may touch. */
    private static JCRNodeWrapper node(String identifier, boolean isNew) {
        return (JCRNodeWrapper) Proxy.newProxyInstance(
                OwnerRoleGrantsTest.class.getClassLoader(),
                new Class<?>[]{JCRNodeWrapper.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isNew":
                            return isNew;
                        case "getIdentifier":
                            return identifier;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
