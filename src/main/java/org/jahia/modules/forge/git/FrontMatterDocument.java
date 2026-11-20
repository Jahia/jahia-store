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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * A Markdown file with a YAML front matter between two {@code ---} lines, as {@code STORE.md}
 * and {@code VERSION.md} are: the fields of the front matter, and the body. The accessors apply
 * the rules of docs/git-backed-modules.md and name the file and the field in each refusal.
 */
final class FrontMatterDocument {

    /** A Maven coordinate segment, which is also what the proxy URL and a node name accept. */
    static final Pattern SAFE_COORDINATE = Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9._-]+$");
    /** A path inside the repository: relative, and never climbing out of it. */
    static final Pattern SAFE_REPOSITORY_PATH = Pattern.compile("^(?!/)(?!.*(^|/)\\.\\.(/|$))[^\\\\\\r\\n]+$");

    private static final String DELIMITER = "---";

    private final String fileName;
    private final Map<String, Object> fields;
    private final String body;

    private FrontMatterDocument(String fileName, Map<String, Object> fields, String body) {
        this.fileName = fileName;
        this.fields = fields;
        this.body = body;
    }

    /**
     * @param fileName the name of the file, for the refusals
     * @throws StoreManifestException when the front matter is absent, unclosed or not a YAML map
     */
    static FrontMatterDocument parse(String fileName, String content) {
        if (content == null) {
            throw new StoreManifestException(fileName + " is empty");
        }
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        String[] lines = text.split("\r?\n", -1);
        if (lines.length == 0 || !DELIMITER.equals(lines[0].trim())) {
            throw new StoreManifestException(fileName + " must start with a '---' front matter line");
        }
        for (int i = 1; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i].trim())) {
                String frontMatter = String.join("\n", Arrays.copyOfRange(lines, 1, i));
                String body = String.join("\n", Arrays.copyOfRange(lines, i + 1, lines.length));
                return new FrontMatterDocument(fileName, loadYaml(fileName, frontMatter), body);
            }
        }
        throw new StoreManifestException(fileName + " front matter has no closing '---' line");
    }

    /**
     * YAML reads an unquoted scalar as a number or a date when it looks like one, and a git tag
     * such as {@code 3_6_0} or a version such as {@code 8.2} is one. Every scalar but a boolean
     * and a null stays the text that was written.
     */
    private static final class TextResolver extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO", 10);
            addImplicitResolver(Tag.NULL, NULL, "~nN\0", 10);
            addImplicitResolver(Tag.NULL, EMPTY, null, 10);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String fileName, String frontMatter) {
        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            loaded = new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()), new DumperOptions(), options,
                    new TextResolver()).load(frontMatter);
        } catch (RuntimeException e) {
            throw new StoreManifestException(fileName + " front matter is not valid YAML: " + e.getMessage(), e);
        }
        if (loaded == null) {
            return Collections.emptyMap();
        }
        if (!(loaded instanceof Map)) {
            throw new StoreManifestException(fileName + " front matter must be a YAML map of fields");
        }
        return (Map<String, Object>) loaded;
    }

    String getFileName() {
        return fileName;
    }

    /** The Markdown body, after the front matter. */
    String getBody() {
        return body;
    }

    String mandatory(String field) {
        String value = optional(field);
        if (value == null) {
            throw new StoreManifestException(fileName + " lacks the mandatory field '" + field + "'");
        }
        return value;
    }

    /** A single-valued field, trimmed, or null when absent or blank. */
    String optional(String field) {
        Object value = fields.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof List || value instanceof Map) {
            throw new StoreManifestException(fileName + " field '" + field + "' must be a single value");
        }
        String text = String.valueOf(value).trim();
        return StringUtils.isEmpty(text) ? null : text;
    }

    /** A field that is a YAML list of strings, or a comma-separated string; empty when absent. */
    List<String> stringList(String field) {
        Object value = fields.get(field);
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
        if (value instanceof Map) {
            throw new StoreManifestException(fileName + " field '" + field + "' must be a list");
        }
        for (String item : String.valueOf(value).split(",")) {
            if (StringUtils.isNotBlank(item)) {
                result.add(item.trim());
            }
        }
        return result;
    }

    /** A boolean field: {@code true}, {@code false}, {@code yes} or {@code no}; the default when absent. */
    boolean bool(String field, boolean defaultValue) {
        String value = optional(field);
        if (value == null) {
            return defaultValue;
        }
        String lower = value.toLowerCase();
        if ("true".equals(lower) || "yes".equals(lower)) {
            return true;
        }
        if ("false".equals(lower) || "no".equals(lower)) {
            return false;
        }
        throw new StoreManifestException(fileName + " field '" + field + "' must be true or false: " + value);
    }

    /** A field that names a path inside the repository, or null when absent. */
    String repositoryPath(String field) {
        String value = optional(field);
        if (value != null && !SAFE_REPOSITORY_PATH.matcher(value).matches()) {
            throw new StoreManifestException(fileName + " field '" + field + "' must be a relative path inside the repository: " + value);
        }
        return value;
    }

    /** A list field whose items name paths inside the repository. */
    List<String> repositoryPaths(String field) {
        List<String> paths = stringList(field);
        for (String path : paths) {
            if (!SAFE_REPOSITORY_PATH.matcher(path).matches()) {
                throw new StoreManifestException(fileName + " field '" + field + "' must list relative paths inside the repository: " + path);
            }
        }
        return paths;
    }

    /** A field whose value is a Maven coordinate segment. */
    String coordinate(String field) {
        String value = mandatory(field);
        if (!SAFE_COORDINATE.matcher(value).matches()) {
            throw new StoreManifestException(fileName + " field '" + field + "' must hold letters, digits, '.', '_' and '-' only: " + value);
        }
        return value;
    }
}
