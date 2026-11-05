package org.jahia.modules.forge.graphql;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S22 / S23(flow) / U9 — {@code ForgeSiteAccess.executeAsSystemForSite} is the shared authorization
 * guard the category and manage-roles mutations run through. Even though the work runs in an
 * ACL-bypassing SYSTEM session, the permission ({@code siteAdminForgeSettings}) is re-checked
 * against the CALLER's own session; without it the callback must never run. It also validates the
 * siteKey FIRST, so a path-altering key aborts before any system session is opened.
 */
class ForgeSiteAccessTest {

    private static final String PERMISSION = "siteAdminForgeSettings";

    @SuppressWarnings("unchecked")
    private static <T> JCRCallback<T> callbackReturning(T value) throws Exception {
        JCRCallback<T> work = mock(JCRCallback.class);
        when(work.doInJCR(any())).thenReturn(value);
        return work;
    }

    @Test
    @DisplayName("S22: a caller WITHOUT the permission is denied and the callback never runs")
    void callerWithoutPermission_denied() throws Exception {
        JCRSessionWrapper systemSession = mock(JCRSessionWrapper.class);
        when(systemSession.nodeExists("/sites/store")).thenReturn(true);
        JCRSessionWrapper callerSession = mock(JCRSessionWrapper.class);
        JCRNodeStub.wireDeniedCaller(callerSession, "/sites/store");
        JCRCallback<Boolean> work = callbackReturning(Boolean.TRUE);

        try (MockedStatic<JCRTemplate> t = org.mockito.Mockito.mockStatic(JCRTemplate.class);
             MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            wireTemplate(t, systemSession);
            wireCallerFactory(f, callerSession);

            assertThatThrownBy(() ->
                    ForgeSiteAccess.executeAsSystemForSite("store", PERMISSION, "failed for site ", work))
                    .isInstanceOf(ForgeSettingsException.class);
        }

        verify(work, never()).doInJCR(any());
    }

    @Test
    @DisplayName("S22: a caller WITH the permission runs the callback in the system session")
    void callerWithPermission_runsCallback() throws Exception {
        JCRSessionWrapper systemSession = mock(JCRSessionWrapper.class);
        when(systemSession.nodeExists("/sites/store")).thenReturn(true);
        JCRSessionWrapper callerSession = mock(JCRSessionWrapper.class);
        JCRNodeStub.wireAllowedCaller(callerSession, "/sites/store");
        JCRCallback<Boolean> work = callbackReturning(Boolean.TRUE);

        Boolean result;
        try (MockedStatic<JCRTemplate> t = org.mockito.Mockito.mockStatic(JCRTemplate.class);
             MockedStatic<JCRSessionFactory> f = org.mockito.Mockito.mockStatic(JCRSessionFactory.class)) {
            wireTemplate(t, systemSession);
            wireCallerFactory(f, callerSession);
            result = ForgeSiteAccess.executeAsSystemForSite("store", PERMISSION, "failed for site ", work);
        }

        assertThat(result).isTrue();
        verify(work, times(1)).doInJCR(systemSession);
    }

    @Test
    @DisplayName("S23(flow): a path-altering siteKey aborts BEFORE any system session is opened")
    void invalidSiteKey_abortsBeforeSystemSession() throws Exception {
        JCRCallback<Boolean> work = callbackReturning(Boolean.TRUE);

        try (MockedStatic<JCRTemplate> t = org.mockito.Mockito.mockStatic(JCRTemplate.class)) {
            JCRTemplate template = mock(JCRTemplate.class);
            t.when(JCRTemplate::getInstance).thenReturn(template);

            assertThatThrownBy(() ->
                    ForgeSiteAccess.executeAsSystemForSite("../..", PERMISSION, "failed for site ", work))
                    .isInstanceOf(ForgeSettingsException.class)
                    .hasMessageContaining("Invalid site key");

            // No system session was ever opened, so no ACL-bypassing work started.
            verify(template, never()).doExecuteWithSystemSession(any());
        }
        verify(work, never()).doInJCR(any());
    }

    // --- wiring helpers ---------------------------------------------------------------------

    private static void wireTemplate(MockedStatic<JCRTemplate> t, JCRSessionWrapper systemSession) throws Exception {
        JCRTemplate template = mock(JCRTemplate.class);
        t.when(JCRTemplate::getInstance).thenReturn(template);
        when(template.doExecuteWithSystemSession(any())).thenAnswer(inv -> {
            JCRCallback<?> cb = inv.getArgument(0);
            return cb.doInJCR(systemSession);
        });
    }

    private static void wireCallerFactory(MockedStatic<JCRSessionFactory> f, JCRSessionWrapper callerSession) throws Exception {
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        f.when(JCRSessionFactory::getInstance).thenReturn(factory);
        when(factory.getCurrentUserSession()).thenReturn(callerSession);
    }

    /** Small helper so both packages' tests wire caller ACLs the same way. */
    static final class JCRNodeStub {
        private JCRNodeStub() {
        }

        static void wireAllowedCaller(JCRSessionWrapper caller, String sitePath) throws Exception {
            org.jahia.services.content.JCRNodeWrapper site = mock(org.jahia.services.content.JCRNodeWrapper.class);
            when(caller.nodeExists(sitePath)).thenReturn(true);
            when(caller.getNode(sitePath)).thenReturn(site);
            when(site.hasPermission(PERMISSION)).thenReturn(true);
        }

        static void wireDeniedCaller(JCRSessionWrapper caller, String sitePath) throws Exception {
            org.jahia.services.content.JCRNodeWrapper site = mock(org.jahia.services.content.JCRNodeWrapper.class);
            when(caller.nodeExists(sitePath)).thenReturn(true);
            when(caller.getNode(sitePath)).thenReturn(site);
            when(site.hasPermission(PERMISSION)).thenReturn(false);
        }
    }
}
