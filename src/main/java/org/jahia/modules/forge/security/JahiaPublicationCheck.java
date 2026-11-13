package org.jahia.modules.forge.security;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPublicationService;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.PublicationInfo;

import javax.jcr.RepositoryException;
import java.util.HashSet;
import java.util.Set;

/**
 * {@link PublicationCheck} backed by {@link JCRPublicationService#getStatus}, the status jContent shows.
 *
 * <p>For a node that has been published, that status is computed from the EDIT node alone
 * ({@code j:published}, {@code j:lastPublished}, {@code jcr:lastModified}); the live copy is not read.
 * This matters: a check that compared edit with live would let anyone able to write in live hide a
 * published advisory ({@code getPublicationInfo} turns PUBLISHED into NOT_PUBLISHED when the live node is
 * missing, so it is not used here).
 *
 * <p>The node and each of its translations in the site's languages must be {@link PublicationInfo#PUBLISHED}.
 * Translations are listed through a session WITHOUT a locale: a localized session hides
 * {@code j:translation_*} children ({@code ChildNodesIterator}), which would skip the check silently.
 */
public final class JahiaPublicationCheck implements PublicationCheck {

    private final JCRPublicationService publication;
    private final JCRSessionFactory sessions;

    public JahiaPublicationCheck() {
        this(JCRPublicationService.getInstance(), JCRSessionFactory.getInstance());
    }

    JahiaPublicationCheck(JCRPublicationService publication, JCRSessionFactory sessions) {
        this.publication = publication;
        this.sessions = sessions;
    }

    @Override
    public boolean isFullyPublished(JCRNodeWrapper editNode) throws RepositoryException {
        JCRSessionWrapper liveSession = sessions.getCurrentSystemSession(Constants.LIVE_WORKSPACE, null, null);
        Set<String> languages = new HashSet<>(editNode.getResolveSite().getLanguages());
        if (publication.getStatus(editNode, liveSession, languages) != PublicationInfo.PUBLISHED) {
            return false;
        }
        JCRSessionWrapper unlocalized = sessions.getCurrentSystemSession(Constants.EDIT_WORKSPACE, null, null);
        JCRNodeIteratorWrapper children = unlocalized.getNodeByIdentifier(editNode.getIdentifier()).getNodes();
        while (children.hasNext()) {
            JCRNodeWrapper child = (JCRNodeWrapper) children.nextNode();
            if (isTranslationIn(child, languages)
                    && publication.getStatus(child, liveSession, languages) != PublicationInfo.PUBLISHED) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTranslationIn(JCRNodeWrapper node, Set<String> languages) throws RepositoryException {
        return node.isNodeType(Constants.JAHIANT_TRANSLATION)
                && node.hasProperty(Constants.JCR_LANGUAGE)
                && languages.contains(node.getProperty(Constants.JCR_LANGUAGE).getString());
    }
}
