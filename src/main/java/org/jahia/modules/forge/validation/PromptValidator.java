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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

/**
 * A check that a human runs with an assistant: the check asks for review with a prompt, which the
 * store administration shows beside a box for the answer. The prompt is a resource of the module
 * with placeholders for the version: {@code ${module}}, {@code ${version}}, {@code ${groupId}},
 * {@code ${artifactUrl}} and {@code ${sourceUrl}}.
 */
abstract class PromptValidator implements ModuleValidator {

    private final String promptResource;

    PromptValidator(String promptResource) {
        this.promptResource = promptResource;
    }

    @Override
    public ValidationResult validate(ModuleArtifact artifact) {
        return ValidationResult.review(prompt(artifact));
    }

    /** The prompt of a version, with its placeholders filled. */
    String prompt(ModuleArtifact artifact) {
        String template;
        try (InputStream in = getClass().getResourceAsStream(promptResource)) {
            if (in == null) {
                throw new IllegalStateException("The prompt " + promptResource + " is missing from the module");
            }
            template = IOUtils.toString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("The prompt " + promptResource + " cannot be read", e);
        }
        return template
                .replace("${module}", StringUtils.defaultString(artifact.getModuleName()))
                .replace("${version}", StringUtils.defaultString(artifact.getVersion()))
                .replace("${groupId}", StringUtils.defaultString(artifact.getGroupId()))
                .replace("${artifactUrl}", StringUtils.defaultString(artifact.getArtifactUrl()))
                .replace("${sourceUrl}", StringUtils.defaultIfBlank(artifact.getSourceUrl(), "(no repository declared)"));
    }
}
