package org.jahia.modules.forge.security;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRPublicationService;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.PublicationInfo;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JahiaPublicationCheckTest {

    private static final String UUID = "advisory-uuid";

    private JCRPublicationService publication;
    private JCRSessionWrapper liveSession;
    private JCRNodeWrapper editNode;
    private JCRNodeWrapper unlocalizedNode;
    private JahiaPublicationCheck check;

    @BeforeEach
    void setUp() throws Exception {
        publication = mock(JCRPublicationService.class);
        JCRSessionFactory sessions = mock(JCRSessionFactory.class);
        liveSession = mock(JCRSessionWrapper.class);
        JCRSessionWrapper unlocalizedSession = mock(JCRSessionWrapper.class);
        when(sessions.getCurrentSystemSession(Constants.LIVE_WORKSPACE, null, null)).thenReturn(liveSession);
        when(sessions.getCurrentSystemSession(Constants.EDIT_WORKSPACE, null, null)).thenReturn(unlocalizedSession);

        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getLanguages()).thenReturn(new TreeSet<>(Arrays.asList("en", "fr")));
        editNode = mock(JCRNodeWrapper.class);
        when(editNode.getIdentifier()).thenReturn(UUID);
        when(editNode.getResolveSite()).thenReturn(site);
        // The localized node hides its translations, as ChildNodesIterator does: never use it for them.
        when(editNode.getNodes()).thenAnswer(inv -> iterator(Collections.emptyList()));
        unlocalizedNode = mock(JCRNodeWrapper.class);
        when(unlocalizedSession.getNodeByIdentifier(UUID)).thenReturn(unlocalizedNode);

        check = new JahiaPublicationCheck(publication, sessions);
    }

    @Test
    @DisplayName("node and every site-language translation PUBLISHED -> fully published")
    void allPublished() throws Exception {
        nodeStatus(PublicationInfo.PUBLISHED);
        JCRNodeWrapper en = translation("en", PublicationInfo.PUBLISHED);
        JCRNodeWrapper fr = translation("fr", PublicationInfo.PUBLISHED);
        children(en, fr);

        assertThat(check.isFullyPublished(editNode)).isTrue();
    }

    @Test
    @DisplayName("a translation changed since publication -> not fully published")
    void modifiedTranslation() throws Exception {
        nodeStatus(PublicationInfo.PUBLISHED);
        children(translation("en", PublicationInfo.PUBLISHED), translation("fr", PublicationInfo.MODIFIED));

        assertThat(check.isFullyPublished(editNode)).isFalse();
    }

    @Test
    @DisplayName("a translation in a language the site does not use is ignored")
    void otherLanguageIgnored() throws Exception {
        nodeStatus(PublicationInfo.PUBLISHED);
        children(translation("en", PublicationInfo.PUBLISHED), translation("de", PublicationInfo.NOT_PUBLISHED));

        assertThat(check.isFullyPublished(editNode)).isTrue();
    }

    @Test
    @DisplayName("node not PUBLISHED (never published, modified, unpublished) -> false, translations not read")
    void nodeNotPublished() throws Exception {
        for (int status : new int[]{PublicationInfo.NOT_PUBLISHED, PublicationInfo.MODIFIED,
                PublicationInfo.UNPUBLISHED, PublicationInfo.CONFLICT, PublicationInfo.MARKED_FOR_DELETION}) {
            nodeStatus(status);
            assertThat(check.isFullyPublished(editNode)).as("status %d", status).isFalse();
        }
        verify(unlocalizedNode, never()).getNodes();
    }

    @Test
    @DisplayName("a non-translation child or a translation without jcr:language does not break the check")
    void otherChildrenIgnored() throws Exception {
        nodeStatus(PublicationInfo.PUBLISHED);
        JCRNodeWrapper file = mock(JCRNodeWrapper.class);
        when(file.isNodeType(Constants.JAHIANT_TRANSLATION)).thenReturn(false);
        JCRNodeWrapper noLanguage = mock(JCRNodeWrapper.class);
        when(noLanguage.isNodeType(Constants.JAHIANT_TRANSLATION)).thenReturn(true);
        when(noLanguage.hasProperty(Constants.JCR_LANGUAGE)).thenReturn(false);
        children(file, noLanguage, translation("en", PublicationInfo.PUBLISHED));

        assertThat(check.isFullyPublished(editNode)).isTrue();
    }

    private void nodeStatus(int status) throws Exception {
        when(publication.getStatus(eq(editNode), eq(liveSession), any())).thenReturn(status);
    }

    private JCRNodeWrapper translation(String language, int status) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.isNodeType(Constants.JAHIANT_TRANSLATION)).thenReturn(true);
        when(node.hasProperty(Constants.JCR_LANGUAGE)).thenReturn(true);
        JCRPropertyWrapper prop = mock(JCRPropertyWrapper.class);
        when(prop.getString()).thenReturn(language);
        when(node.getProperty(Constants.JCR_LANGUAGE)).thenReturn(prop);
        when(publication.getStatus(eq(node), eq(liveSession), any(Set.class))).thenReturn(status);
        return node;
    }

    private void children(JCRNodeWrapper... kids) throws Exception {
        List<JCRNodeWrapper> list = Arrays.asList(kids);
        when(unlocalizedNode.getNodes()).thenAnswer(inv -> iterator(list));
    }

    private static JCRNodeIteratorWrapper iterator(List<JCRNodeWrapper> list) {
        Iterator<JCRNodeWrapper> it = list.iterator();
        JCRNodeIteratorWrapper wrapper = mock(JCRNodeIteratorWrapper.class);
        when(wrapper.hasNext()).thenAnswer(inv -> it.hasNext());
        when(wrapper.nextNode()).thenAnswer(inv -> it.next());
        return wrapper;
    }
}
