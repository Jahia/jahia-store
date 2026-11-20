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

import java.util.Collections;
import java.util.List;

/**
 * The content of a {@code STORE.md}, as docs/git-backed-modules.md states it: the fields shared
 * by every version, the description that its Markdown body gives, and the versions that its
 * {@code ## Versions} section lists. Immutable; built by {@link StoreManifestParser}.
 */
public final class StoreManifest {

    /** One entry of the {@code ## Versions} section: the version number and its folder. */
    public static final class VersionLink {
        private final String number;
        private final String folder;

        VersionLink(String number, String folder) {
            this.number = number;
            this.folder = folder;
        }

        public String getNumber() {
            return number;
        }

        /** The folder of the version, relative to the root of the repository, without a trailing slash. */
        public String getFolder() {
            return folder;
        }
    }

    private final String name;
    private final String groupId;
    private final String title;
    private final String license;
    private final String icon;
    private final List<String> screenshots;
    private final String author;
    private final String authorUrl;
    private final String authorEmail;
    private final String requiredVersion;
    private final List<String> dependencies;
    private final String descriptionMarkdown;
    private final List<VersionLink> versions;
    private final List<TabLink> tabs;

    /** One entry of the {@code ## Tabs} section: the title of the tab and the Markdown file of its content. */
    public static final class TabLink {
        private final String title;
        private final String file;

        TabLink(String title, String file) {
            this.title = title;
            this.file = file;
        }

        public String getTitle() {
            return title;
        }

        /** The Markdown file, relative to the root of the repository. */
        public String getFile() {
            return file;
        }
    }

    StoreManifest(String name, String groupId, String title, String license, String icon, List<String> screenshots,
                  String author, String authorUrl, String authorEmail, String requiredVersion,
                  List<String> dependencies, String descriptionMarkdown, List<VersionLink> versions, List<TabLink> tabs) {
        this.name = name;
        this.groupId = groupId;
        this.title = title;
        this.license = license;
        this.icon = icon;
        this.screenshots = screenshots == null ? Collections.emptyList() : Collections.unmodifiableList(screenshots);
        this.author = author;
        this.authorUrl = authorUrl;
        this.authorEmail = authorEmail;
        this.requiredVersion = requiredVersion;
        this.dependencies = dependencies == null ? Collections.emptyList() : Collections.unmodifiableList(dependencies);
        this.descriptionMarkdown = descriptionMarkdown == null ? "" : descriptionMarkdown;
        this.versions = versions == null ? Collections.emptyList() : Collections.unmodifiableList(versions);
        this.tabs = tabs == null ? Collections.emptyList() : Collections.unmodifiableList(tabs);
    }

    public String getName() {
        return name;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getTitle() {
        return title;
    }

    public String getLicense() {
        return license;
    }

    /** The path of the icon inside the repository, or null. */
    public String getIcon() {
        return icon;
    }

    /** The paths of the screenshots inside the repository. */
    public List<String> getScreenshots() {
        return screenshots;
    }

    public String getAuthor() {
        return author;
    }

    public String getAuthorUrl() {
        return authorUrl;
    }

    public String getAuthorEmail() {
        return authorEmail;
    }

    /** The lowest Jahia version, for a version that states none; or null. */
    public String getRequiredVersion() {
        return requiredVersion;
    }

    /** The dependencies, for a version that states none. */
    public List<String> getDependencies() {
        return dependencies;
    }

    /** The Markdown body without its {@code ## Versions} section; never null. */
    public String getDescriptionMarkdown() {
        return descriptionMarkdown;
    }

    /** The description rendered to HTML, with its relative links as they are; never null. */
    public String getDescriptionHtml() {
        return Markdown.toHtml(descriptionMarkdown);
    }

    /** The versions of the {@code ## Versions} section, in the order of the file. */
    public List<VersionLink> getVersions() {
        return versions;
    }

    /** The tabs of the {@code ## Tabs} section, in the order of the file. */
    public List<TabLink> getTabs() {
        return tabs;
    }
}
