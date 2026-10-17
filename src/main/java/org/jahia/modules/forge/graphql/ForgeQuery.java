package org.jahia.modules.forge.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;

import java.util.List;

/**
 * Read side of the Private App Store GraphQL surface, reached through the single
 * {@code forge} field on the root Query (see {@link ForgeQueryExtension}):
 * {@code query { forge { settings(siteKey: "...") { ... } } }}.
 *
 * <p>Grouping every store query under one namespace replaces the previous set of fields
 * (forgeSettings, forgeCategorySettings, manageRolesSettings, searchForgePrincipals) that
 * flattened straight onto the global Query type. Each field here delegates to the focused
 * logic holder for its concern; the authorization and validation live there.
 */
@GraphQLName("ForgeQuery")
@GraphQLDescription("Private App Store queries")
public class ForgeQuery {

    @GraphQLField
    @GraphQLName("settings")
    @GraphQLDescription("Read the forge settings for a site")
    public GqlForgeSettings settings(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey) {
        return ForgeSettingsQueryExtension.getForgeSettings(siteKey);
    }

    @GraphQLField
    @GraphQLName("categorySettings")
    @GraphQLDescription("Read the forge category settings for a site")
    public GqlForgeCategorySettings categorySettings(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey) {
        return CategorySettingsQueryExtension.getForgeCategorySettings(siteKey);
    }

    @GraphQLField
    @GraphQLName("manageRolesSettings")
    @GraphQLDescription("Read the role-management settings for a site")
    public GqlManageRolesSettings manageRolesSettings(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey) {
        return ManageRolesQueryExtension.getManageRolesSettings(siteKey);
    }

    @GraphQLField
    @GraphQLName("searchPrincipals")
    @GraphQLDescription("Find users or groups whose name contains the given term (case-insensitive)")
    public List<GqlPrincipal> searchPrincipals(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("searchTerm") @GraphQLNonNull final String searchTerm,
            @GraphQLName("type") @GraphQLNonNull final PrincipalType type) {
        return ManageRolesQueryExtension.searchForgePrincipals(siteKey, searchTerm, type);
    }

    @GraphQLField
    @GraphQLName("gitModules")
    @GraphQLDescription("The git-backed modules registered on a site")
    public List<GqlGitModule> gitModules(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey) {
        return GitModuleMutationExtension.list(siteKey);
    }
}
