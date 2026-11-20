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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GitHostingTest {

    @Test
    @DisplayName("a GitHub repository gives the page of a tag and the changes between two tags")
    void github() {
        String repo = "https://github.com/Jahia/server-availability-manager.git";

        assertThat(GitHosting.tagUrl(repo, "3_6_0")).isEqualTo("https://github.com/Jahia/server-availability-manager/releases/tag/3_6_0");
        assertThat(GitHosting.compareUrl(repo, "3_5_0", "3_6_0")).isEqualTo("https://github.com/Jahia/server-availability-manager/compare/3_5_0...3_6_0");
        assertThat(GitHosting.tagUrl("https://github.com/Jahia/server-availability-manager/", "3_6_0")).endsWith("/releases/tag/3_6_0");
    }

    @Test
    @DisplayName("another host, a missing tag or a bare GitHub URL give nothing")
    void unknownHost() {
        assertThat(GitHosting.tagUrl("https://gitlab.example.com/org/repo.git", "1.0.0")).isNull();
        assertThat(GitHosting.tagUrl("https://github.com/Jahia/server-availability-manager.git", null)).isNull();
        assertThat(GitHosting.compareUrl("https://github.com/Jahia/server-availability-manager.git", null, "3_6_0")).isNull();
        assertThat(GitHosting.tagUrl("https://github.com/Jahia", "3_6_0")).isNull();
        assertThat(GitHosting.tagUrl("not a url", "3_6_0")).isNull();
    }
}
