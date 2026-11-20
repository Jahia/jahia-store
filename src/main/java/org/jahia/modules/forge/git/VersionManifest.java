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

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * The content of the {@code VERSION.md} of one version, as docs/git-backed-modules.md states
 * it: the artefact URL, the disabled flag, the date, the version-specific fields and the release
 * notes. Immutable; built by {@link #parse}.
 */
public final class VersionManifest {

    static final String FILE_NAME = "VERSION.md";
    static final String FIELD_ARTIFACT = "artifact";
    static final String FIELD_DATE = "date";
    static final String FIELD_DISABLED = "disabled";
    static final String FIELD_REQUIRED_VERSION = "requiredVersion";
    static final String FIELD_DEPENDENCIES = "dependencies";
    /** A placeholder the artefact URL may carry, replaced by the version number. */
    static final String VERSION_PLACEHOLDER = "{version}";

    private final String artifactUrl;
    private final boolean disabled;
    private final Instant date;
    private final String requiredVersion;
    private final List<String> dependencies;
    private final String notesMarkdown;

    private VersionManifest(String artifactUrl, boolean disabled, Instant date, String requiredVersion,
                            List<String> dependencies, String notesMarkdown) {
        this.artifactUrl = artifactUrl;
        this.disabled = disabled;
        this.date = date;
        this.requiredVersion = requiredVersion;
        this.dependencies = dependencies == null ? Collections.emptyList() : Collections.unmodifiableList(dependencies);
        this.notesMarkdown = notesMarkdown;
    }

    /**
     * @param number the version number, which names the file in a refusal
     * @throws StoreManifestException when the file fails the contract; the message names the version and the cause
     */
    public static VersionManifest parse(String number, String content) {
        String fileName = FILE_NAME + " of the version " + number;
        FrontMatterDocument doc = FrontMatterDocument.parse(fileName, content);
        String artifact = doc.mandatory(FIELD_ARTIFACT).replace(VERSION_PLACEHOLDER, number);
        validateArtifact(fileName, artifact);
        return new VersionManifest(artifact, doc.bool(FIELD_DISABLED, false), date(fileName, doc.optional(FIELD_DATE)),
                doc.optional(FIELD_REQUIRED_VERSION), doc.stringList(FIELD_DEPENDENCIES), doc.getBody());
    }

    private static void validateArtifact(String fileName, String artifact) {
        URI uri;
        try {
            uri = new URI(artifact);
        } catch (URISyntaxException e) {
            throw new StoreManifestException(fileName + " field '" + FIELD_ARTIFACT + "' is not a valid URL: " + artifact, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!uri.isAbsolute() || uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new StoreManifestException(fileName + " field '" + FIELD_ARTIFACT + "' must be an absolute http or https URL: " + artifact);
        }
    }

    /** A {@code YYYY-MM-DD} date, at midnight UTC, or null when absent. */
    private static Instant date(String fileName, String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new StoreManifestException(fileName + " field '" + FIELD_DATE + "' must be a YYYY-MM-DD date: " + value, e);
        }
    }

    /** The artefact URL, absolute, with the placeholder replaced. */
    public String getArtifactUrl() {
        return artifactUrl;
    }

    /** True when the file disables the version: the store shows it to the editors of the module only. */
    public boolean isDisabled() {
        return disabled;
    }

    /** The release date, or null when the file states none. */
    public Instant getDate() {
        return date;
    }

    /** The lowest Jahia version, or null when the file states none. */
    public String getRequiredVersion() {
        return requiredVersion;
    }

    /** The dependencies, empty when the file states none. */
    public List<String> getDependencies() {
        return dependencies;
    }

    /** The Markdown body: the release notes, possibly blank. */
    public String getNotesMarkdown() {
        return notesMarkdown;
    }
}
