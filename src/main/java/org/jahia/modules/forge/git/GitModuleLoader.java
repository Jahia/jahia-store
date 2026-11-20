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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link GitModule} from the store branch of a repository, as docs/git-backed-modules.md
 * states: the root {@code STORE.md}, the files its description and its tabs show, then the
 * {@code VERSION.md} and the files of the folder of each version it lists.
 */
public final class GitModuleLoader {

    static final String STORE_MD = StoreManifestParser.FILE_NAME;
    static final String VERSION_MD = VersionManifest.FILE_NAME;
    static final String CHANGELOG_MD = "CHANGELOG.md";
    /** The folder of the module under which the files of the description are served. */
    static final String FILES_FOLDER = "files";

    private static final Logger LOGGER = LoggerFactory.getLogger(GitModuleLoader.class);

    private GitModuleLoader() {
    }

    /**
     * @param reader       the repository
     * @param branch       the store branch, or null for the default branch of the repository
     * @param filesBaseUrl the URL under which the files of the module are served, without a
     *                     trailing slash, or null to leave the relative links of the Markdown as they are
     * @throws IOException            when the remote cannot be read
     * @throws StoreManifestException when the repository fails the contract
     */
    public static GitModule load(GitRepositoryReader reader, String branch, String filesBaseUrl) throws IOException {
        String branchName = StringUtils.isBlank(branch) ? reader.defaultBranch() : branch.trim();
        String ref = reader.fetchBranch(branchName);
        byte[] content = reader.readFile(ref, STORE_MD);
        if (content == null) {
            throw new StoreManifestException("The repository holds no " + STORE_MD + " on the branch " + branchName);
        }
        StoreManifest manifest = StoreManifestParser.parse(new String(content, StandardCharsets.UTF_8));
        Instant tipDate = reader.commitDate(ref);

        // The description and the tabs show files of the repository, named relative to its root.
        List<GitFile> files = new ArrayList<>();
        String filesUrl = filesBaseUrl == null ? null : filesBaseUrl + "/" + FILES_FOLDER + "/";
        readReferencedFiles(reader, ref, manifest.getDescriptionMarkdown(), "", files);
        String descriptionHtml = Markdown.toHtml(manifest.getDescriptionMarkdown(), filesUrl);
        List<GitModule.Tab> tabs = new ArrayList<>();
        for (StoreManifest.TabLink link : manifest.getTabs()) {
            byte[] tabContent = reader.readFile(ref, link.getFile());
            if (tabContent == null) {
                throw new StoreManifestException("The tab " + link.getTitle() + " links to " + link.getFile() + ", which the repository does not hold");
            }
            String markdown = new String(tabContent, StandardCharsets.UTF_8);
            readReferencedFiles(reader, ref, markdown, StringUtils.substringBeforeLast("/" + link.getFile(), "/").substring(1), files);
            String tabName = GitModule.Tab.nameOf(link.getTitle());
            if (tabs.stream().anyMatch(t -> t.getName().equals(tabName))) {
                throw new StoreManifestException("The tabs " + link.getTitle() + " and another one share the node name " + tabName);
            }
            tabs.add(new GitModule.Tab(link.getTitle(), tabName, Markdown.toHtml(markdown, filesUrl)));
        }

        List<ReadVersion> read = new ArrayList<>();
        for (StoreManifest.VersionLink link : manifest.getVersions()) {
            read.add(readVersion(reader, ref, link, filesBaseUrl));
        }
        read.sort((a, b) -> VersionNumbers.newestFirst().compare(a.link.getNumber(), b.link.getNumber()));
        List<GitModule.Version> versions = new ArrayList<>();
        for (int i = 0; i < read.size(); i++) {
            // The changelog of a version on GitHub is its changes since the version before it.
            String previousTag = i + 1 < read.size() ? read.get(i + 1).manifest.getTag() : null;
            versions.add(read.get(i).toVersion(reader.getUrl(), manifest, tipDate, previousTag));
        }

        GitFile icon = manifest.getIcon() == null ? null : readDeclaredFile(reader, ref, manifest.getIcon(), "icon");
        List<GitFile> screenshots = new ArrayList<>();
        for (String path : manifest.getScreenshots()) {
            GitFile screenshot = readDeclaredFile(reader, ref, path, "screenshot");
            if (screenshot != null) {
                screenshots.add(screenshot);
            }
        }
        return new GitModule(reader.getUrl(), branchName, manifest, descriptionHtml, files, tabs, versions, icon, screenshots);
    }

