package org.jahia.modules.forge.security;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvisoriesLockTest {

    private static final String SITE = "/sites/store";
    private static final String FOLDER = "/sites/store/contents/security-advisories";
    private static final String ROLE = "store-developer";

    private JCRTemplate jcrTemplate;
    private JCRSessionWrapper edit;
    private JCRSessionWrapper live;
    private JCRNodeWrapper editFolder;
    private JCRNodeWrapper liveFolder;
    private AdvisoriesLock lock;

    @BeforeEach
    void setUp() throws Exception {
        jcrTemplate = mock(JCRTemplate.class);
        edit = mock(JCRSessionWrapper.class);
        live = mock(JCRSessionWrapper.class);
        when(jcrTemplate.doExecuteWithSystemSessionAsUser(isNull(), eq("default"), isNull(), any()))
                .thenAnswer(inv -> inv.<JCRCallback<?>>getArgument(3).doInJCR(edit));
        when(jcrTemplate.doExecuteWithSystemSessionAsUser(isNull(), eq("live"), isNull(), any()))
                .thenAnswer(inv -> inv.<JCRCallback<?>>getArgument(3).doInJCR(live));
        editFolder = folder(edit);
        liveFolder = folder(live);
        lock = new AdvisoriesLock(jcrTemplate);
    }

    @Test
    @DisplayName("denies store-developer on the folder to every user and group granted it on the site, in both workspaces")
    void deniesGrantedPrincipalsInBothWorkspaces() throws Exception {
        Map<String, List<String[]>> acl = acl(
                row("u:dev", SITE, "GRANT", ROLE),
                row("g:devs", SITE, "GRANT", ROLE));
        when(editFolder.getAclEntries()).thenReturn(acl);
        when(liveFolder.getAclEntries()).thenReturn(acl);

        lock.sync("store");

        for (JCRNodeWrapper folder : Arrays.asList(editFolder, liveFolder)) {
            verify(folder).denyRoles("u:dev", Collections.singleton(ROLE));
            verify(folder).denyRoles("g:devs", Collections.singleton(ROLE));
        }
        verify(edit).save();
        verify(live).save();
    }

    @Test
    @DisplayName("a principal granted in one workspace only is denied in both (no gap before the site is republished)")
    void unionOfWorkspaces() throws Exception {
        when(editFolder.getAclEntries()).thenReturn(acl(row("u:new", SITE, "GRANT", ROLE)));
        when(liveFolder.getAclEntries()).thenReturn(acl());

        lock.sync("store");

        verify(editFolder).denyRoles("u:new", Collections.singleton(ROLE));
        verify(liveFolder).denyRoles("u:new", Collections.singleton(ROLE));
    }

    @Test
    @DisplayName("removes the denial of a principal no longer granted store-developer")
    void removesStaleDenial() throws Exception {
        Map<String, List<String[]>> acl = acl(row("u:former", FOLDER, "DENY", ROLE));
        when(editFolder.getAclEntries()).thenReturn(acl);
        when(liveFolder.getAclEntries()).thenReturn(acl);

        lock.sync("store");

        Map<String, String> remove = Collections.singletonMap(ROLE, "REMOVE");
        verify(editFolder).changeRoles("u:former", remove);
        verify(liveFolder).changeRoles("u:former", remove);
        verify(editFolder, never()).denyRoles(anyString(), anySet());
    }

    @Test
    @DisplayName("is idempotent: a folder already in sync is not written nor saved")
    void idempotent() throws Exception {
        Map<String, List<String[]>> acl = acl(
                row("u:dev", FOLDER, "DENY", ROLE),
                row("u:dev", SITE, "GRANT", ROLE));
        when(editFolder.getAclEntries()).thenReturn(acl);
        when(liveFolder.getAclEntries()).thenReturn(acl);

        lock.sync("store");

        verify(editFolder, never()).denyRoles(anyString(), anySet());
        verify(editFolder, never()).changeRoles(anyString(), anyMap());
        verify(edit, never()).save();
        verify(live, never()).save();
    }

    @Test
    @DisplayName("ignores other roles, inherited DENY rows, and grants made on the folder itself")
    void ignoresPrincipalsNotGrantedStoreDeveloper() throws Exception {
        Map<String, List<String[]>> acl = acl(
                row("u:admin", SITE, "GRANT", "store-administrator"),
                row("g:site-administrators", SITE, "GRANT", "site-administrator"),
                row("u:denied", SITE, "DENY", ROLE),
                row("u:exception", FOLDER, "GRANT", ROLE));
        when(editFolder.getAclEntries()).thenReturn(acl);
        when(liveFolder.getAclEntries()).thenReturn(acl);

        lock.sync("store");

        verify(editFolder, never()).denyRoles(anyString(), anySet());
        verify(editFolder, never()).changeRoles(anyString(), anyMap());
        verify(edit, never()).save();
    }

    @Test
    @DisplayName("a site whose folder is not published yet is locked in edit only; no folder at all is a no-op")
    void missingFolder() throws Exception {
        when(live.nodeExists(FOLDER)).thenReturn(false);
        when(editFolder.getAclEntries()).thenReturn(acl(row("u:dev", SITE, "GRANT", ROLE)));

        lock.sync("store");

        verify(editFolder).denyRoles("u:dev", Collections.singleton(ROLE));
        verify(live, never()).save();

        when(edit.nodeExists(FOLDER)).thenReturn(false);
        assertThatCode(() -> lock.sync("store")).doesNotThrowAnyException();
        verify(editFolder, times(1)).denyRoles(anyString(), anySet());
    }

    @Test
    @DisplayName("a sync re-entered from its own save (a JCR listener) is skipped, not recursive")
    void reentrantSyncSkipped() throws Exception {
        when(editFolder.getAclEntries()).thenReturn(acl(row("u:dev", SITE, "GRANT", ROLE)));
        when(liveFolder.getAclEntries()).thenReturn(acl(row("u:dev", SITE, "GRANT", ROLE)));
        doAnswer(inv -> {
            lock.sync("store");
            return null;
        }).when(edit).save();

        lock.sync("store");

        verify(editFolder, times(1)).denyRoles("u:dev", Collections.singleton(ROLE));
    }

    @Test
    @DisplayName("an unreadable ACL fails the sync instead of removing every denial")
    void unreadableAclFails() throws Exception {
        when(editFolder.getAclEntries()).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lock.sync("store"))
                .isInstanceOf(RepositoryException.class);
        verify(editFolder, never()).changeRoles(anyString(), anyMap());
    }

    @Test
    @DisplayName("activation syncs every site; a failing site does not stop the others nor the activation")
    void activationSyncsEverySite() throws Exception {
        JCRNodeWrapper sites = mock(JCRNodeWrapper.class);
        JCRNodeWrapper broken = site("broken");
        JCRNodeWrapper store = site("store");
        JCRNodeWrapper notASite = mock(JCRNodeWrapper.class);
        when(edit.getNode("/sites")).thenReturn(sites);
        when(sites.getNodes()).thenAnswer(inv -> iterator(Arrays.asList(broken, notASite, store)));
        when(edit.nodeExists("/sites/broken/contents/security-advisories")).thenThrow(new RepositoryException("boom"));
        when(editFolder.getAclEntries()).thenReturn(acl(row("u:dev", SITE, "GRANT", ROLE)));
        when(liveFolder.getAclEntries()).thenReturn(acl());

        assertThatCode(() -> lock.activate()).doesNotThrowAnyException();

        verify(editFolder).denyRoles("u:dev", Collections.singleton(ROLE));
    }

    // ---------------------------------------------------------------- helpers

    private static JCRNodeWrapper folder(JCRSessionWrapper session) throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        when(folder.getPath()).thenReturn(FOLDER);
        when(session.nodeExists(FOLDER)).thenReturn(true);
        when(session.getNode(FOLDER)).thenReturn(folder);
        when(folder.getAclEntries()).thenReturn(acl());
        return folder;
    }

    private static JCRNodeWrapper site(String key) throws Exception {
        JCRNodeWrapper site = mock(JCRNodeWrapper.class);
        when(site.isNodeType("jnt:virtualsite")).thenReturn(true);
        when(site.getName()).thenReturn(key);
        return site;
    }

    private static String[] row(String principal, String path, String type, String role) {
        return new String[]{principal, path, type, role};
    }

    /** Builds a getAclEntries() map: principal -> [path, aceType, role] rows. */
    private static Map<String, List<String[]>> acl(String[]... rows) {
        Map<String, List<String[]>> map = new LinkedHashMap<>();
        for (String[] r : rows) {
            map.computeIfAbsent(r[0], k -> new java.util.ArrayList<>()).add(new String[]{r[1], r[2], r[3]});
        }
        return map;
    }

    private static JCRNodeIteratorWrapper iterator(List<JCRNodeWrapper> list) {
        Iterator<JCRNodeWrapper> it = list.iterator();
        JCRNodeIteratorWrapper wrapper = mock(JCRNodeIteratorWrapper.class);
        when(wrapper.hasNext()).thenAnswer(inv -> it.hasNext());
        when(wrapper.nextNode()).thenAnswer(inv -> it.next());
        when(wrapper.next()).thenAnswer(inv -> it.next());
        return wrapper;
    }
}
