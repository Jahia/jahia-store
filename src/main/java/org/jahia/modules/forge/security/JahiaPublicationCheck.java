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
 * This matters: store developers can change or delete live nodes, and a check that compared edit with live
 * would let them hide a published advisory ({@code getPublicationInfo} turns PUBLISHED into NOT_PUBLISHED
 * when the live node is missing, so it is not used here). The node and each of its translations in the
 * site's languages must be {@link PublicationInfo#PUBLISHED}.
 */
public final class JahiaPublicationCheck implements PublicationCheck {

    @Override
    public boolean isFullyPublished(JCRNodeWrapper editNode) throws RepositoryException {
        JCRSessionWrapper liveSession = JCRSessionFactory.getInstance()
                .getCurrentSystemSession(Constants.LIVE_WORKSPACE, editNode.getSession().getLocale(), null);
        Set<String> languages = new HashSet<>(editNode.getResolveSite().getLanguages());
        JCRPublicationService publication = JCRPublicationService.getInstance();
        if (publication.getStatus(editNode, liveSession, languages) != PublicationInfo.PUBLISHED) {
            return false;
        }
        JCRNodeIteratorWrapper children = editNode.getNodes();
        while (children.hasNext()) {
            JCRNodeWrapper child = (JCRNodeWrapper) children.nextNode();
            if (child.isNodeType(Constants.JAHIANT_TRANSLATION)
                    && languages.contains(child.getProperty(Constants.JCR_LANGUAGE).getString())
                    && publication.getStatus(child, liveSession, languages) != PublicationInfo.PUBLISHED) {
                return false;
            }
        }
        return true;
    }
}
