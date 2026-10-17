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
 *     For questions regarding licensing, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link GitModule} from a repository, as docs/git-backed-modules.md states: the root
 * {@code STORE.md} on the branch, one version per tag that matches the pattern, with the
 * {@code STORE.md} and the {@code CHANGELOG.md} as they stand at that tag.
 */
public final class GitModuleLoader {

    static final String STORE_MD = "STORE.md";
    static final String STORE_INDEX_MD = "store/index.md";
    static final String CHANGELOG_MD = "CHANGELOG.md";

    private static final Logger LOGGER = LoggerFactory.getLogger(GitModuleLoader.class);

    private GitModuleLoader() {
    }

    /**
     * @param reader     the repository
     * @param branch     the branch that holds the root manifest, or null for the default branch
     * @param tagPattern the pattern a tag must match to be a version
     * @throws IOException            when the remote cannot be read
     * @throws StoreManifestException when the repository fails the contract
     */
    public static GitModule load(GitRepositoryReader reader, String branch, Pattern tagPattern) throws IOException {
        String branchName = StringUtils.isBlank(branch) ? reader.defaultBranch() : branch.trim();
        String branchRef = reader.fetchBranch(branchName);
        StoreManifest root = readManifest(reader, branchRef, "the branch " + branchName);

        List<GitModule.Version> versions = new ArrayList<>();
        for (String tag : reader.listRemoteTags()) {
            if (!VersionTags.isVersion(tagPattern, tag)) {
                continue;
            }
            versions.add(readVersion(reader, root, tag));
        }
        versions.sort((a, b) -> VersionTags.newestFirst().compare(a.getNumber(), b.getNumber()));

        String iconName = null;
        String iconMime = null;
        byte[] iconBytes = null;
        if (root.getIcon() != null) {
            iconBytes = reader.readFile(branchRef, root.getIcon());
            if (iconBytes == null) {
                LOGGER.warn("The repository {} declares the icon {} and does not hold it", reader.getUrl(), root.getIcon());
            } else {
                iconName = StringUtils.substringAfterLast("/" + root.getIcon(), "/");
                iconMime = imageMimeType(iconName);
            }
        }
        return new GitModule(reader.getUrl(), branchName, root, versions, iconName, iconMime, iconBytes);
    }

    /** The MIME type of an icon file by its extension; the formats a browser shows inline. */
    static String imageMimeType(String fileName) {
        String ext = StringUtils.substringAfterLast(fileName, ".").toLowerCase();
        switch (ext) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "svg":
                return "image/svg+xml";
            case "webp":
                return "image/webp";
            case "ico":
                return "image/x-icon";
            default:
                return "application/octet-stream";
        }
    }

    private static GitModule.Version readVersion(GitRepositoryReader reader, StoreManifest root, String tag) throws IOException {
        String ref = reader.fetchTag(tag);
        String number = VersionTags.versionOf(tag);
        StoreManifest atTag;
        try {
            atTag = readManifest(reader, ref, "the tag " + tag);
        } catch (StoreManifestException e) {
            // A tag older than the contract still lists: the root manifest names its artefact.
            LOGGER.warn("The tag {} of {} has no usable STORE.md ({}); using the root manifest", tag, reader.getUrl(), e.getMessage());
            atTag = root;
        }
        byte[] changelog = reader.readFile(ref, CHANGELOG_MD);
        String section = changelog == null ? null : ChangelogParser.section(new String(changelog, StandardCharsets.UTF_8), number);
        return new GitModule.Version(tag, number, atTag.artifactUrl(number),
                section == null ? null : Markdown.toHtml(section),
                atTag.getRequiredVersion(), atTag.getDependencies(), reader.commitDate(ref));
    }

    private static StoreManifest readManifest(GitRepositoryReader reader, String ref, String where) throws IOException {
        byte[] content = reader.readFile(ref, STORE_INDEX_MD);
        if (content == null) {
            content = reader.readFile(ref, STORE_MD);
        }
        if (content == null) {
            throw new StoreManifestException("The repository holds no " + STORE_MD + " on " + where);
        }
        return StoreManifestParser.parse(new String(content, StandardCharsets.UTF_8));
    }
}
