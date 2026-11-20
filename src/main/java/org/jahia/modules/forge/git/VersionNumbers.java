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

/** What a version number of {@code STORE.md} may be, and how two of them order. */
public final class VersionNumbers {

    /** A Maven version: it starts with a digit and holds letters, digits, '.', '_' and '-'. */
    private static final Pattern VERSION = Pattern.compile("^\\d[A-Za-z0-9._-]*$");

    private VersionNumbers() {
    }

    public static boolean isValid(String number) {
        return number != null && VERSION.matcher(number).matches();
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
