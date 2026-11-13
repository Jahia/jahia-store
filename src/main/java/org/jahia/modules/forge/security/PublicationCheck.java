package org.jahia.modules.forge.security;

import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.RepositoryException;

/**
 * Tells whether a node read from the EDIT workspace is fully published: published at least once and
 * with no change, in any language, made since its last publication.
 */
public interface PublicationCheck {

    /**
     * @param editNode a node read from the edit ({@code default}) workspace
     * @return true only when the live copy is identical to {@code editNode}
     * @throws RepositoryException when the publication status cannot be read
     */
    boolean isFullyPublished(JCRNodeWrapper editNode) throws RepositoryException;
}
