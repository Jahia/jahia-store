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

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * What the store knows of a git-backed module at one point in time: the manifest, the description
 * rendered with the files it shows, the versions with their files, the icon and the screenshots.
 * Immutable, so a read never sees a half refresh.
 */
public final class GitModule {

    /** One version: its manifest, its release notes rendered, and the files of its folder. */
    public static final class Version {
        private final String number;
        private final String folder;
        private final String artifactUrl;
        private final boolean disabled;
        private final String tag;
        private final String tagUrl;
        private final String changelogUrl;
        private final String notesHtml;
        private final String requiredVersion;
        private final List<String> dependencies;
        private final Instant date;
        private final List<GitFile> files;

        Version(String number, String folder, String artifactUrl, boolean disabled, String tag, String tagUrl, String changelogUrl,
                String notesHtml, String requiredVersion, List<String> dependencies, Instant date, List<GitFile> files) {
            this.number = number;
            this.folder = folder;
            this.artifactUrl = artifactUrl;
            this.disabled = disabled;
            this.tag = tag;
            this.tagUrl = tagUrl;
            this.changelogUrl = changelogUrl;
            this.notesHtml = notesHtml;
            this.requiredVersion = requiredVersion;
            this.dependencies = dependencies == null ? Collections.emptyList() : Collections.unmodifiableList(dependencies);
            this.date = date;
            this.files = files == null ? Collections.emptyList() : Collections.unmodifiableList(files);
        }

        public String getNumber() {
            return number;
        }

        /** The folder of the version inside the repository. */
        public String getFolder() {
            return folder;
        }

        public String getArtifactUrl() {
            return artifactUrl;
        }

        /** True when the version file disables the version: served unpublished, for the editors of the module only. */
        public boolean isDisabled() {
            return disabled;
        }

        /** The git tag of the version, or null. */
        public String getTag() {
            return tag;
        }

        /** The page of the tag on the git host, or null. */
        public String getTagUrl() {
            return tagUrl;
        }

        /** The page of the changelog of the version on the git host, or null. */
        public String getChangelogUrl() {
            return changelogUrl;
        }

        /** The release notes as HTML, or null when the version has none. */
        public String getNotesHtml() {
            return notesHtml;
        }

        public String getRequiredVersion() {
            return requiredVersion;
        }

        public List<String> getDependencies() {
            return dependencies;
        }

        public Instant getDate() {
            return date;
        }

        /** The files of the folder served beside the version, other than its Markdown files. */
        public List<GitFile> getFiles() {
            return files;
        }

        public GitFile file(String name) {
            return GitModule.file(files, name);
        }
    }

    /** One tab of the page of the module: its title, its node name and its content rendered. */
    public static final class Tab {
        private final String title;
        private final String name;
        private final String contentHtml;

        Tab(String title, String name, String contentHtml) {
            this.title = title;
            this.name = name;
            this.contentHtml = contentHtml;
        }

        public String getTitle() {
            return title;
        }

        /** The node name of the tab: the title in lowercase with every run of other characters as one dash. */
        public String getName() {
            return name;
        }

        public String getContentHtml() {
            return contentHtml;
        }

        static String nameOf(String title) {
            String name = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            return name.isEmpty() ? "tab" : name;
        }
    }

    private final String repositoryUrl;
    private final String branch;
    private final StoreManifest manifest;
    private final String descriptionHtml;
    private final List<GitFile> files;
    private final List<Tab> tabs;
    private final List<Version> versions;
    private final GitFile icon;
    private final List<GitFile> screenshots;
    private final Instant readAt;

    GitModule(String repositoryUrl, String branch, StoreManifest manifest, String descriptionHtml, List<GitFile> files,
              List<Tab> tabs, List<Version> versions, GitFile icon, List<GitFile> screenshots) {
        this.repositoryUrl = repositoryUrl;
        this.branch = branch;
        this.manifest = manifest;
        this.descriptionHtml = descriptionHtml;
        this.files = files == null ? Collections.emptyList() : Collections.unmodifiableList(files);
        this.tabs = tabs == null ? Collections.emptyList() : Collections.unmodifiableList(tabs);
        this.versions = Collections.unmodifiableList(versions);
        this.icon = icon;
        this.screenshots = screenshots == null ? Collections.emptyList() : Collections.unmodifiableList(screenshots);
        this.readAt = Instant.now();
    }

    static GitFile file(List<GitFile> files, String name) {
        for (GitFile file : files) {
            if (file.getName().equals(name)) {
                return file;
            }
        }
        return null;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    /** The store branch the module was read from. */
    public String getBranch() {
        return branch;
    }

    public StoreManifest getManifest() {
        return manifest;
    }

    /** The description rendered to HTML, its relative images and links pointing at {@link #getFiles()}. */
    public String getDescriptionHtml() {
        return descriptionHtml;
    }

    /** The files the description shows, served under the {@code files} folder of the module. */
    public List<GitFile> getFiles() {
        return files;
    }

    /** The tabs of the {@code ## Tabs} section, in the order of the file. */
    public List<Tab> getTabs() {
        return tabs;
    }

    public Tab tab(String name) {
        for (Tab tab : tabs) {
            if (tab.getName().equals(name)) {
                return tab;
            }
        }
        return null;
    }

    /** True when a tab carries the title, whatever its case. */
    public boolean hasTab(String title) {
        for (Tab tab : tabs) {
            if (tab.getTitle().equalsIgnoreCase(title)) {
                return true;
            }
        }
        return false;
    }

    /** Every version of the manifest, the disabled ones included, newest first. */
    public List<Version> getVersions() {
        return versions;
    }

    public Version version(String number) {
        for (Version v : versions) {
            if (v.getNumber().equals(number)) {
                return v;
            }
        }
        return null;
    }

    /** The icon, or null when the repository declares none or lacks the file. */
    public GitFile getIcon() {
        return icon;
    }

    /** The screenshots the repository declares and holds. */
    public List<GitFile> getScreenshots() {
        return screenshots;
    }

    public GitFile screenshot(String name) {
        return file(screenshots, name);
    }

    public Instant getReadAt() {
        return readAt;
    }
}
