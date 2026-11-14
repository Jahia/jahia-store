package org.jahia.modules.forge.security;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second line of defence of the security feed: tells whether a LIVE advisory, or one of its translations,
 * was written directly in the live workspace instead of being published from edit. The first line is
 * {@link AdvisoriesLock}, which denies store developers any write in the advisories folder.
 *
 * <p>A live node is rejected when any of these holds, for the advisory node and for each of its
 * {@code j:translation_*} children (read through {@link JCRNodeWrapper#getI18Ns()}, which reads the raw
 * child nodes: a localized session's {@code getNodes()} hides translation nodes, see Jahia's
 * {@code ChildNodesIterator}):
 * <ul>
 *   <li><b>{@code jmix:liveProperties}</b>: Jahia's {@code UGCListener} (registered on the live workspace
 *       only, not run during publication) adds this mixin and lists the changed properties in
 *       {@code j:liveProperties} when a property of a node whose {@code j:originWS} is {@code default} is
 *       changed in live ({@code j:published} is excluded by default, {@code jahia.ugc.excludePropertiesFromUGCCheck}).
 *       Publication does NOT clear it: {@code ConflictResolver} keeps the mixin and, worse, keeps the live
 *       value of every property listed in {@code j:liveProperties} instead of the edit value. A tampered
 *       advisory therefore stays ignored after a republication; it has to be deleted (and the deletion
 *       published) and created again.</li>
 *   <li><b>{@code j:originWS=live}</b>: Jahia's {@code LastModifiedListener} sets {@code j:originWS} to the
 *       workspace a {@code jmix:originWS} node is created in (every {@code jnt:content}, so every advisory).
 *       A publication clones the edit node with its {@code j:originWS=default} and never sets
 *       {@code live}; a copy does not carry it over ({@code Constants.forbiddenPropertiesToCopy}).</li>
 *   <li><b>no node with the same identifier in EDIT</b>: kept on purpose although the markers above exist,
 *       because they miss cases this check covers. {@code jnt:translation} is not {@code jmix:originWS}, so
 *       Jahia sets NEITHER marker on a translation (UGCListener only marks nodes whose {@code j:originWS} is
 *       {@code default}): a translation created in live is only caught here. A node created in live while
 *       observation listeners are off (publication-only listener phase, listeners disabled, an import into
 *       live whose XML carries {@code j:originWS}) carries no marker either.</li>
 * </ul>
 * Known gap, reported and not closable from here: a property of an existing translation (the title, the
 * summary) changed in live leaves no marker and keeps its identifier, so it is not detected; the lock is
 * what prevents it.
 *
 * <p>Each rejected node is logged once at WARN (per node and reason, for the life of the bundle), not on
 * every request.
 */
final class LiveChangeDetector {

    static final String LIVE_PROPERTIES_MIXIN = Constants.JAHIAMIX_LIVE_PROPERTIES;
    static final String ORIGIN_WORKSPACE = Constants.ORIGIN_WORKSPACE;

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveChangeDetector.class);
    /** Bounds the memory of already-logged nodes: past it, rejections are still applied but not logged. */
    private static final int MAX_WARNED = 1000;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private LiveChangeDetector() {
    }

    /**
     * @param advisory    the advisory, read in a live session
     * @param editSession a system session on the edit workspace
     * @return true when the advisory or one of its translations was written in live; the reason is logged
     * @throws RepositoryException when the repository cannot be read
     */
    static boolean isWrittenInLive(JCRNodeWrapper advisory, JCRSessionWrapper editSession) throws RepositoryException {
        if (rejects(advisory, editSession)) {
            return true;
        }
        NodeIterator translations = advisory.getI18Ns();
        while (translations.hasNext()) {
            if (rejects(translations.nextNode(), editSession)) {
                return true;
            }
        }
        return false;
    }

    private static boolean rejects(Node node, JCRSessionWrapper editSession) throws RepositoryException {
        String reason = reason(node, editSession);
        if (reason == null) {
            return false;
        }
        String path = node.getPath();
        if (WARNED.size() < MAX_WARNED && WARNED.add(path + '|' + reason)) {
            LOGGER.warn("Security feed ignores {}: {}", path, reason);
        }
        return true;
    }

    private static String reason(Node node, JCRSessionWrapper editSession) throws RepositoryException {
        if (node.isNodeType(LIVE_PROPERTIES_MIXIN)) {
            return "changed in the live workspace (" + LIVE_PROPERTIES_MIXIN + ")";
        }
        if (node.hasProperty(ORIGIN_WORKSPACE)
                && Constants.LIVE_WORKSPACE.equals(node.getProperty(ORIGIN_WORKSPACE).getString())) {
            return "created in the live workspace (" + ORIGIN_WORKSPACE + "=live)";
        }
        if (!existsInEdit(node.getIdentifier(), editSession)) {
            return "no node with the same identifier in the edit workspace";
        }
        return null;
    }

    private static boolean existsInEdit(String identifier, JCRSessionWrapper editSession) throws RepositoryException {
        try {
            editSession.getNodeByIdentifier(identifier);
            return true;
        } catch (ItemNotFoundException e) {
            return false;
        }
    }
}
