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
import org.jahia.services.content.DefaultEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.io.IOException;
import java.io.InputStream;

/**
 * Server-side re-validation of the content type of files written under a forge element
 * (module / package), guarding against a spoofed {@code jcr:mimeType} that could be served inline
 * as script (SECURITY-571 #28, hardened for #61).
 *
 * <p>A module author holds the {@code OWNER} role over their whole module subtree and writes via the
 * generic, non-CSRF-gated {@code /modules/graphql} mutation. The storefront editor uses that path
 * to add icons/screenshots, taking {@code jcr:mimeType} verbatim from the browser's {@code
 * File.type}; but the same primitive lets an author store an SVG/HTML file (with a spoofed image
 * mime) <em>anywhere</em> in the subtree, not only under {@code icon}/{@code screenshots}.</p>
 *
 * <p>It sniffs any {@code jnt:resource} whose containing {@code jnt:file} is in the module repository
 * and, running as system:</p>
 * <ul>
 *   <li><b>Everywhere in the module repository</b> — removes any SVG or HTML file, honestly declared
 *       or not: SVG and HTML are never accepted as module media (JSTORE-SEC-012). A file is SVG or
 *       HTML by its declared mime, its name ({@code .svg}, {@code .html}, …) or its content (an
 *       {@code <svg}, {@code <!doctype html}, {@code <html} or {@code <script} token). Any other
 *       script-capable file (an XML mime, or bytes that start with markup) is removed too. Legitimate
 *       binaries (the module JAR/tgz artifact, rasters, …) are untouched.</li>
 *   <li><b>In the {@code icon}/{@code screenshots} folders of a forge element</b> (scoped by
 *       <b>node structure, not path string</b>: the file must sit under a {@code jmix:forgeElement})
 *       — additionally requires a real raster: a non-raster upload is removed and a mismatched
 *       declared mime is pinned to the detected type.</li>
 * </ul>
 *
 * <p>Media is written in both workspaces: authors edit in the default workspace, and store developers
 * (who hold {@code jcr:write_live}) write through the storefront's live mutations. Jahia registers a
 * listener on one workspace only (the default one when none is set), so this class is registered once
 * per workspace, by {@link ForgeMediaMimeEditListener} and {@link ForgeMediaMimeLiveListener}
 * (JSTORE-SEC-012).</p>
 *
 * <p>Follows the module's established {@link DefaultEventListener} pattern (see
 * {@code AdvisoriesLockListener}). It is defense-in-depth, not the sole gate: the upload action checks
 * the artifacts it stores ({@link ArtifactUploadGuard}), and the client keeps its allow-list.</p>
 */
