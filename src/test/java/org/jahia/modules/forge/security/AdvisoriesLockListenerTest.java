package org.jahia.modules.forge.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvisoriesLockListenerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/sites/store/j:acl",
            "/sites/store/j:acl/GRANT_u_dev",
            "/sites/store/j:acl/GRANT_u_dev/j:roles",
            "/sites/store/contents/j:acl/DENY_g_devs",
            "/sites/store/contents/security-advisories/j:acl",
            "/sites/store/contents/security-advisories/j:acl/DENY_u_dev/j:roles"
    })
    @DisplayName("an ACL change on the site, its contents folder or the advisories folder syncs the site")
    void aclChangesSyncTheSite(String path) {
        assertThat(AdvisoriesLockListener.siteKeyToSync(Event.PROPERTY_CHANGED, path)).isEqualTo("store");
        assertThat(AdvisoriesLockListener.siteKeyToSync(Event.NODE_ADDED, path)).isEqualTo("store");
        assertThat(AdvisoriesLockListener.siteKeyToSync(Event.NODE_REMOVED, path)).isEqualTo("store");
    }

    @Test
    @DisplayName("creating the advisories folder syncs the site")
    void folderCreationSyncs() {
        assertThat(AdvisoriesLockListener.siteKeyToSync(Event.NODE_ADDED, "/sites/store/contents/security-advisories"))
                .isEqualTo("store");
        assertThat(AdvisoriesLockListener.siteKeyToSync(Event.NODE_REMOVED, "/sites/store/contents/security-advisories"))
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/sites/store",
            "/sites/store/jcr:title",
            "/sites/store/home/j:acl/GRANT_u_dev",
            "/sites/store/contents/modules-repository/m/j:acl/GRANT_u_owner",
            "/sites/store/contents/security-advisories/jsa-2026-0001",
            "/sites/store/contents/security-advisories/jsa-2026-0001/j:acl/GRANT_u_x",
            "/sites/store/contents/security-advisoriesX/j:acl",
            "/sites/store/j:aclX",
            "/sites",
            "/users/root/j:acl",
            ""
    })
    @DisplayName("other changes are ignored")
    void otherChangesIgnored(String path) {
        assertThat(AdvisoriesLockListener.siteKeyToSync(Event.NODE_ADDED, path)).isNull();
        assertThat(AdvisoriesLockListener.siteKeyToSync(Event.PROPERTY_CHANGED, path)).isNull();
    }

    @Test
    @DisplayName("a null path is ignored")
    void nullPathIgnored() {
        assertThat(AdvisoriesLockListener.siteKeyToSync(Event.NODE_ADDED, null)).isNull();
    }

    @Test
    @DisplayName("a batch of events syncs each affected site once")
    void syncsEachSiteOnce() throws Exception {
        AdvisoriesLock lock = mock(AdvisoriesLock.class);
        AdvisoriesLockListener listener = new AdvisoriesLockEditListener(lock);

        listener.onEvent(events(
                event(Event.NODE_ADDED, "/sites/a/j:acl/GRANT_u_dev"),
                event(Event.PROPERTY_ADDED, "/sites/a/j:acl/GRANT_u_dev/j:roles"),
                event(Event.PROPERTY_CHANGED, "/sites/a/jcr:title"),
                event(Event.NODE_ADDED, "/sites/b/contents/security-advisories")));

        verify(lock, times(1)).sync("a");
        verify(lock, times(1)).sync("b");
    }

    @Test
    @DisplayName("never throws: a failing sync or an unreadable event is logged, the other sites still sync")
    void neverThrows() throws Exception {
        AdvisoriesLock lock = mock(AdvisoriesLock.class);
        doThrow(new RepositoryException("boom")).when(lock).sync("a");
        AdvisoriesLockListener listener = new AdvisoriesLockLiveListener(lock);
        Event unreadable = mock(Event.class);
        when(unreadable.getPath()).thenThrow(new RepositoryException("gone"));

        assertThatCode(() -> listener.onEvent(events(
                unreadable,
                event(Event.NODE_ADDED, "/sites/a/j:acl"),
                event(Event.NODE_ADDED, "/sites/b/j:acl")))).doesNotThrowAnyException();

        verify(lock).sync("b");
    }

    @Test
    @DisplayName("no relevant event, no sync")
    void noRelevantEvent() throws Exception {
        AdvisoriesLock lock = mock(AdvisoriesLock.class);

        new AdvisoriesLockEditListener(lock).onEvent(events(event(Event.PROPERTY_CHANGED, "/sites/a/home/jcr:title")));

        verify(lock, never()).sync(anyString());
    }

    @Test
    @DisplayName("one listener per workspace, deep on /sites, for node and property changes")
    void registration() {
        AdvisoriesLock lock = mock(AdvisoriesLock.class);
        AdvisoriesLockListener edit = new AdvisoriesLockEditListener(lock);
        AdvisoriesLockListener live = new AdvisoriesLockLiveListener(lock);

        assertThat(edit.getWorkspace()).isEqualTo("default");
        assertThat(live.getWorkspace()).isEqualTo("live");
        assertThat(edit.getPath()).isEqualTo("/sites");
        assertThat(edit.isDeep()).isTrue();
        int expected = Event.NODE_ADDED | Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED
                | Event.PROPERTY_REMOVED;
        assertThat(edit.getEventTypes()).isEqualTo(expected);
        assertThat(edit.getListenerClass()).isNotEqualTo(live.getListenerClass());
    }

    private static Event event(int type, String path) throws Exception {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn(type);
        when(event.getPath()).thenReturn(path);
        return event;
    }

    private static EventIterator events(Event... events) {
        List<Event> list = Arrays.asList(events);
        Iterator<Event> it = list.iterator();
        EventIterator iterator = mock(EventIterator.class);
        when(iterator.hasNext()).thenAnswer(inv -> it.hasNext());
        when(iterator.nextEvent()).thenAnswer(inv -> it.next());
        return iterator;
    }
}
