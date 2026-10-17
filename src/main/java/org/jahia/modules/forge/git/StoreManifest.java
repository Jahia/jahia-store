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
 * The store fields of a {@code STORE.md}, as docs/git-backed-modules.md states them, plus the
 * description that its Markdown body gives. Immutable; built by {@link StoreManifestParser}.
 */
public final class StoreManifest {

    /** The placeholder of {@link #getArtifact()} that the version number replaces. */
    public static final String VERSION_PLACEHOLDER = "{version}";

    private final String name;
    private final String groupId;
    private final String title;
    private final String artifact;
    private final String license;
    private final String icon;
    private final String author;
    private final String authorUrl;
    private final String authorEmail;
    private final String requiredVersion;
    private final List<String> dependencies;
    private final String descriptionHtml;

    StoreManifest(String name, String groupId, String title, String artifact, String license, String icon,
                  String author, String authorUrl, String authorEmail, String requiredVersion,
                  List<String> dependencies, String descriptionHtml) {
        this.name = name;
        this.groupId = groupId;
        this.title = title;
        this.artifact = artifact;
        this.license = license;
        this.icon = icon;
        this.author = author;
        this.authorUrl = authorUrl;
        this.authorEmail = authorEmail;
        this.requiredVersion = requiredVersion;
        this.dependencies = dependencies == null ? Collections.emptyList() : Collections.unmodifiableList(dependencies);
        this.descriptionHtml = descriptionHtml;
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

    /** The artefact URL template, with {@link #VERSION_PLACEHOLDER} where the version goes. */
    public String getArtifact() {
        return artifact;
    }

    /** The artefact URL of one version. */
    public String artifactUrl(String version) {
        return artifact.replace(VERSION_PLACEHOLDER, version);
    }

    public String getLicense() {
        return license;
    }

    /** The path of the icon inside the repository, or null. */
    public String getIcon() {
        return icon;
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

    public String getRequiredVersion() {
        return requiredVersion;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    /** The Markdown body of the file rendered to HTML, never null. */
    public String getDescriptionHtml() {
        return descriptionHtml;
    }
}
