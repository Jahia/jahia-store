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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionNumbersTest {

    @Test
    @DisplayName("a version starts with a digit and holds coordinate characters only")
    void validity() {
        assertThat(VersionNumbers.isValid("1.2.3")).isTrue();
        assertThat(VersionNumbers.isValid("3.2.1-jahia8")).isTrue();
        assertThat(VersionNumbers.isValid("v1.2.3")).isFalse();
        assertThat(VersionNumbers.isValid("1.2/3")).isFalse();
        assertThat(VersionNumbers.isValid("latest")).isFalse();
        assertThat(VersionNumbers.isValid("")).isFalse();
        assertThat(VersionNumbers.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("newest first orders numerically, 1.10 before 1.9")
    void newestFirst() {
        List<String> versions = new ArrayList<>(Arrays.asList("1.9.0", "1.10.0", "2.0.0", "1.9.1"));

        versions.sort(VersionNumbers.newestFirst());

        assertThat(versions).containsExactly("2.0.0", "1.10.0", "1.9.1", "1.9.0");
    }
}
