package org.jahia.modules.forge.security;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRStoreService;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRWorkspaceWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JSTORE-SEC-017: the security flags of a module version (securityFix, securitySeverity, securityReferences,
 * securityNote) are a claim that securityUpdates.json republishes to every Jahia instance. Only the owner of
 * the module (the {@code owner} role on the module node, which an upload grants) or a store administrator
 * ({@code jahiaForgeModerateModule}) may write them. The store-developer role writes live content site-wide,
 * so without this check any developer could flag or unflag any module's versions.
 *
 * <p>Every accepted write stamps who made it and when ({@code securityFlaggedBy}, {@code securityFlaggedDate}),
 * and the stamps themselves cannot be chosen by the writer.
 */
class SecurityFlagInterceptorTest {

    private static final String WORKSPACE = "live";
    private static final String MODULE_ID = "module-uuid";
    private static final Instant NOW = Instant.parse("2026-11-18T10:15:30Z");

    private JCRTemplate jcrTemplate;
    private JCRSessionWrapper session;
    private JCRSessionWrapper systemSession;
    private ValueFactory valueFactory;
    private JCRNodeWrapper version;
    private JCRNodeWrapper module;
    private JCRNodeWrapper systemModule;
    private final Map<String, List<String[]>> moduleAcl = new LinkedHashMap<>();
    private SecurityFlagInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        jcrTemplate = mock(JCRTemplate.class);
        session = mock(JCRSessionWrapper.class);
        systemSession = mock(JCRSessionWrapper.class);
        valueFactory = mock(ValueFactory.class);
        version = mock(JCRNodeWrapper.class);
        module = mock(JCRNodeWrapper.class);
        systemModule = mock(JCRNodeWrapper.class);

        JCRWorkspaceWrapper workspace = mock(JCRWorkspaceWrapper.class);
        when(workspace.getName()).thenReturn(WORKSPACE);
        when(session.getWorkspace()).thenReturn(workspace);
        when(session.getValueFactory()).thenReturn(valueFactory);
        when(valueFactory.createValue(anyString())).thenAnswer(inv -> stringValue(inv.getArgument(0)));
        when(valueFactory.createValue(any(Calendar.class))).thenAnswer(inv -> dateValue(inv.getArgument(0)));
        actingAs("dev");

        when(version.isNodeType(SecurityFlagInterceptor.VERSION_TYPE)).thenReturn(true);
        when(version.getSession()).thenReturn(session);
        when(version.getParent()).thenReturn(module);
        when(version.getPath()).thenReturn("/sites/store/contents/modules-repository/m/m-1.0.0");
        when(module.getIdentifier()).thenReturn(MODULE_ID);

        when(jcrTemplate.doExecuteWithSystemSessionAsUser(isNull(), eq(WORKSPACE), isNull(), any()))
                .thenAnswer(inv -> inv.<JCRCallback<?>>getArgument(3).doInJCR(systemSession));
        when(systemSession.getNodeByIdentifier(MODULE_ID)).thenReturn(systemModule);
        when(systemModule.getAclEntries()).thenReturn(moduleAcl);

