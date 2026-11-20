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
import org.apache.commons.lang.StringUtils;

/**
 * The pages a git host serves for a tag and for the changes between two tags, which the store
 * links from a version when its {@code VERSION.md} names a tag and no URL. GitHub is the one host
 * known; a repository elsewhere names its URLs in the version file.
 */
final class GitHosting {

    private static final String GITHUB_HOST = "github.com";

    private GitHosting() {
    }

    /** The page of a tag, or null when the host is not known. */
    static String tagUrl(String repositoryUrl, String tag) {
        String project = githubProject(repositoryUrl);
        return project == null || StringUtils.isBlank(tag) ? null : project + "/releases/tag/" + tag;
    }

    /** The changes between two tags, or null when the host is not known or a tag is missing. */
    static String compareUrl(String repositoryUrl, String fromTag, String toTag) {
        String project = githubProject(repositoryUrl);
        return project == null || StringUtils.isBlank(fromTag) || StringUtils.isBlank(toTag) ? null
                : project + "/compare/" + fromTag + "..." + toTag;
    }

    /** The {@code https://github.com/<owner>/<repo>} of a GitHub repository URL, or null for another host. */
    static String githubProject(String repositoryUrl) {
        if (repositoryUrl == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(repositoryUrl.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        if (uri.getHost() == null || !GITHUB_HOST.equalsIgnoreCase(uri.getHost())) {
            return null;
        }
        String path = StringUtils.removeEnd(StringUtils.removeEnd(StringUtils.defaultString(uri.getPath()), "/"), ".git");
        String[] segments = path.split("/");
        if (segments.length < 3 || segments[1].isEmpty() || segments[2].isEmpty()) {
            return null;
        }
        return "https://" + GITHUB_HOST + "/" + segments[1] + "/" + segments[2];
    }
}
