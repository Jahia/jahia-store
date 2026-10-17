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
 * What the store knows of a git-backed module at one point in time: the root manifest, the
 * versions read from the tags, and the icon. Immutable, so a read never sees a half refresh.
 */
public final class GitModule {

    /** One version: the tag, the manifest at the tag and the release notes at the tag. */
    public static final class Version {
        private final String tag;
        private final String number;
        private final String artifactUrl;
        private final String changelogHtml;
        private final String requiredVersion;
        private final List<String> dependencies;
        private final Instant date;

        Version(String tag, String number, String artifactUrl, String changelogHtml, String requiredVersion,
                List<String> dependencies, Instant date) {
            this.tag = tag;
            this.number = number;
            this.artifactUrl = artifactUrl;
            this.changelogHtml = changelogHtml;
            this.requiredVersion = requiredVersion;
            this.dependencies = dependencies == null ? Collections.emptyList() : Collections.unmodifiableList(dependencies);
            this.date = date;
        }

        public String getTag() {
            return tag;
        }

        public String getNumber() {
            return number;
        }

        public String getArtifactUrl() {
            return artifactUrl;
        }

        /** The release notes as HTML, or null. */
        public String getChangelogHtml() {
            return changelogHtml;
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
    }

    private final String repositoryUrl;
    private final String branch;
    private final StoreManifest manifest;
    private final List<Version> versions;
    private final String iconName;
    private final String iconMimeType;
    private final byte[] iconBytes;
    private final Instant readAt;

    GitModule(String repositoryUrl, String branch, StoreManifest manifest, List<Version> versions,
              String iconName, String iconMimeType, byte[] iconBytes) {
        this.repositoryUrl = repositoryUrl;
        this.branch = branch;
        this.manifest = manifest;
        this.versions = Collections.unmodifiableList(versions);
        this.iconName = iconName;
        this.iconMimeType = iconMimeType;
        this.iconBytes = iconBytes;
        this.readAt = Instant.now();
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getBranch() {
        return branch;
    }

    public StoreManifest getManifest() {
        return manifest;
    }

    /** Newest first. */
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

    /** The file name of the icon, or null when the repository declares none or lacks the file. */
    public String getIconName() {
        return iconName;
    }

    public String getIconMimeType() {
        return iconMimeType;
    }

    public byte[] getIconBytes() {
        return iconBytes;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
