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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a {@code STORE.md}: a YAML front matter between two {@code ---} lines, then a Markdown
 * body. The mandatory fields and their rules are the ones of docs/git-backed-modules.md.
 */
public final class StoreManifestParser {

    static final String FIELD_NAME = "name";
    static final String FIELD_GROUP_ID = "groupId";
    static final String FIELD_TITLE = "title";
    static final String FIELD_ARTIFACT = "artifact";
    static final String FIELD_LICENSE = "license";
    static final String FIELD_ICON = "icon";
    static final String FIELD_AUTHOR = "author";
    static final String FIELD_AUTHOR_URL = "authorUrl";
    static final String FIELD_AUTHOR_EMAIL = "authorEmail";
    static final String FIELD_REQUIRED_VERSION = "requiredVersion";
    static final String FIELD_DEPENDENCIES = "dependencies";

    private static final String FRONT_MATTER_DELIMITER = "---";
    /** A Maven coordinate segment, which is also what the proxy URL and the node name accept. */
    private static final Pattern SAFE_COORDINATE = Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9._-]+$");
    /** A path inside the repository: relative, and never climbing out of it. */
    private static final Pattern SAFE_REPOSITORY_PATH = Pattern.compile("^(?!/)(?!.*(^|/)\\.\\.(/|$))[^\\\\\\r\\n]+$");

    private StoreManifestParser() {
    }

    /**
     * Parse the content of a {@code STORE.md}.
     *
     * @throws StoreManifestException when the front matter is absent, is not a YAML map, lacks a
     *                                mandatory field, or holds a field that fails its rule
     */
    public static StoreManifest parse(String content) {
        if (content == null) {
            throw new StoreManifestException("STORE.md is empty");
        }
        String[] parts = splitFrontMatter(content);
        Map<String, Object> fields = loadYaml(parts[0]);

        String name = mandatory(fields, FIELD_NAME);
        if (!SAFE_COORDINATE.matcher(name).matches()) {
            throw new StoreManifestException("STORE.md field 'name' must be a Maven artifactId (letters, digits, '.', '_' and '-'): " + name);
        }
        String groupId = mandatory(fields, FIELD_GROUP_ID);
        if (!SAFE_COORDINATE.matcher(groupId).matches()) {
            throw new StoreManifestException("STORE.md field 'groupId' must be a Maven groupId (letters, digits, '.', '_' and '-'): " + groupId);
        }
        String title = mandatory(fields, FIELD_TITLE);
        String artifact = mandatory(fields, FIELD_ARTIFACT);
        validateArtifact(artifact);
        String icon = optional(fields, FIELD_ICON);
        if (icon != null && !SAFE_REPOSITORY_PATH.matcher(icon).matches()) {
            throw new StoreManifestException("STORE.md field 'icon' must be a relative path inside the repository: " + icon);
        }

        return new StoreManifest(name, groupId, title, artifact,
                optional(fields, FIELD_LICENSE), icon,
                optional(fields, FIELD_AUTHOR), optional(fields, FIELD_AUTHOR_URL), optional(fields, FIELD_AUTHOR_EMAIL),
                optional(fields, FIELD_REQUIRED_VERSION), stringList(fields.get(FIELD_DEPENDENCIES)),
                Markdown.toHtml(parts[1]));
    }

    /** The front matter and the body, or a {@link StoreManifestException} when the file has no front matter. */
    private static String[] splitFrontMatter(String content) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        String[] lines = text.split("\r?\n", -1);
        if (lines.length == 0 || !FRONT_MATTER_DELIMITER.equals(lines[0].trim())) {
            throw new StoreManifestException("STORE.md must start with a '---' front matter line");
        }
        for (int i = 1; i < lines.length; i++) {
            if (FRONT_MATTER_DELIMITER.equals(lines[i].trim())) {
                String frontMatter = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, i));
                String body = String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length));
                return new String[]{frontMatter, body};
            }
        }
        throw new StoreManifestException("STORE.md front matter has no closing '---' line");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String frontMatter) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(frontMatter);
        } catch (RuntimeException e) {
            throw new StoreManifestException("STORE.md front matter is not valid YAML: " + e.getMessage(), e);
        }
        if (!(loaded instanceof Map)) {
            throw new StoreManifestException("STORE.md front matter must be a YAML map of fields");
        }
        return (Map<String, Object>) loaded;
    }

    private static String mandatory(Map<String, Object> fields, String field) {
        String value = optional(fields, field);
        if (value == null) {
            throw new StoreManifestException("STORE.md lacks the mandatory field '" + field + "'");
        }
        return value;
    }

    private static String optional(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof List || value instanceof Map) {
            throw new StoreManifestException("STORE.md field '" + field + "' must be a single value");
        }
        String text = String.valueOf(value).trim();
        return StringUtils.isEmpty(text) ? null : text;
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null && StringUtils.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item).trim());
                }
            }
            return result;
        }
        for (String item : String.valueOf(value).split(",")) {
            if (StringUtils.isNotBlank(item)) {
                result.add(item.trim());
            }
        }
        return result;
    }

    private static void validateArtifact(String artifact) {
        URI uri;
        try {
            // The placeholder is not a legal URI character; validate the shape with it filled.
            uri = new URI(artifact.replace(StoreManifest.VERSION_PLACEHOLDER, "0"));
        } catch (URISyntaxException e) {
            throw new StoreManifestException("STORE.md field 'artifact' is not a valid URL: " + artifact, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!uri.isAbsolute() || uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new StoreManifestException("STORE.md field 'artifact' must be an absolute http or https URL: " + artifact);
        }
    }
}
