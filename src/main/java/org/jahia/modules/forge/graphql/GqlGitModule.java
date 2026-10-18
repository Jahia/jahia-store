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
package org.jahia.modules.forge.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

/** The registration of a git-backed module, as the GraphQL API shows it. */
@GraphQLName("GitModule")
@GraphQLDescription("A module whose description, versions and artefacts a git repository holds")
public class GqlGitModule {

    private final String name;
    private final String gitUrl;
    private final String tagPattern;
    private final String branch;
    private final String registeredBy;
    private final String path;
    private final String mountStatus;

    public GqlGitModule(String name, String gitUrl, String tagPattern, String branch, String registeredBy, String path, String mountStatus) {
        this.name = name;
        this.gitUrl = gitUrl;
        this.tagPattern = tagPattern;
        this.branch = branch;
        this.registeredBy = registeredBy;
        this.path = path;
        this.mountStatus = mountStatus;
    }

    @GraphQLField
    @GraphQLDescription("The module name, which is the node name under modules-repository")
    public String getName() {
        return name;
    }

    @GraphQLField
    @GraphQLDescription("The HTTP URL of the repository")
    public String getGitUrl() {
        return gitUrl;
    }

    @GraphQLField
    @GraphQLDescription("The pattern a tag must match to be a version, or null for the store default")
    public String getTagPattern() {
        return tagPattern;
    }

    @GraphQLField
    @GraphQLDescription("The branch that holds the root STORE.md, or null for the default branch")
    public String getBranch() {
        return branch;
    }

    @GraphQLField
    @GraphQLDescription("The user the module is registered for, who the module reads as its creator")
    public String getRegisteredBy() {
        return registeredBy;
    }

    @GraphQLField
    @GraphQLDescription("The JCR path the module is served at")
    public String getPath() {
        return path;
    }

    @GraphQLField
    @GraphQLDescription("The mount status Jahia reports: mounted, unmounted, waiting or error")
    public String getMountStatus() {
        return mountStatus;
    }
}