        interceptor = new SecurityFlagInterceptor(jcrTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------------------ who may write

    @ParameterizedTest
    @ValueSource(strings = {"securityFix", "securitySeverity", "securityNote"})
    @DisplayName("a store developer who does not own the module cannot set a security flag of its version")
    void nonOwnerCannotSetFlag(String property) {
        Value value = stringValue("true");

        assertThatThrownBy(() -> interceptor.beforeSetValue(version, property, definition(property), value))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoStamp();
    }

    @Test
    @DisplayName("a non-owner cannot set the references of another developer's version")
    void nonOwnerCannotSetReferences() {
        Value[] values = {stringValue("CVE-2026-1234")};

        assertThatThrownBy(() -> interceptor.beforeSetValues(version, "securityReferences", definition("securityReferences"), values))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoStamp();
    }

    @Test
    @DisplayName("a non-owner cannot unflag a version: neither removing a flag property nor setting it to null")
    void nonOwnerCannotUnflag() {
        assertThatThrownBy(() -> interceptor.beforeRemove(version, "securityNote", definition("securityNote")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoStamp();
    }

    @Test
    @DisplayName("the owner of a version only (a second uploader, SEC-381) is not the owner of the module")
    void versionOwnerIsNotModuleOwner() throws Exception {
        Map<String, List<String[]>> versionAcl = new LinkedHashMap<>();
        versionAcl.put("u:dev", Collections.singletonList(new String[]{"/v", "GRANT", "owner"}));
        when(version.getAclEntries()).thenReturn(versionAcl);

        assertThatThrownBy(() -> interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("another role granted on the module (not owner) does not make the writer its owner")
    void otherRoleIsNotOwner() {
        moduleAcl.put("u:dev", Collections.singletonList(new String[]{"/m", "GRANT", "editor"}));

        assertThatThrownBy(() -> interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an owner grant to another user does not count")
    void anotherUsersOwnerGrant() {
        moduleAcl.put("u:alice", Collections.singletonList(new String[]{"/m", "GRANT", "owner"}));

        assertThatThrownBy(() -> interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a DENY of owner nearer the module than the GRANT wins")
    void nearerDenyWins() {
        moduleAcl.put("u:dev", Arrays.asList(new String[]{"/m", "DENY", "owner"}, new String[]{"/", "GRANT", "owner"}));

        assertThatThrownBy(() -> interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a module the system session cannot find (not saved yet) has no owner")
    void unsavedModuleHasNoOwner() throws Exception {
        when(systemSession.getNodeByIdentifier(MODULE_ID)).thenThrow(new ItemNotFoundException());

        assertThatThrownBy(() -> interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a guest, or a session without a user, is refused")
    void guestRefused() {
        actingAs("guest");
        assertThatThrownBy(() -> interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true")))
                .isInstanceOf(AccessDeniedException.class);

        when(session.getUser()).thenReturn(null);
        assertThatThrownBy(() -> interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the owner of the module sets a flag: accepted unchanged, and stamped with who and when")
    void ownerSetsFlag() throws Exception {
        ownerOfModule("dev");
        Value value = stringValue("true");

        Value result = interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), value);

        assertThat(result).isSameAs(value);
        verify(version).setProperty(SecurityFlagInterceptor.FLAGGED_BY, "dev");
        ArgumentCaptor<Calendar> when = ArgumentCaptor.forClass(Calendar.class);
        verify(version).setProperty(eq(SecurityFlagInterceptor.FLAGGED_DATE), when.capture());
        assertThat(when.getValue().toInstant()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("the owner of the module sets references and removes a note: both accepted and stamped")
    void ownerSetsReferencesAndRemovesNote() throws Exception {
        ownerOfModule("dev");
        Value[] values = {stringValue("CVE-2026-1234")};

        assertThat(interceptor.beforeSetValues(version, "securityReferences", definition("securityReferences"), values))
                .isSameAs(values);
        interceptor.beforeRemove(version, "securityNote", definition("securityNote"));

        verify(version, org.mockito.Mockito.times(2)).setProperty(SecurityFlagInterceptor.FLAGGED_BY, "dev");
    }

    @Test
    @DisplayName("an owner grant inherited from above the module counts")
    void inheritedOwnerGrant() throws Exception {
        moduleAcl.put("u:dev", Collections.singletonList(new String[]{"/sites/store/contents/modules-repository", "GRANT", "owner"}));

        interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true"));

        verify(version).setProperty(SecurityFlagInterceptor.FLAGGED_BY, "dev");
    }

    @Test
    @DisplayName("a store administrator (jahiaForgeModerateModule on the module) flags any module, stamped")
    void storeAdministratorFlagsAnyModule() throws Exception {
        actingAs("admin1");
        when(module.hasPermission(SecurityFlagInterceptor.MODERATE_PERMISSION)).thenReturn(true);

        interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), stringValue("true"));

        verify(version).setProperty(SecurityFlagInterceptor.FLAGGED_BY, "admin1");
    }

    @Test
    @DisplayName("a system session (publication, import) passes through and keeps the stamps it copies")
    void systemSessionPassesThrough() throws Exception {
        when(session.isSystem()).thenReturn(true);
        Value value = stringValue("true");
        Value by = stringValue("alice");

        assertThat(interceptor.beforeSetValue(version, "securityFix", definition("securityFix"), value)).isSameAs(value);
        assertThat(interceptor.beforeSetValue(version, SecurityFlagInterceptor.FLAGGED_BY,
                definition(SecurityFlagInterceptor.FLAGGED_BY), by)).isSameAs(by);
        interceptor.beforeRemove(version, SecurityFlagInterceptor.FLAGGED_BY, definition(SecurityFlagInterceptor.FLAGGED_BY));

        verifyNoStamp();
        verifyNoInteractions(jcrTemplate);
    }

    // ------------------------------------------------------------------------------ the stamps

    @Test
    @DisplayName("an authorized writer cannot choose the stamps: they always say who wrote and when")
    void stampsCannotBeForged() throws Exception {
        ownerOfModule("dev");
        Calendar past = Calendar.getInstance();
        past.setTimeInMillis(0);

        Value by = interceptor.beforeSetValue(version, SecurityFlagInterceptor.FLAGGED_BY,
                definition(SecurityFlagInterceptor.FLAGGED_BY), stringValue("root"));
        Value date = interceptor.beforeSetValue(version, SecurityFlagInterceptor.FLAGGED_DATE,
                definition(SecurityFlagInterceptor.FLAGGED_DATE), dateValue(past));

        assertThat(by.getString()).isEqualTo("dev");
        assertThat(date.getDate().toInstant()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a non-owner cannot write the stamps of another developer's version")
    void nonOwnerCannotWriteStamps() {
        assertThatThrownBy(() -> interceptor.beforeSetValue(version, SecurityFlagInterceptor.FLAGGED_BY,
                definition(SecurityFlagInterceptor.FLAGGED_BY), stringValue("dev")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("outside a system session, nobody removes the stamps, not even the owner")
    void stampsCannotBeRemoved() {
        ownerOfModule("dev");

        assertThatThrownBy(() -> interceptor.beforeRemove(version, SecurityFlagInterceptor.FLAGGED_BY,
                definition(SecurityFlagInterceptor.FLAGGED_BY)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> interceptor.beforeSetValue(version, SecurityFlagInterceptor.FLAGGED_DATE,
                definition(SecurityFlagInterceptor.FLAGGED_DATE), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------------------ scope and registration

    @ParameterizedTest
    @ValueSource(strings = {"securityFix", "securitySeverity", "securityReferences", "securityNote",
            "securityFlaggedBy", "securityFlaggedDate"})
    @DisplayName("applies to the security flags and their stamps on module versions")
    void appliesToSecurityProperties(String property) throws Exception {
        assertThat(interceptor.canApplyOnProperty(version, definition(property))).isTrue();
    }

    @Test
    @DisplayName("does not apply to other properties, nor to other node types")
    void doesNotApplyElsewhere() throws Exception {
        JCRNodeWrapper other = mock(JCRNodeWrapper.class);

        assertThat(interceptor.canApplyOnProperty(version, definition("changeLog"))).isFalse();
        assertThat(interceptor.canApplyOnProperty(version, definition("published"))).isFalse();
        assertThat(interceptor.canApplyOnProperty(other, definition("securityFix"))).isFalse();
    }

    @Test
    @DisplayName("registers with the JCR store on activation and unregisters on deactivation")
    void registration() {
        JCRStoreService store = mock(JCRStoreService.class);

        interceptor.register(store);
        interceptor.deactivate();

        org.mockito.InOrder order = inOrder(store);
        order.verify(store).removeInterceptor(interceptor);
        order.verify(store).addInterceptor(interceptor);
        order.verify(store).removeInterceptor(interceptor);
    }

    // ------------------------------------------------------------------------------ helpers

    private void actingAs(String username) {
        JahiaUser user = mock(JahiaUser.class);
        when(user.getUsername()).thenReturn(username);
        when(user.getName()).thenReturn(username);
        when(session.getUser()).thenReturn(user);
    }

    private void ownerOfModule(String username) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"/sites/store/contents/modules-repository/m", "GRANT", "owner"});
        moduleAcl.put("u:" + username, rows);
    }

    private void verifyNoStamp() {
        try {
            verify(version, never()).setProperty(eq(SecurityFlagInterceptor.FLAGGED_BY), anyString());
            verify(version, never()).setProperty(eq(SecurityFlagInterceptor.FLAGGED_DATE), any(Calendar.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ExtendedPropertyDefinition definition(String name) {
        ExtendedPropertyDefinition definition = mock(ExtendedPropertyDefinition.class);
        when(definition.getName()).thenReturn(name);
        return definition;
    }

    private static Value stringValue(String s) {
        Value value = mock(Value.class);
        try {
            when(value.getString()).thenReturn(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return value;
    }

    private static Value dateValue(Calendar c) {
        Value value = mock(Value.class);
        try {
            when(value.getDate()).thenReturn(c);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return value;
    }
}