    /**
     * Read the files a Markdown text references, each once.
     *
     * @param baseDir the folder of the Markdown file inside the repository, which a relative
     *                reference is resolved against; empty for the root
     */
    private static void readReferencedFiles(GitRepositoryReader reader, String ref, String markdown, String baseDir, List<GitFile> files)
            throws IOException {
        for (String reference : Markdown.relativeReferences(markdown)) {
            if (GitModule.file(files, Markdown.baseName(reference)) != null) {
                continue;
            }
            String path = (baseDir.isEmpty() ? "" : baseDir + "/") + StringUtils.removeStart(reference, "./");
            GitFile file = readDeclaredFile(reader, ref, path, "referenced file");
            if (file != null) {
                files.add(file);
            }
        }
    }

    /** The folder of a version as read: its manifest, its notes rendered and its files. */
    private static final class ReadVersion {
        private final StoreManifest.VersionLink link;
        private final VersionManifest manifest;
        private final String notesHtml;
        private final List<GitFile> files;

        private ReadVersion(StoreManifest.VersionLink link, VersionManifest manifest, String notesHtml, List<GitFile> files) {
            this.link = link;
            this.manifest = manifest;
            this.notesHtml = notesHtml;
            this.files = files;
        }

        private GitModule.Version toVersion(String repositoryUrl, StoreManifest module, Instant tipDate, String previousTag) {
            String tagUrl = manifest.getTagUrl() != null ? manifest.getTagUrl() : GitHosting.tagUrl(repositoryUrl, manifest.getTag());
            String changelogUrl = manifest.getChangelogUrl() != null ? manifest.getChangelogUrl()
                    : GitHosting.compareUrl(repositoryUrl, previousTag, manifest.getTag());
            return new GitModule.Version(link.getNumber(), link.getFolder(), manifest.getArtifactUrl(), manifest.isDisabled(),
                    manifest.getTag(), tagUrl, changelogUrl, notesHtml,
                    StringUtils.defaultIfBlank(manifest.getRequiredVersion(), module.getRequiredVersion()),
                    manifest.getDependencies().isEmpty() ? module.getDependencies() : manifest.getDependencies(),
                    manifest.getDate() == null ? tipDate : manifest.getDate(), files);
        }
    }

    private static ReadVersion readVersion(GitRepositoryReader reader, String ref, StoreManifest.VersionLink link, String filesBaseUrl)
            throws IOException {
        String folder = link.getFolder();
        byte[] content = reader.readFile(ref, folder + "/" + VERSION_MD);
        if (content == null) {
            throw new StoreManifestException("The version " + link.getNumber() + " links to " + folder + ", which holds no " + VERSION_MD);
        }
        VersionManifest version = VersionManifest.parse(link.getNumber(), new String(content, StandardCharsets.UTF_8));
        String notes = version.getNotesMarkdown();
        if (StringUtils.isBlank(notes)) {
            byte[] changelog = reader.readFile(ref, folder + "/" + CHANGELOG_MD);
            notes = changelog == null ? null : new String(changelog, StandardCharsets.UTF_8);
        }
        String notesHtml = StringUtils.isBlank(notes) ? null
                : Markdown.toHtml(notes, filesBaseUrl == null ? null : filesBaseUrl + "/" + link.getNumber() + "/");

        List<GitFile> files = new ArrayList<>();
        for (String name : reader.listFiles(ref, folder)) {
            if (!name.toLowerCase().endsWith(".md")) {
                files.add(new GitFile(name, reader.readFile(ref, folder + "/" + name)));
            }
        }
        return new ReadVersion(link, version, notesHtml, files);
    }

    /** A file that {@code STORE.md} names, or null, with a warning, when the repository lacks it. */
    private static GitFile readDeclaredFile(GitRepositoryReader reader, String ref, String path, String what) throws IOException {
        byte[] bytes = reader.readFile(ref, StringUtils.removeStart(path, "./"));
        if (bytes == null) {
            LOGGER.warn("The repository {} declares the {} {} and does not hold it", reader.getUrl(), what, path);
            return null;
        }
        return new GitFile(Markdown.baseName(path), bytes);
    }
}
