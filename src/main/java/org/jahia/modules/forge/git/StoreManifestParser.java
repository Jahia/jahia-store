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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;

/**
 * Parses a {@code STORE.md}: its front matter, its description, the {@code ## Versions} section
 * that lists one link per version and the {@code ## Tabs} section that lists one link per tab.
 * The rules are the ones of docs/git-backed-modules.md.
 */
public final class StoreManifestParser {

    static final String FILE_NAME = "STORE.md";
    static final String FIELD_NAME = "name";
    static final String FIELD_GROUP_ID = "groupId";
    static final String FIELD_TITLE = "title";
    static final String FIELD_LICENSE = "license";
    static final String FIELD_ICON = "icon";
    static final String FIELD_SCREENSHOTS = "screenshots";
    static final String FIELD_AUTHOR = "author";
    static final String FIELD_AUTHOR_URL = "authorUrl";
    static final String FIELD_AUTHOR_EMAIL = "authorEmail";
    static final String FIELD_REQUIRED_VERSION = "requiredVersion";
    static final String FIELD_DEPENDENCIES = "dependencies";
    static final String VERSIONS_HEADING = "Versions";
    static final String TABS_HEADING = "Tabs";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    /** A list item that is a link: {@code - [1.2.0](store/1.2.0)}. */
    private static final Pattern LINK_ITEM = Pattern.compile("^\\s*[-*+]\\s+\\[([^\\]]+)\\]\\(<?([^)>\\s]+)>?\\)\\s*$");

    private StoreManifestParser() {
    }

    /**
     * @throws StoreManifestException when the file fails the contract; the message names the cause
     */
    public static StoreManifest parse(String content) {
        FrontMatterDocument doc = FrontMatterDocument.parse(FILE_NAME, content);
        String name = doc.coordinate(FIELD_NAME);
        String groupId = doc.coordinate(FIELD_GROUP_ID);
        String title = doc.mandatory(FIELD_TITLE);

        StringBuilder description = new StringBuilder();
        List<StoreManifest.VersionLink> versions = new ArrayList<>();
        List<StoreManifest.TabLink> tabs = new ArrayList<>();
        Set<String> seenVersions = new HashSet<>();
        Set<String> seenTabs = new HashSet<>();
        // The level-2 section the line belongs to: Versions, Tabs, or null for the description.
        String section = null;
        for (String line : doc.getBody().split("\r?\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                // A section ends at the next heading of the same level or higher.
                String text = heading.group(2);
                section = heading.group(1).length() != 2 ? null
                        : VERSIONS_HEADING.equalsIgnoreCase(text) ? VERSIONS_HEADING
                        : TABS_HEADING.equalsIgnoreCase(text) ? TABS_HEADING : null;
                if (section != null) {
                    continue;
                }
            }
            if (section == null) {
                description.append(line).append('\n');
                continue;
            }
            Matcher item = LINK_ITEM.matcher(line);
            if (!item.matches()) {
                if (StringUtils.isNotBlank(line)) {
                    throw new StoreManifestException(FILE_NAME + " section '" + section + "' holds a line that is not a link: " + line.trim());
                }
                continue;
            }
            if (VERSIONS_HEADING.equals(section)) {
                versions.add(versionLink(item.group(1).trim(), item.group(2).trim(), seenVersions));
            } else {
                tabs.add(tabLink(item.group(1).trim(), item.group(2).trim(), seenTabs));
            }
        }

        return new StoreManifest(name, groupId, title,
                doc.optional(FIELD_LICENSE), doc.repositoryPath(FIELD_ICON), doc.repositoryPaths(FIELD_SCREENSHOTS),
                doc.optional(FIELD_AUTHOR), doc.optional(FIELD_AUTHOR_URL), doc.optional(FIELD_AUTHOR_EMAIL),
                doc.optional(FIELD_REQUIRED_VERSION), doc.stringList(FIELD_DEPENDENCIES),
                description.toString(), versions, tabs);
    }

    private static StoreManifest.VersionLink versionLink(String number, String target, Set<String> seen) {
        if (!VersionNumbers.isValid(number)) {
            throw new StoreManifestException(FILE_NAME + " section 'Versions' names a version that is not a Maven version: " + number);
        }
        if (!seen.add(number)) {
            throw new StoreManifestException(FILE_NAME + " section 'Versions' names the version " + number + " twice");
        }
        String folder = repositoryPath(target);
        if (folder == null) {
            throw new StoreManifestException(FILE_NAME + " section 'Versions' must link the version " + number
                    + " to a folder inside the repository: " + target);
        }
        return new StoreManifest.VersionLink(number, folder);
    }

    private static StoreManifest.TabLink tabLink(String title, String target, Set<String> seen) {
        if (!seen.add(title.toLowerCase())) {
            throw new StoreManifestException(FILE_NAME + " section 'Tabs' names the tab " + title + " twice");
        }
        String file = repositoryPath(target);
        if (file == null || !file.toLowerCase().endsWith(".md")) {
            throw new StoreManifestException(FILE_NAME + " section 'Tabs' must link the tab " + title
                    + " to a Markdown file inside the repository: " + target);
        }
        return new StoreManifest.TabLink(title, file);
    }

    /** A link target as a path inside the repository, without a leading {@code ./} nor a trailing slash, or null. */
    private static String repositoryPath(String target) {
        String path = StringUtils.removeEnd(StringUtils.removeStart(target, "./"), "/");
        if (path.isEmpty() || path.contains("://") || !FrontMatterDocument.SAFE_REPOSITORY_PATH.matcher(path).matches()) {
            return null;
        }
        return path;
    }
}
