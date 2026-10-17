package org.jahia.modules.forge.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;

import java.util.List;

/**
 * Write side of the Private App Store GraphQL surface, reached through the single
 * {@code forge} field on the root Mutation (see {@link ForgeMutationExtension}):
 * {@code mutation { forge { updateSettings(siteKey: "...", settings: {...}) { ... } } }}.
 *
 * <p>Grouping every store mutation under one namespace replaces the previous set of fields
 * (updateForgeSettings, setRootCategory, addForgeCategory, ...) that flattened straight onto
 * the global Mutation type. Each field here delegates to the focused logic holder for its
 * concern; the authorization and validation live there.
 */
@GraphQLName("ForgeMutation")
@GraphQLDescription("Private App Store mutations")
public class ForgeMutation {

    @GraphQLField
    @GraphQLName("updateSettings")
    @GraphQLDescription("Create or update the forge settings for a site")
    public GqlForgeSettings updateSettings(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("settings") @GraphQLNonNull @GraphQLDescription("Connection + branding fields") final ForgeSettingsInput settings) {
        return ForgeSettingsMutationExtension.updateForgeSettings(siteKey, settings);
    }

    @GraphQLField
    @GraphQLName("registerGitModule")
    @GraphQLDescription("Register a git-backed module: the repository is read first, and one that fails the STORE.md contract is refused with the reason")
    public GqlGitModule registerGitModule(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("gitUrl") @GraphQLNonNull @GraphQLDescription("The HTTP URL of the repository") final String gitUrl,
            @GraphQLName("tagPattern") @GraphQLDescription("The pattern a tag must match to be a version; the store default when absent") final String tagPattern,
            @GraphQLName("branch") @GraphQLDescription("The branch that holds the root STORE.md; the default branch when absent") final String branch) {
        return GitModuleMutationExtension.register(siteKey, gitUrl, tagPattern, branch);
    }

    @GraphQLField
    @GraphQLName("unregisterGitModule")
    @GraphQLDescription("Remove the registration of a git-backed module. The repository is untouched.")
    public Boolean unregisterGitModule(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("name") @GraphQLNonNull final String name) {
        return GitModuleMutationExtension.unregister(siteKey, name);
    }

    @GraphQLField
    @GraphQLName("refreshGitModule")
    @GraphQLDescription("Make the next read of a git-backed module refresh it from its repository")
    public Boolean refreshGitModule(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("name") @GraphQLNonNull final String name) {
        return GitModuleMutationExtension.refresh(siteKey, name);
    }

    @GraphQLField
    @GraphQLName("setRootCategory")
    @GraphQLDescription("Set the JCR node configured as the site's root category")
    public Boolean setRootCategory(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("rootCategoryUuid") @GraphQLNonNull final String rootCategoryUuid) {
        return CategorySettingsMutationExtension.setRootCategory(siteKey, rootCategoryUuid);
    }

    @GraphQLField
    @GraphQLName("addCategory")
    @GraphQLDescription("Create a jnt:category node under the site's root category, returning its UUID")
    public String addCategory(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("name") @GraphQLNonNull final String name) {
        return CategorySettingsMutationExtension.addForgeCategory(siteKey, name);
    }

    @GraphQLField
    @GraphQLName("updateCategoryTitles")
    @GraphQLDescription("Set per-language jcr:title on a category. Blank/null title removes the translation.")
    public Boolean updateCategoryTitles(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("uuid") @GraphQLNonNull final String uuid,
            @GraphQLName("titles") @GraphQLNonNull final List<InputCategoryTitle> titles) {
        return CategorySettingsMutationExtension.updateForgeCategoryTitles(siteKey, uuid, titles);
    }

    @GraphQLField
    @GraphQLName("deleteCategory")
    @GraphQLDescription("Delete a category node by UUID")
    public Boolean deleteCategory(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("uuid") @GraphQLNonNull final String uuid) {
        return CategorySettingsMutationExtension.deleteForgeCategory(siteKey, uuid);
    }

    @GraphQLField
    @GraphQLName("grantRole")
    @GraphQLDescription("Grant a role to a principal on the site node")
    public Boolean grantRole(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("role") @GraphQLNonNull final String role,
            @GraphQLName("principalName") @GraphQLNonNull final String principalName,
            @GraphQLName("principalType") @GraphQLNonNull final PrincipalType principalType) {
        return ManageRolesMutationExtension.grantSiteRole(siteKey, role, principalName, principalType);
    }

    @GraphQLField
    @GraphQLName("revokeRole")
    @GraphQLDescription("Revoke a single role from a principal on the site node. Leaves other roles untouched.")
    public Boolean revokeRole(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("role") @GraphQLNonNull final String role,
            @GraphQLName("principalName") @GraphQLNonNull final String principalName,
            @GraphQLName("principalType") @GraphQLNonNull final PrincipalType principalType) {
        return ManageRolesMutationExtension.revokeSiteRole(siteKey, role, principalName, principalType);
    }
}