public abstract class ForgeMediaMimeListener extends DefaultEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForgeMediaMimeListener.class);
    private static final String MODULES_REPOSITORY = "/contents/modules-repository/";
    private static final String JCR_CONTENT = "/jcr:content";
    private static final String PROP_MIME_TYPE = "jcr:mimeType";
    private static final String PROP_DATA = "jcr:data";
    private static final String JNT_RESOURCE = "jnt:resource";
    private static final String JNT_FILE = "jnt:file";
    private static final String JMIX_FORGE_ELEMENT = "jmix:forgeElement";
    private static final String ICON_FOLDER = "icon";
    private static final String SCREENSHOTS_FOLDER = "screenshots";
    /** Both workspaces are checked: media can be authored in EDIT or directly in LIVE. */
    private static final String[] WORKSPACES = {Constants.EDIT_WORKSPACE, Constants.LIVE_WORKSPACE};

    /** @param workspace the workspace this listener observes ({@code default} or {@code live}) */
    protected ForgeMediaMimeListener(String workspace) {
        setWorkspace(workspace);
    }

    @Override
    public int getEventTypes() {
        return Event.NODE_ADDED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED;
    }

    @Override
    public String getPath() {
        return "/sites";
    }

    @Override
    public boolean isDeep() {
        return true;
    }

    @Override
    public String[] getNodeTypes() {
        // Restrict to the binary resource node: for property events this matches jcr:mimeType /
        // jcr:data whose parent is the jnt:resource, so noise from unrelated writes is filtered out.
        return new String[]{JNT_RESOURCE};
    }

    @Override
    public void onEvent(EventIterator events) {
        while (events.hasNext()) {
            final Event event = events.nextEvent();
            try {
                final String resourcePath = forgeRepositoryResourcePath(event.getPath());
                if (resourcePath != null) {
                    validateResource(resourcePath);
                }
            } catch (RepositoryException e) {
                LOGGER.error("ForgeMediaMimeListener: error handling event", e);
            }
        }
    }

    /**
     * Cheap path pre-filter: return the {@code .../jcr:content} node path when {@code eventPath} is
     * (or is a property of) a resource under the module repository, else null. The precise, airtight
     * scoping (is it under a forge element?) is done structurally in {@link #validateInSession}.
     */
    static String forgeRepositoryResourcePath(String eventPath) {
        if (eventPath == null || !eventPath.contains(MODULES_REPOSITORY)) {
            return null;
        }
        final int idx = eventPath.indexOf(JCR_CONTENT);
        if (idx < 0) {
            return null;
        }
        // Trim any trailing property segment (".../jcr:content/jcr:mimeType" -> ".../jcr:content").
        return eventPath.substring(0, idx + JCR_CONTENT.length());
    }

    /** Sniff the resource bytes in whichever workspace(s) hold it and reject/correct as needed. */
    private void validateResource(String resourcePath) {
        for (String workspace : WORKSPACES) {
            try {
                JCRTemplate.getInstance().doExecuteWithSystemSession(null, workspace, session -> {
                    validateInSession(session, resourcePath);
                    return null;
                });
            } catch (RepositoryException e) {
                // Node absent in this workspace (not yet published, or authored only in the other) —
                // expected; keep checking the remaining workspace.
                LOGGER.debug("ForgeMediaMimeListener: '{}' not processed in workspace '{}': {}",
                        ActionSecurityUtils.sanitizeForLog(resourcePath), workspace, e.getMessage());
            }
        }
    }

    private static void validateInSession(JCRSessionWrapper session, String resourcePath)
            throws RepositoryException {
        if (!session.nodeExists(resourcePath)) {
            return;
        }
        final JCRNodeWrapper resource = session.getNode(resourcePath);
        if (!resource.isNodeType(JNT_RESOURCE) || !resource.hasProperty(PROP_DATA)) {
            return;
        }
        final JCRNodeWrapper file = resource.getParent();
        if (!file.isNodeType(JNT_FILE) || !file.getPath().contains(MODULES_REPOSITORY)) {
            return;
        }
        final byte[] header = readHeader(resource);
        final String declared = resource.hasProperty(PROP_MIME_TYPE)
                ? resource.getProperty(PROP_MIME_TYPE).getString() : null;

        // Rule A (anywhere in the module repository): SVG and HTML are never accepted as module media
        // (JSTORE-SEC-012), whether declared, named or sniffed, and nothing a browser could render as
        // script is kept either. Legit binaries (module artifact, rasters) match none of these checks.
        if (isRefusedMarkup(file, declared, header)) {
            LOGGER.warn("ForgeMediaMimeListener: removed SVG/HTML or script-capable file '{}' (declared mime '{}')",
                    ActionSecurityUtils.sanitizeForLog(file.getPath()),
                    ActionSecurityUtils.sanitizeForLog(declared));
            file.remove();
            session.save();
            return;
        }

        // Structural scope of the raster rule: only files that live under a forge element (module/package)
        // are author media. This replaces the former "/icon//screenshots/" path matching so a file planted
        // elsewhere in the subtree is covered too (SECURITY-571 #61).
        if (!isUnderForgeElement(file)) {
            return;
        }

        // Rule B (media folders only): icons/screenshots must be a genuine raster.
        if (isMediaFile(file)) {
            final String detected = MagicByteImageValidator.detectRasterMime(header);
            if (detected == null) {
                LOGGER.warn("ForgeMediaMimeListener: removed non-raster media '{}' (declared mime '{}')",
                        ActionSecurityUtils.sanitizeForLog(file.getPath()),
                        ActionSecurityUtils.sanitizeForLog(declared));
                file.remove();
                session.save();
            } else if (!detected.equalsIgnoreCase(declared)) {
                resource.setProperty(PROP_MIME_TYPE, detected);
                session.save();
                LOGGER.warn("ForgeMediaMimeListener: corrected spoofed mime on '{}' ('{}' -> '{}')",
                        ActionSecurityUtils.sanitizeForLog(resourcePath),
                        ActionSecurityUtils.sanitizeForLog(declared), detected);
            }
        }
    }

    /**
     * True when the file is SVG or HTML by its declared mime, its name or its content, or is otherwise
     * script-capable (an XML mime, or bytes that start with markup). The content token sniff does not
     * apply to an archive (a module JAR or tgz), whose stored entries may hold HTML text.
     */
    private static boolean isRefusedMarkup(JCRNodeWrapper file, String declared, byte[] header) {
        return MagicByteImageValidator.isScriptCapableMime(declared)
                || MagicByteImageValidator.looksLikeMarkup(header)
                || MagicByteImageValidator.isSvgOrHtmlName(file.getName())
                || (!MagicByteImageValidator.isArchive(header)
                        && MagicByteImageValidator.containsSvgOrHtmlMarkup(header));
    }

    /**
     * True when {@code node} has a {@code jmix:forgeElement} ancestor within the module repository.
     *
     * <p>The walk has no depth limit: a JCR hierarchy is a tree, and the walk stops at the latest when it
     * leaves the module repository, so it always ends. A fixed limit let an author nest a file deeper
     * than the limit and escape the guard (JSTORE-SEC-012).</p>
     */
    private static boolean isUnderForgeElement(JCRNodeWrapper node) throws RepositoryException {
        JCRNodeWrapper current = node;
        while (current != null) {
            if (current.isNodeType(JMIX_FORGE_ELEMENT)) {
                return true;
            }
            if (!current.getPath().contains(MODULES_REPOSITORY)) {
                return false; // left the module repository without meeting a forge element
            }
            try {
                current = current.getParent();
            } catch (javax.jcr.ItemNotFoundException rootReached) {
                return false;
            }
        }
        return false;
    }

    /** True when the file's containing folder is the module's {@code icon} or {@code screenshots}. */
    private static boolean isMediaFile(JCRNodeWrapper file) throws RepositoryException {
        final String parentName = file.getParent().getName();
        return ICON_FOLDER.equals(parentName) || SCREENSHOTS_FOLDER.equals(parentName);
    }

    private static byte[] readHeader(JCRNodeWrapper resource) throws RepositoryException {
        try (InputStream in = resource.getProperty(PROP_DATA).getBinary().getStream()) {
            return in.readNBytes(MagicByteImageValidator.MARKUP_SNIFF_LENGTH);
        } catch (IOException e) {
            LOGGER.warn("ForgeMediaMimeListener: could not read '{}' data: {}",
                    ActionSecurityUtils.sanitizeForLog(resource.getPath()), e.getMessage());
            return new byte[0];
        }
    }
}
