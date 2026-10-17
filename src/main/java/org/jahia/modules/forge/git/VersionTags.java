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

import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which tags of a repository are versions, and the version number of a tag. A pre-release tag
 * never matches the default pattern.
 */
public final class VersionTags {

    /** Accepts {@code 1.2.3} and {@code v1.2.3}, and no pre-release suffix. */
    public static final String DEFAULT_PATTERN = "^v?\\d+\\.\\d+\\.\\d+$";

    private VersionTags() {
    }

    /** Compile a pattern of a registration, or the default one when it is blank. */
    public static Pattern compile(String pattern) {
        String p = pattern == null || pattern.trim().isEmpty() ? DEFAULT_PATTERN : pattern.trim();
        try {
            return Pattern.compile(p);
        } catch (PatternSyntaxException e) {
            throw new StoreManifestException("The tag pattern is not a valid regular expression: " + p, e);
        }
    }

    public static boolean isVersion(Pattern pattern, String tag) {
        return tag != null && pattern.matcher(tag).matches();
    }

    /** The version number of a tag: the tag without its {@code v} prefix. */
    public static String versionOf(String tag) {
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /** Newest version first, on the numeric segments, then on the text. */
    public static Comparator<String> newestFirst() {
        return (a, b) -> {
            String[] pa = a.split("\\D+");
            String[] pb = b.split("\\D+");
            int len = Math.max(pa.length, pb.length);
            for (int i = 0; i < len; i++) {
                long va = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0;
                long vb = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;
                if (va != vb) {
                    return Long.compare(vb, va);
                }
            }
            return b.compareTo(a);
        };
    }
}
