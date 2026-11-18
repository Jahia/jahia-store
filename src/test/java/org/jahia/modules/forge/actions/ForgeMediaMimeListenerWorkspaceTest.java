package org.jahia.modules.forge.actions;

import org.jahia.services.content.DefaultEventListener;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JSTORE-SEC-012 S1: the media MIME guard must observe the LIVE workspace too. Store developers hold
 * {@code jcr:write_live} and the storefront writes media through live mutations; a listener that
 * observes only the default (edit) workspace never sees those writes, so a planted SVG/HTML file stays
 * and is served inline. Jahia registers a {@link DefaultEventListener} whose workspace is null on the
 * default workspace only, so each workspace needs its own registered listener (as for the advisories
 * lock: {@code AdvisoriesLockEditListener} / {@code AdvisoriesLockLiveListener}).
 */
class ForgeMediaMimeListenerWorkspaceTest {

    private static final String RESOURCE =
            "/sites/store/contents/modules-repository/org/acme/mymodule/icon/evil.png/jcr:content";

    @Test
    @DisplayName("S1: a listener is bound to the live workspace")
    void liveListenerObservesLive() {
        assertThat(new ForgeMediaMimeLiveListener().getWorkspace()).isEqualTo("live");
    }

    @Test
    @DisplayName("S1: a listener is bound to the edit (default) workspace, explicitly")
    void editListenerObservesDefault() {
        assertThat(new ForgeMediaMimeEditListener().getWorkspace()).isEqualTo("default");
    }

    @ParameterizedTest
    @ValueSource(classes = {ForgeMediaMimeEditListener.class, ForgeMediaMimeLiveListener.class})
    @DisplayName("S1: both listeners are declarative-services components, so both register")
    void bothListenersAreComponents(Class<?> listenerClass) throws Exception {
        // @Component has CLASS retention: it is in the class file (and read by bnd), not at runtime.
        assertThat(classBytes(listenerClass)).contains("Lorg/osgi/service/component/annotations/Component;");
        assertThat(DefaultEventListener.class).isAssignableFrom(listenerClass);
    }

    @ParameterizedTest
    @ValueSource(classes = {ForgeMediaMimeEditListener.class, ForgeMediaMimeLiveListener.class})
    @DisplayName("S1: both listeners watch every resource write under /sites")
    void bothListenersWatchResourceWrites(Class<? extends ForgeMediaMimeListener> listenerClass) throws Exception {
        ForgeMediaMimeListener listener = listenerClass.getDeclaredConstructor().newInstance();

        assertThat(listener.getPath()).isEqualTo("/sites");
        assertThat(listener.isDeep()).isTrue();
        assertThat(listener.getNodeTypes()).containsExactly("jnt:resource");
        assertThat(listener.getEventTypes())
                .isEqualTo(Event.NODE_ADDED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED);
    }

    @Test
    @DisplayName("S1: a resource written in LIVE is validated in the live workspace")
    @SuppressWarnings("unchecked")
    void liveWriteIsValidatedInLive() throws Exception {
        JCRTemplate template = mock(JCRTemplate.class);
        try (MockedStatic<JCRTemplate> statics = mockStatic(JCRTemplate.class)) {
            statics.when(JCRTemplate::getInstance).thenReturn(template);

            new ForgeMediaMimeLiveListener().onEvent(events(event(RESOURCE + "/jcr:mimeType")));

            verify(template).doExecuteWithSystemSession(isNull(), eq("live"), any(JCRCallback.class));
        }
    }

    private static Event event(String path) throws Exception {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn(Event.PROPERTY_ADDED);
        when(event.getPath()).thenReturn(path);
        return event;
    }

    private static EventIterator events(Event... events) {
        Iterator<Event> it = Arrays.asList(events).iterator();
        EventIterator iterator = mock(EventIterator.class);
        when(iterator.hasNext()).thenAnswer(invocation -> it.hasNext());
        when(iterator.nextEvent()).thenAnswer(invocation -> it.next());
        return iterator;
    }

    private static String classBytes(Class<?> clazz) throws Exception {
        try (InputStream in = clazz.getResourceAsStream(clazz.getSimpleName() + ".class")) {
            // ISO-8859-1 keeps every byte 1:1 so constant-pool UTF8 symbols survive as substrings.
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
