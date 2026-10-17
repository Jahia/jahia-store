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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the section of one version from a {@code CHANGELOG.md} in the Keep a Changelog
 * layout: the text under the {@code ##} heading that names the version, up to the next
 * {@code ##} heading.
 */
public final class ChangelogParser {

    private static final Pattern SECTION_HEADING = Pattern.compile("^##\\s+(.*)$");

    private ChangelogParser() {
    }

    /**
     * @return the Markdown of the section of {@code version}, or null when the changelog has none
     */
    public static String section(String changelog, String version) {
        if (changelog == null || version == null) {
            return null;
        }
        Pattern heading = Pattern.compile("^\\[?v?" + Pattern.quote(version) + "\\]?(\\s.*|$)");
        StringBuilder section = null;
        for (String line : changelog.split("\r?\n")) {
            Matcher m = SECTION_HEADING.matcher(line);
            if (m.matches()) {
                if (section != null) {
                    break;
                }
                if (heading.matcher(m.group(1).trim()).matches()) {
                    section = new StringBuilder();
                }
            } else if (section != null) {
                section.append(line).append('\n');
            }
        }
        if (section == null) {
            return null;
        }
        String text = section.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
