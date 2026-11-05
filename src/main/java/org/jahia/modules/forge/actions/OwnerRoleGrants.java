/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2026 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms & Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.actions;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grants the {@code owner} role to the uploading user on the nodes an upload just created —
 * using a <em>system</em> session, so the {@code store-developer} role does not need
 * {@code jcr:modifyAccessControl_live} to make an upload work (SEC-366 / GHSA-f882-3xwv-3439).
 *
 * <p><strong>Why elevation and not a grant.</strong> Before SEC-366 the role shipped
 * {@code jcr:modifyAccessControl_live} at <em>site</em> scope so that
 * {@code node.grantRoles(...)} would succeed in the caller's own live session (commit
 * {@code 25db344}, "APPS-14 fixed issue when new node is created with just upload rights").
 * Site-scoped live ACL authority is far more than the upload needs: it let a store developer
 * rewrite the site's live ACL, self-promote to {@code store-administrator} and strip the
 * site administrators — invisibly, because a live write never enters a publication queue.
 * The upload needs ACL authority on the handful of nodes it just created, so that is all it
 * gets, and it gets it from the platform rather than from the caller's role.
 *
 * <p><strong>Why the grants are deferred.</strong> Both upload paths grant the owner role on
 * nodes that are still transient in the caller's session. A system session cannot see those,
 * so callers {@link #recordOwner(JCRNodeWrapper)} each node as it is created and
 * {@link #flush()} once the caller's session has been saved.
 *
 * <p><strong>Invariants enforced on every elevated write</strong> — an elevated write is only
 * as safe as the constraints around it, so all four are checked here rather than assumed:
 * <ol>
 *   <li>the principal is the caller's own username, never a parameter;</li>
 *   <li>the role is the constant {@code owner}, never a caller-supplied name;</li>
 *   <li>the target is re-resolved <em>by identifier</em> in the system session — never by a
 *       path built from user input;</li>
 *   <li>the target must live under the repository node the action was invoked on, which the
 *       Action framework already gated with {@code jahiaForgeUploadModule}
 *       ({@code CreateEntryFromJar.activate()}). A node outside that subtree is refused, so
 *       this helper can never be repurposed to grant owner anywhere else in the site.</li>
 * </ol>
 *
 * <p>Not thread-safe, and not meant to be: one instance belongs to one upload request.
 */
final class OwnerRoleGrants {

    private static final Logger logger = LoggerFactory.getLogger(OwnerRoleGrants.class);

    /** The Jahia core role granted to an uploader on their own entry. */
    private static final String OWNER = "owner";

    private final JCRSessionWrapper callerSession;
    private final String scopeRootPath;
    private final String username;
    /** Identifiers of nodes awaiting the elevated grant, in creation order, de-duplicated. */
    private final Set<String> pendingIdentifiers = new LinkedHashSet<>();

    /**
     * @param callerSession the request session the upload writes through; its workspace decides
     *                      which workspace the elevated grant is applied in
     * @param scopeRoot     the repository node the action was invoked on — the only subtree in
     *                      which this instance may ever grant the owner role
     */
    OwnerRoleGrants(JCRSessionWrapper callerSession, JCRNodeWrapper scopeRoot) {
        this(callerSession, scopeRoot.getPath(), callerSession.getUser().getUsername());
    }

    // package-private for unit testing: lets a test build an instance without a live session
    OwnerRoleGrants(JCRSessionWrapper callerSession, String scopeRootPath, String username) {
        this.callerSession = callerSession;
        this.scopeRootPath = scopeRootPath;
        this.username = username;
    }

    /** The caller's session, so callers that only needed it to reach this collector can share one argument. */
    JCRSessionWrapper getCallerSession() {
        return callerSession;
    }

    /**
     * Remember that {@code node} should be owned by the uploader. Records the identifier rather
     * than the node, because the node may still be transient and will be re-resolved in the
     * system session at {@link #flush()} time.
     */
    void recordOwner(JCRNodeWrapper node) throws RepositoryException {
        if (!shouldRecord(username, node.isNew())) {
            return;
        }
        pendingIdentifiers.add(node.getIdentifier());
    }

    /** The identifiers {@link #flush()} would grant on, in recording order. */
    // package-private for unit testing
    Set<String> getPendingIdentifiers() {
        return Collections.unmodifiableSet(pendingIdentifiers);
    }

    /**
     * True when this upload should own the node it just handled. Two facts decide it, and the
     * second is what bounds the elevated write.
     *
     * <p>A guest owns nothing, so a guest upload records no node at all.
     *
     * <p>An upload owns only what it CREATED. {@code CreateEntryFromJar} reaches its module node
     * through {@code upsertModuleNode}, and its package node through {@code upsertPackageNode},
     * and both return the STORED node when one of that name already exists. {@code Action.createNode}
     * reuses an existing node of the requested name in the same way. So the node handed here is not
     * always a new one, and a reused node belongs to whoever uploaded it first. Recording it would
     * put a second uploader's owner entry on that node, written by a system session that answers to
     * no ACL. The subtree check in {@link #isWithin} does not cover this, because another
     * developer's module sits under the same upload repository.
     *
     * <p>{@code isNew} is the discriminator, and it is read on the caller's own session before that
     * session is saved, which is where it still answers correctly.
     *
     * @param username  the caller's username
     * @param nodeIsNew whether the caller's session created the node in this request
     */
    // package-private for unit testing
    static boolean shouldRecord(String username, boolean nodeIsNew) {
        return !Constants.GUEST_USERNAME.equals(username) && nodeIsNew;
    }

    /**
     * Apply every recorded grant in a system session bound to the caller's workspace, then clear
     * the queue so a second call is a no-op. Must be called <em>after</em> the caller's session
     * has been saved — the nodes have to exist for another session to see them.
     *
     * <p>A node that cannot be granted is logged and skipped rather than aborting the upload:
     * the artifact is already deployed and the content already saved by this point, so failing
     * here would leave a half-committed upload. The consequence of a skip is a module the
     * uploader cannot subsequently edit, which an administrator can repair.
     */
    void flush() throws RepositoryException {
        if (pendingIdentifiers.isEmpty()) {
            return;
        }

        final List<String> identifiers = new ArrayList<>(pendingIdentifiers);
        pendingIdentifiers.clear();
        final String workspace = callerSession.getWorkspace().getName();

        JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, workspace, null, systemSession -> {
            boolean dirty = false;
            for (String identifier : identifiers) {
                dirty |= grantOwner(systemSession, identifier);
            }
            if (dirty) {
                systemSession.save();
            }
            return null;
        });
    }

    /**
     * Grant the owner role on one node, or return false when the node is gone or falls outside
     * the permitted subtree. Package-private return value is the "session needs saving" flag.
     */
    private boolean grantOwner(JCRSessionWrapper systemSession, String identifier) throws RepositoryException {
        final JCRNodeWrapper node;
        try {
            node = systemSession.getNodeByIdentifier(identifier);
        } catch (ItemNotFoundException e) {
            // The node was removed between the caller's save and this grant.
            logger.warn("Owner grant skipped: node {} no longer exists in workspace {}",
                    identifier, systemSession.getWorkspace().getName());
            return false;
        }

        if (!isWithinScope(node.getPath())) {
            // Refused, not repaired: a target outside the gated subtree means the caller passed
            // something the jahiaForgeUploadModule check never covered.
            if (logger.isErrorEnabled()) {
                logger.error("Owner grant refused: {} is outside the upload repository {}",
                        ActionSecurityUtils.sanitizeForLog(node.getPath()),
                        ActionSecurityUtils.sanitizeForLog(scopeRootPath));
            }
            return false;
        }

        node.grantRoles("u:" + username, Collections.singleton(OWNER));
        return true;
    }

    /**
     * True when {@code path} is the scope root or a descendant of it. Compared with an explicit
     * separator so that a sibling whose name merely starts with the root's name (for example
     * {@code /…/modules-repository-archive} against {@code /…/modules-repository}) is not
     * mistaken for a descendant.
     */
    // package-private for unit testing
    static boolean isWithin(String path, String rootPath) {
        if (path == null || rootPath == null) {
            return false;
        }
        return path.equals(rootPath) || path.startsWith(rootPath + "/");
    }

    private boolean isWithinScope(String path) {
        return isWithin(path, scopeRootPath);
    }
}
