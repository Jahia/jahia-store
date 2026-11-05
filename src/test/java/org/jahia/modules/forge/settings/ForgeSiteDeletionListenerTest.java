package org.jahia.modules.forge.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S19 / U13 — the site-deletion listener must delete a site's per-site forge {@code .cfg} ONLY when
 * the site node itself ({@code /sites/<key>}) is removed, never for a descendant node. Otherwise a
 * recreated same-key site could inherit stale credentials, or an unrelated descendant removal could
 * wipe live config.
 */
class ForgeSiteDeletionListenerTest {

    private static EventIterator singleEvent(String path) throws Exception {
        Event event = mock(Event.class);
        when(event.getPath()).thenReturn(path);
        EventIterator it = mock(EventIterator.class);
        when(it.hasNext()).thenReturn(true, false);
        when(it.nextEvent()).thenReturn(event);
        return it;
    }

    @Test
    @DisplayName("removing the site node itself deletes that site's forge config once")
    void siteNodeRemoved_deletesConfig() throws Exception {
        // Arrange
        ForgeSettingsService svc = mock(ForgeSettingsService.class);
        ForgeSiteDeletionListener listener = new ForgeSiteDeletionListener(svc);

        // Act
        listener.onEvent(singleEvent("/sites/mysite"));

        // Assert
        verify(svc, times(1)).delete("mysite");
    }

    @Test
    @DisplayName("removing a descendant of a site does NOT delete the site config")
    void descendantRemoved_doesNotDelete() throws Exception {
        // Arrange
        ForgeSettingsService svc = mock(ForgeSettingsService.class);
        ForgeSiteDeletionListener listener = new ForgeSiteDeletionListener(svc);

        // Act
        listener.onEvent(singleEvent("/sites/mysite/contents/modules-repository"));

        // Assert
        verify(svc, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("removals outside /sites are ignored")
    void nonSitePath_ignored() throws Exception {
        // Arrange
        ForgeSettingsService svc = mock(ForgeSettingsService.class);
        ForgeSiteDeletionListener listener = new ForgeSiteDeletionListener(svc);

        // Act
        listener.onEvent(singleEvent("/users/foo"));

        // Assert
        verify(svc, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }
}
