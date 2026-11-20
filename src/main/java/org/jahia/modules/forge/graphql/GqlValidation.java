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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.jackrabbit.util.ISO8601;
import java.util.Calendar;
import org.jahia.modules.forge.validation.ValidationRecord;

/** The validation of a version of a module, as the GraphQL API shows it (docs/validation.md). */
@GraphQLName("ModuleValidation")
@GraphQLDescription("The validation of a version of a module: its status and one check per registered validator")
public class GqlValidation {

    /** One check of the version. */
    @GraphQLName("ModuleValidationCheck")
    @GraphQLDescription("One check of a version: what it means, what it says, and the prompt a human follows when it waits for one")
    public static class Check {
        private final ValidationRecord.Check check;

        Check(ValidationRecord.Check check) {
            this.check = check;
        }

        @GraphQLField
        @GraphQLDescription("The key of the check, stable across releases")
        public String getKey() {
            return check.getKey();
        }

        @GraphQLField
        @GraphQLDescription("What the check means to a developer, such as Code quality")
        public String getLabel() {
            return check.getLabel();
        }

        @GraphQLField
        @GraphQLDescription("pending, approved, review or rejected")
        public String getStatus() {
            return check.getStatus() == null ? null : check.getStatus().key();
        }

        @GraphQLField
        public List<String> getComments() {
            return check.getComments();
        }

        @GraphQLField
        @GraphQLDescription("The prompt a human follows to answer the check, when it waits for one")
        public String getPrompt() {
            return check.getPrompt();
        }

        @GraphQLField
        public String getUpdatedBy() {
            return check.getUpdatedBy();
        }

        @GraphQLField
        public String getUpdatedAt() {
            return iso(check.getUpdatedAt());
        }
    }

    private final ValidationRecord record;

    public GqlValidation(ValidationRecord record) {
        this.record = record;
    }

    @GraphQLField
    public String getModuleName() {
        return record.getModuleName();
    }

    @GraphQLField
    public String getVersion() {
        return record.getVersion();
    }

    @GraphQLField
    @GraphQLDescription("pending, approved, review or rejected")
    public String getStatus() {
        return record.getStatus() == null ? null : record.getStatus().key();
    }

    @GraphQLField
    @GraphQLDescription("Where the archive of the version is")
    public String getArtifactUrl() {
        return record.getArtifactUrl();
    }

    @GraphQLField
    public String getRequestedBy() {
        return record.getRequestedBy();
    }

    @GraphQLField
    public String getRequestedAt() {
        return iso(record.getRequestedAt());
    }

    @GraphQLField
    @GraphQLDescription("The reason an administrator gave when invalidating the version")
    public String getReason() {
        return record.getReason();
    }

    @GraphQLField
    public List<Check> getChecks() {
        List<Check> checks = new ArrayList<>();
        for (ValidationRecord.Check check : record.getChecks()) {
            checks.add(new Check(check));
        }
        return checks;
    }

    static String iso(Date date) {
        if (date == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return ISO8601.format(calendar);
    }
}
