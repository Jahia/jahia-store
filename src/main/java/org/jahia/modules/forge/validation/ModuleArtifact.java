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
package org.jahia.modules.forge.validation;

/** The version of a module a check validates: its coordinates and where its archive is. */
public final class ModuleArtifact {

    private final String siteKey;
    private final String moduleName;
    private final String version;
    private final String groupId;
    private final String artifactUrl;
    private final String sourceUrl;

    /**
     * @param artifactUrl where the archive of the version is: an absolute URL, or a path of the
     *                    store such as its Maven proxy
     * @param sourceUrl   the repository of the module, or null
     */
    public ModuleArtifact(String siteKey, String moduleName, String version, String groupId, String artifactUrl, String sourceUrl) {
        this.siteKey = siteKey;
        this.moduleName = moduleName;
        this.version = version;
        this.groupId = groupId;
        this.artifactUrl = artifactUrl;
        this.sourceUrl = sourceUrl;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getVersion() {
        return version;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactUrl() {
        return artifactUrl;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }
}
