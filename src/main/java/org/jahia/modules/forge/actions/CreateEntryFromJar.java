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
 *     Copyright (C) 2002-2020 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms & Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.actions;

import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jahia.api.Constants;
import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.commons.Version;
import org.jahia.data.templates.ModuleReleaseInfo;
import org.jahia.data.templates.ModulesPackage;
import org.jahia.modules.forge.settings.ForgeSettings;
import org.jahia.modules.forge.settings.ForgeSettingsService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.jahia.taglibs.jcr.node.JCRTagUtils;
import org.jahia.tools.files.FileUpload;
import org.jahia.utils.PomUtils;
import org.jahia.utils.ProcessHelper;
import org.jahia.utils.i18n.Messages;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import org.apache.jackrabbit.core.fs.FileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;

/**
 * Action to par a jar and produce an entry in module list
 */
@Component(service = Action.class)
public class CreateEntryFromJar extends Action {

    private static final Logger logger = LoggerFactory.getLogger(CreateEntryFromJar.class);
    private static final String DEFAULT_MAVEN_EXECUTABLE = "mvn";
    private static final String[] EMPTY_REFERENCES = new String[]{"none"};
    private static final String JNT_FORGEMODULEVERSION = "jnt:forgeModuleVersion";
    private static final String JNT_FORGEPACKAGEVERSION = "jnt:forgePackageVersion";
    private static final String JNT_CONTENT_FOLDER = "jnt:contentFolder";
    private static final String REDIRECT_URL = "redirectURL";
    private static final String BUNDLE_SYMBOLIC_NAME = "Bundle-SymbolicName";
    private static final String REQUIRED_VERSION = "requiredVersion";
    private static final String VERSION_NUMBER = "versionNumber";
    private static final String VERSION_PREFIX = "version-";
    private static final String DESCRIPTION = "description";
    private static final String AUTHOR_URL = "authorURL";
    private static final String ERROR = "error";
    private static final String PACKAGES = "packages";
    private static final String MODULES_LIST = "modulesList";
    private static final String RESOURCES_JAHIA_STORE = "resources.jahia-store";
    private static final String GROUP_ID = "groupId";
    private static final String MODULE_NAME = "moduleName";
    private static final String CATEGORY = "category";
    private static final String AUTHOR_NAME_DISPLAYED_AS = "authorNameDisplayedAs";
    private static final String AUTHOR_EMAIL = "authorEmail";
    private static final String CODE_REPOSITORY = "codeRepository";
    private static final String DOWNLOAD_COUNT = "downloadCount";
    private static final String SUPPORTED_BY_JAHIA = "supportedByJahia";
    private static final String REVIEWED_BY_JAHIA = "reviewedByJahia";
    private static final String PUBLISHED = "published";
    private static final String DELETED = "deleted";
    private static final String SCREENSHOTS = "screenshots";
    private static final String VIDEO = "video";
    private static final String CHANGE_LOG = "changeLog";
    /** Never add to *_PARAM_KEYS: server clock only, so an upload cannot forge its release date. */
    private static final String UPLOAD_DATE = "uploadDate";
    private static final String FILE_DSA_SIGNATURE = "fileDsaSignature";
    private static final String REFERENCES = "references";
    private static final String SUCCESS_REDIRECT_URL = "successRedirectUrl";
    private static final String SUCCESS_REDIRECT_ABSOLUTE_URL = "successRedirectAbsoluteUrl";
    private static final String ERR_UNABLE_READ_FILE = "forge.uploadJar.error.unable.read.file";
    private static final String ERR_FILE_TOO_LARGE = "forge.uploadJar.error.file.too.large";
    private static final String ERR_MISSING_MANIFEST_ATTRIBUTE = "forge.uploadJar.error.missing.manifest.attribute";
    private static final String ERR_VERSION_NUMBER = "forge.uploadJar.error.versionNumber";
    private static final String LOG_UNSAFE_REDIRECT = "CreateEntryFromJar: rejected unsafe redirectURL '{}'";
    /** Allowed upload extensions. Downstream code only ever sees these constants, never user input. */
    private static final String EXTENSION_JAR = "jar";
    private static final String EXTENSION_TGZ = "tgz";
    /** Maximum accepted size for an uploaded artifact (guards against resource-exhaustion uploads). */
    private static final long MAX_UPLOAD_SIZE_BYTES = 200L * 1024 * 1024;
    /** Maximum size of a single tar entry we read into memory (guards against decompression bombs). */
    private static final long MAX_TAR_ENTRY_SIZE_BYTES = 10L * 1024 * 1024;
    /** A safe Maven coordinate token: letters, digits, dot, underscore, hyphen only — no shell/expression metacharacters. */
    private static final Pattern SAFE_MAVEN_COORD = Pattern.compile("[A-Za-z0-9._-]+");
    /** Form params routed to the module node (the rest go to the version node). */
    private static final List<String> MODULE_PARAM_KEYS = Arrays.asList(DESCRIPTION, CATEGORY, "icon",
            AUTHOR_NAME_DISPLAYED_AS, AUTHOR_URL, AUTHOR_EMAIL, "FAQ", CODE_REPOSITORY, DOWNLOAD_COUNT,
            SUPPORTED_BY_JAHIA, REVIEWED_BY_JAHIA, PUBLISHED, DELETED, SCREENSHOTS, VIDEO, GROUP_ID);
    /** Form params routed to the version node. */
    private static final List<String> VERSION_PARAM_KEYS =
            Arrays.asList(REQUIRED_VERSION, VERSION_NUMBER, FILE_DSA_SIGNATURE, CHANGE_LOG);
    /** Form params routed to a package node (no groupId / code-repository, unlike a module). */
    private static final List<String> PACKAGE_PARAM_KEYS = Arrays.asList(DESCRIPTION, CATEGORY, "icon",
            AUTHOR_NAME_DISPLAYED_AS, AUTHOR_URL, AUTHOR_EMAIL, "FAQ", DOWNLOAD_COUNT, SUPPORTED_BY_JAHIA,
            REVIEWED_BY_JAHIA, PUBLISHED, DELETED, SCREENSHOTS, VIDEO);

    private volatile String mavenExecutable;

    @Reference
    private ForgeSettingsService forgeSettingsService;

    @Activate
    public void activate() {
        setName("createEntryFromJar");
        setRequireAuthenticatedUser(true);
        setRequiredPermission("jahiaForgeUploadModule");
        setRequiredMethods("POST");
        if (mavenExecutable == null) {
            String configured = System.getProperty("mvnPath", DEFAULT_MAVEN_EXECUTABLE);
            setMavenExecutable(configured);
        }
    }

    @Override
    public ActionResult doExecute(HttpServletRequest request, RenderContext renderContext, Resource resource, JCRSessionWrapper session, Map<String, List<String>> parameters, URLResolver urlResolver) throws Exception {
        final FileUpload fu = (FileUpload) request.getAttribute(FileUpload.FILEUPLOAD_ATTRIBUTE);

        DiskFileItem uploadedFile = fu.getFileItems().get("file");
        if (uploadedFile == null) {
            return errorResult(session, ERR_UNABLE_READ_FILE);
        }
        if (uploadedFile.getSize() > MAX_UPLOAD_SIZE_BYTES) {
            if (logger.isWarnEnabled()) {
                logger.warn("CreateEntryFromJar: rejected oversized upload '{}' ({} bytes)",
                        ActionSecurityUtils.sanitizeForLog(uploadedFile.getName()), uploadedFile.getSize());
            }
            uploadedFile.delete();
            return errorResult(session, ERR_FILE_TOO_LARGE);
        }
        String filename = uploadedFile.getName();
        if (StringUtils.contains(filename, "SNAPSHOT.")) {
            return errorResult(session, "forge.uploadJar.error.snapshot.not.allowed");
        }
        String extension = trustedExtension(filename);
        if (extension == null) {
            return errorResult(session, "forge.uploadJar.error.wrong.format");
        }
        Map<String, List<String>> formParameters = fu.getParameterMap();
        final UploadContext ctx = new UploadContext(uploadedFile, request, renderContext, resource, session, formParameters);
        // The temp upload is always cleaned up here, and any archive parse error maps to a single
        // read-file error - owned by the entry point so neither handler repeats the wrapper.
        try {
            if (EXTENSION_TGZ.equals(extension)) {
                return handleTgzUpload(ctx);
            }
            return handleJarUpload(ctx, extension);
        } catch (IOException ex) {
            logger.error("Impossible to parse archive", ex);
            return errorResult(ctx.session, ERR_UNABLE_READ_FILE);
        } finally {
            ctx.uploadedFile.delete();
        }
    }

    private ActionResult handleTgzUpload(UploadContext ctx) throws IOException, RepositoryException, JSONException {
        try (InputStream fileInputStream = new FileInputStream(ctx.uploadedFile.getStoreLocation());
             InputStream gzipInputStream = new GZIPInputStream(fileInputStream);
             TarArchiveInputStream tarInputStream = new TarArchiveInputStream(gzipInputStream)) {

            TarArchiveEntry entry;
            while ((entry = tarInputStream.getNextEntry()) != null) {
                if (!entry.isFile() || !"package/package.json".equals(entry.getName())) {
                    continue;
                }
                if (entry.getSize() > MAX_TAR_ENTRY_SIZE_BYTES) {
                    logger.warn("CreateEntryFromJar: package.json entry too large ({} bytes) - rejected", entry.getSize());
                    return errorResult(ctx.session, ERR_UNABLE_READ_FILE);
                }
                final JSONObject jsonObject = new JSONObject(IOUtils.toString(tarInputStream, StandardCharsets.UTF_8));
                return createJavascriptModule(ctx, jsonObject);
            }
            return errorResult(ctx.session, "forge.uploadJar.error.unable.read.manifest");
        }
    }

    private ActionResult handleJarUpload(UploadContext ctx, String extension) throws IOException, RepositoryException, JSONException {
        try (JarFile jar = new JarFile(ctx.uploadedFile.getStoreLocation())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return errorResult(ctx.session, "forge.uploadJar.error.unable.read.manifest");
            }
            Attributes attributes = manifest.getMainAttributes();
            if (attributes.getValue("Jahia-Package-Name") != null) {
                return createPackage(ctx, jar, attributes);
            }
            return createModule(ctx, attributes, extension);
        }
    }

    private ActionResult createJavascriptModule(UploadContext ctx, JSONObject jsonObject)
            throws RepositoryException, JSONException, IOException {
        final String version = jsonObject.getString("version");
        final String moduleName = jsonObject.getString("name");
        final JSONObject jahiaJsonObject = jsonObject.getJSONObject("jahia");
        final String groupId = jahiaJsonObject.getJSONObject("maven").getString(GROUP_ID);
        final Map<String, List<String>> moduleParams = new HashMap<>();
        moduleParams.put(MODULE_NAME, Arrays.asList(moduleName));
        moduleParams.put(GROUP_ID, Arrays.asList(groupId));
        moduleParams.put(Constants.JCR_TITLE, Arrays.asList(moduleName));
        moduleParams.put(VERSION_NUMBER, Arrays.asList(version));
        // JS modules attach the package to the version node (no Maven deploy).
        return createForgeModule(ctx, moduleParams, jahiaJsonObject.getString("required-version"),
                jahiaJsonObject.getString("module-dependencies"), false, null);
    }

    /**
     * Shared module-creation core for both upload paths (JAR via {@link #createModule}, JS via
     * {@link #createJavascriptModule}). The caller pre-populates {@code moduleParams} from its
     * source (MANIFEST / package.json). Differences captured by the flags: JAR modules deploy the
     * artifact to the site's Maven repo before node creation ({@code deployJar=true}, {@code
     * extension} set); JS modules attach the package file to the version node afterwards. Behaviour
     * mirrors the two former methods (validation set, ordering, save points) - extracted to remove
     * the duplicated body.
     */
    private ActionResult createForgeModule(UploadContext ctx, Map<String, List<String>> moduleParams,
                                           String reqVersionAttribute, String dependencies, boolean deployJar,
                                           String extension) throws RepositoryException, JSONException, IOException {
        final String moduleName = getParameter(moduleParams, MODULE_NAME);
        final String groupId = getParameter(moduleParams, GROUP_ID);
        final String version = getParameter(moduleParams, VERSION_NUMBER);
        final String requiredVersion = VERSION_PREFIX + reqVersionAttribute;
        if (StringUtils.isEmpty(moduleName) || StringUtils.isEmpty(groupId)
                || StringUtils.isEmpty(reqVersionAttribute) || !isSafeCoordinate(groupId, moduleName)
                || !isSafeRequiredVersion(reqVersionAttribute)) {
            return errorResult(ctx.session, ERR_MISSING_MANIFEST_ATTRIBUTE);
        }
        final JCRNodeWrapper repository = ctx.resource.getNode();
        final JCRNodeWrapper versions = getJahiaVersion(requiredVersion, ctx.resource, ctx.session);
        moduleParams.put(REQUIRED_VERSION, Arrays.asList(versions.getNode(requiredVersion).getIdentifier()));

        if (deployJar) {
            final ActionResult deployFailure = deployArtifact(ctx.uploadedFile, repository.getResolveSite(),
                    extension, groupId, moduleName, ctx.session);
            if (deployFailure != null) {
                return deployFailure;
            }
        }

        final Map<String, List<String>> moduleParameters = new HashMap<>();
        final Map<String, List<String>> versionParameters = new HashMap<>();
        final String title = populateParameterMaps(moduleParams, moduleName, MODULE_PARAM_KEYS, moduleParameters, versionParameters);

        // Sanitize archive-supplied coordinates before logging: moduleName/version/title pass the
        // path-fragment guard (no ../\/) but may still carry CR/LF, which would forge log lines
        // (CWE-117). Mirrors the already-sanitized error-path sinks (SECURITY-571 #56).
        logger.info("Start creating Private App Store Module {}", ActionSecurityUtils.sanitizeForLog(moduleName));
        logger.info("Start adding module version {} of {}",
                ActionSecurityUtils.sanitizeForLog(version), ActionSecurityUtils.sanitizeForLog(title));
        final ModulePrep prep = prepareModuleVersion(ctx.request, repository, groupId, moduleName, moduleParameters, version, ctx.ownerGrants);
        if (prep.conflict != null) {
            return prep.conflict;
        }
        final JCRNodeWrapper module = prep.module;
        final JCRNodeWrapper moduleVersion = createModuleVersion(ctx.request, module, versionParameters, version, dependencies, ctx.ownerGrants);

        logger.info("Module version {} of {} successfully added",
                ActionSecurityUtils.sanitizeForLog(version), ActionSecurityUtils.sanitizeForLog(title));
        logger.info("Private App Store Module {} successfully created and added to repository {}",
                ActionSecurityUtils.sanitizeForLog(moduleName), module.getParent().getPath());

        ctx.session.save();
        // Nodes are durable from here, so the deferred owner grants can be applied by a system
        // session (SEC-366 - the uploader's own role no longer carries live ACL authority).
        ctx.ownerGrants.flush();
        if (!deployJar) {
            moduleVersion.uploadFile(ctx.uploadedFile.getName(), ctx.uploadedFile.getInputStream(), ctx.uploadedFile.getContentType());
            ctx.session.save();
        }
        return buildUploadResult(ctx.request, ctx.renderContext, module, ctx.formParams);
    }

    /**
     * Immutable bundle of the request-scoped arguments shared by both upload paths, so the shared
     * {@link #createForgeModule} core stays within a sane parameter count. The module coordinates
     * (name / groupId / version) are read back from the populated {@code moduleParams} map.
     */
    private static final class UploadContext {
        private final DiskFileItem uploadedFile;
        private final HttpServletRequest request;
        private final RenderContext renderContext;
        private final Resource resource;
        private final JCRSessionWrapper session;
        private final Map<String, List<String>> formParams;
        /**
         * Collects the owner-role grants this upload owes, so they can be applied with elevated
         * rights once the caller's session is saved (SEC-366). Scoped to the repository node the
         * action was invoked on — the node the Action framework already gated with
         * {@code jahiaForgeUploadModule}.
         */
        private final OwnerRoleGrants ownerGrants;

        private UploadContext(DiskFileItem uploadedFile, HttpServletRequest request, RenderContext renderContext,
                              Resource resource, JCRSessionWrapper session, Map<String, List<String>> formParams) {
            this.uploadedFile = uploadedFile;
            this.request = request;
            this.renderContext = renderContext;
            this.resource = resource;
            this.session = session;
            this.formParams = formParams;
            this.ownerGrants = new OwnerRoleGrants(session, resource.getNode());
        }
    }

    private ActionResult createPackage(UploadContext ctx, JarFile jar, Attributes attributes)
            throws RepositoryException, JSONException, IOException {
        final JCRNodeWrapper repository = ctx.resource.getNode();

        String packageName = attributes.getValue("Jahia-Package-ID");
        String packageRelPath = "packages/" + packageName;
        String version = attributes.getValue("Jahia-Package-Version");

        Map<String, List<String>> packageParams = new HashMap<>();

        packageParams.put("packageName", Arrays.asList(packageName));
        packageParams.put(Constants.JCR_TITLE, Arrays.asList(attributes.getValue("Jahia-Package-Name")));
        packageParams.put(DESCRIPTION, Arrays.asList(attributes.getValue("Jahia-Package-Description")));
        packageParams.put(VERSION_NUMBER, Arrays.asList(version));

        String reqVersionAttribute = attributes.getValue("Jahia-Required-Version");
        final String requiredVersion = VERSION_PREFIX + reqVersionAttribute;
        if (StringUtils.isEmpty(packageName) || StringUtils.isEmpty(reqVersionAttribute) || StringUtils.isEmpty(version)
                || !isSafePackageName(packageName) || !isSafeRequiredVersion(reqVersionAttribute)) {
            return errorResult(ctx.session, ERR_MISSING_MANIFEST_ATTRIBUTE);
        }
        JCRNodeWrapper versions = getJahiaVersion(requiredVersion, ctx.resource, ctx.session);
        packageParams.put(REQUIRED_VERSION, Arrays.asList(versions.getNode(requiredVersion).getIdentifier()));

        Map<String, List<String>> packageParameters = new HashMap<>();
        Map<String, List<String>> versionParameters = new HashMap<>();
        String title = populateParameterMaps(packageParams, packageName, PACKAGE_PARAM_KEYS, packageParameters, versionParameters);

        logger.info("Start creating Private App Store Package {}", ActionSecurityUtils.sanitizeForLog(packageName));

        final JCRNodeWrapper modulesPackage = upsertPackageNode(ctx.request, repository, packageRelPath, packageName, packageParameters);
        ctx.ownerGrants.recordOwner(modulesPackage);

        boolean hasPackageVersions = JCRTagUtils.hasChildrenOfType(modulesPackage, JNT_FORGEPACKAGEVERSION);
        logger.info("Start adding package version {} of {}",
                ActionSecurityUtils.sanitizeForLog(version), ActionSecurityUtils.sanitizeForLog(title));

        if (hasPackageVersions && !hasValidVersionNumber(modulesPackage, version)) {
            String error = Messages.getWithArgs(RESOURCES_JAHIA_STORE, ERR_VERSION_NUMBER, ctx.session.getLocale(), packageName, version);
            return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject().put(ERROR, error));
        }

        JCRNodeWrapper packageVersion = createNode(ctx.request, versionParameters, modulesPackage, JNT_FORGEPACKAGEVERSION, modulesPackage.getName() + "-" + version, false);
        stampUploadDate(packageVersion);
        ctx.ownerGrants.recordOwner(packageVersion);
        packageVersion.uploadFile(ctx.uploadedFile.getName(), ctx.uploadedFile.getInputStream(), ctx.uploadedFile.getContentType());

        logger.info("Package version {} of {} successfully added",
                ActionSecurityUtils.sanitizeForLog(version), ActionSecurityUtils.sanitizeForLog(title));

        populatePackageModulesList(packageVersion, jar);

        logger.info("Private App Store Package {} successfully created and added to repository {}",
                ActionSecurityUtils.sanitizeForLog(packageName), modulesPackage.getParent().getPath());

        ctx.session.save();
        // See createForgeModule: grants are applied only once the nodes exist for another session.
        ctx.ownerGrants.flush();
        return buildUploadResult(ctx.request, ctx.renderContext, modulesPackage, ctx.formParams);
    }

    private JCRNodeWrapper upsertPackageNode(HttpServletRequest request, JCRNodeWrapper repositoryStart,
                                             String packageRelPath, String packageName,
                                             Map<String, List<String>> packageParameters) throws RepositoryException {
        if (!repositoryStart.hasNode(packageRelPath)) {
            JCRNodeWrapper packagesFolder = repositoryStart.hasNode(PACKAGES)
                    ? repositoryStart.getNode(PACKAGES)
                    : repositoryStart.addNode(PACKAGES, JNT_CONTENT_FOLDER);
            return createNode(request, packageParameters, packagesFolder, "jnt:forgePackage", packageName, false);
        }
        JCRNodeWrapper modulesPackage = repositoryStart.getNode(packageRelPath);
        packageParameters.remove(DESCRIPTION);
        packageParameters.remove(Constants.JCR_TITLE);
        setProperties(modulesPackage, packageParameters);
        return modulesPackage;
    }

    private static void populatePackageModulesList(JCRNodeWrapper packageVersion, JarFile jar) throws RepositoryException, IOException {
        JCRNodeWrapper modulesList = packageVersion.hasNode(MODULES_LIST)
                ? packageVersion.getNode(MODULES_LIST)
                : packageVersion.addNode(MODULES_LIST, "jnt:forgePackageModulesList");

        Map<String, ModulesPackage.PackagedModule> packagedModuleMap = ModulesPackage.create(jar).getModules();
        for (ModulesPackage.PackagedModule packagedModule : packagedModuleMap.values()) {
            String symbolicName = packagedModule.getManifestAttributes().getValue(BUNDLE_SYMBOLIC_NAME);
            JCRNodeWrapper module = modulesList.hasNode(symbolicName)
                    ? modulesList.getNode(symbolicName)
                    : modulesList.addNode(symbolicName, "jnt:forgePackageModule");
            module.setProperty(MODULE_NAME, packagedModule.getManifestAttributes().getValue("Implementation-Title"));
            module.setProperty("moduleVersion", packagedModule.getManifestAttributes().getValue("Implementation-Version"));
        }
    }

    private ActionResult createModule(UploadContext ctx, Attributes attributes, String extension)
            throws RepositoryException, JSONException, IOException {
        final String version = attributes.getValue("Implementation-Version");
        final String moduleName = attributes.getValue(BUNDLE_SYMBOLIC_NAME);
        final String groupId = attributes.getValue("Jahia-GroupId");
        final Map<String, List<String>> moduleParams = new HashMap<>();
        moduleParams.put(MODULE_NAME, Arrays.asList(moduleName));
        moduleParams.put(GROUP_ID, Arrays.asList(groupId));
        moduleParams.put(Constants.JCR_TITLE, Arrays.asList(attributes.getValue("Implementation-Title")));
        moduleParams.put(DESCRIPTION, Arrays.asList(attributes.getValue("Bundle-Description")));
        moduleParams.put(AUTHOR_URL, Arrays.asList(attributes.getValue("Implementation-URL")));
        moduleParams.put(CODE_REPOSITORY, Arrays.asList(attributes.getValue("Jahia-Source-Control-Connection")));
        moduleParams.put(VERSION_NUMBER, Arrays.asList(version));
        // JAR modules deploy the artifact to the site's Maven repo before node creation.
        return createForgeModule(ctx, moduleParams, attributes.getValue("Jahia-Required-Version"),
                attributes.getValue("Jahia-Depends"), true, extension);
    }

    private ActionResult deployArtifact(DiskFileItem uploadedFile, JCRSiteNode site, String extension,
                                        String groupId, String moduleName,
                                        JCRSessionWrapper session) throws JSONException {
        // Connection settings now live in per-site OSGi config (see ForgeSettingsService).
        final ForgeSettings settings = forgeSettingsService.get(site.getSiteKey());

        File artifact = null;
        try {
            artifact = File.createTempFile("artifact", "." + extension);
            FileUtils.copyFile(uploadedFile.getStoreLocation(), artifact);

            ModuleReleaseInfo moduleReleaseInfo = new ModuleReleaseInfo();
            moduleReleaseInfo.setRepositoryId("remote-repository");
            moduleReleaseInfo.setRepositoryUrl(settings.getUrl());
            moduleReleaseInfo.setUsername(settings.getUser());
            moduleReleaseInfo.setPassword(settings.getPassword());
            deployToMaven(groupId, moduleName, moduleReleaseInfo, artifact);
            return null;
        } catch (IOException e) {
            // A release repository (e.g. Nexus hosted "release") refuses to overwrite an existing
            // version -> surface an actionable "bump the version" message instead of the raw Maven
            // failure. Any other deploy error stays generic (the detail is in the server log).
            final String key = isVersionAlreadyDeployed(e.getMessage())
                    ? "forge.uploadJar.error.version.already.deployed"
                    : "forge.uploadJar.error.cannot.upload";
            return errorResult(session, key);
        } finally {
            FileUtils.deleteQuietly(artifact);
        }
    }

    /**
     * True when a Maven deploy failed because the version already exists in a release repository:
     * Nexus answers HTTP 400 "... cannot be updated" (redeploy disabled on release repos). Matched
     * on that phrase, which {@link #getMavenError} keeps from the {@code [ERROR]} lines.
     */
    static boolean isVersionAlreadyDeployed(String mavenError) {
        return mavenError != null && StringUtils.containsIgnoreCase(mavenError, "cannot be updated");
    }

    /**
     * Allow-lists the uploaded file extension and returns the matching {@code EXTENSION_*} constant,
     * or {@code null} when the extension is not supported. The returned value is always a constant —
     * never the user-derived string — so the suffix later embedded in the temp-artifact path
     * ({@code File.createTempFile}) is provably untainted (CodeQL java/path-injection).
     */
    // package-private for unit testing
    static String trustedExtension(String filename) {
        String extension = StringUtils.substringAfterLast(filename, ".");
        if (StringUtils.equals(extension, EXTENSION_JAR)) {
            return EXTENSION_JAR;
        }
        if (StringUtils.equals(extension, EXTENSION_TGZ)) {
            return EXTENSION_TGZ;
        }
        return null;
    }

    private static ActionResult errorResult(JCRSessionWrapper session, String messageKey) throws JSONException {
        String error = Messages.get(RESOURCES_JAHIA_STORE, messageKey, session.getLocale());
        return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject().put(ERROR, error));
    }

    private static void applySafeRedirect(ActionResult result, Map<String, List<String>> formParams) {
        if (!formParams.containsKey(REDIRECT_URL)) {
            return;
        }
        String redirectUrl = formParams.get(REDIRECT_URL).get(0);
        if (ActionSecurityUtils.isSafeRedirect(redirectUrl)) {
            result.setUrl(redirectUrl);
        } else if (logger.isWarnEnabled()) {
            logger.warn(LOG_UNSAFE_REDIRECT, ActionSecurityUtils.sanitizeForLog(redirectUrl));
        }
    }

    private static void partitionParams(Map<String, List<String>> source,
                                        List<String> moduleKeys, List<String> versionKeys,
                                        Map<String, List<String>> moduleTarget,
                                        Map<String, List<String>> versionTarget) {
        for (Map.Entry<String, List<String>> e : source.entrySet()) {
            String key = e.getKey();
            List<String> value = e.getValue();
            if (value == null || value.isEmpty() || value.get(0) == null) {
                continue;
            }
            if (moduleKeys.contains(key)) {
                moduleTarget.put(key, value);
            } else if (versionKeys.contains(key)) {
                versionTarget.put(key, value);
            }
        }
    }

    /**
     * Reject archive-supplied coordinates that could traverse the JCR tree. groupId and
     * moduleName come from the uploaded package.json / MANIFEST.MF and are concatenated into a
     * JCR relative path (groupId-as-folders + "/" + moduleName); a ".." segment or a backslash
     * could escape the modules repository. Dotted groupIds and scoped (@scope/name) module names
     * remain valid — only parent-traversal and path escapes are rejected (SECURITY-571).
     */
    static boolean isSafeCoordinate(String groupId, String moduleName) {
        return isSafePathFragment(groupId) && isSafePathFragment(moduleName);
    }

    /**
     * True when {@code value} cannot escape or deepen the JCR relative path it is spliced into:
     * no ".." parent-traversal, no "\\" or "/" path separator (a "/" in an addNode relative path
     * silently creates extra nested children — SECURITY-571).
     */
    static boolean isSafePathFragment(String value) {
        return value != null && !value.contains("..") && !value.contains("\\") && !value.contains("/");
    }

    /**
     * Reject a package id that could traverse the JCR tree. {@code Jahia-Package-ID} comes from the
     * uploaded MANIFEST.MF and is concatenated into the relative path "packages/" + id; it is a flat
     * identifier, so any "/", "\\" or ".." segment is illegal (SECURITY-571). Mirrors the
     * {@link #isSafeCoordinate} guard the module and JS upload paths already apply.
     */
    static boolean isSafePackageName(String name) {
        return isSafePathFragment(name);
    }

    /** True when {@code value} is a plain Maven coordinate token (no expression / option metacharacters). */
    static boolean isSafeMavenCoordinate(String value) {
        return value != null && SAFE_MAVEN_COORD.matcher(value).matches();
    }

    /**
     * True when the archive-supplied required-version is a plain version token. It is concatenated
     * into the {@code version-<x>} JCR node name and navigated/created under
     * {@code …/modules-required-versions}; restricting it to the Maven-coordinate charset keeps a
     * crafted value (e.g. {@code 8.2/../../x}) from traversing the JCR tree (SECURITY-571).
     */
    static boolean isSafeRequiredVersion(String value) {
        return isSafeMavenCoordinate(value);
    }

    /** Outcome of preparing a module node for a new version: the module + an optional conflict error. */
    private static final class ModulePrep {
        private final JCRNodeWrapper module;
        private final ActionResult conflict;

        private ModulePrep(JCRNodeWrapper module, ActionResult conflict) {
            this.module = module;
            this.conflict = conflict;
        }
    }

    /**
     * Upsert the module node (creating its groupId folders as needed), grant the caller the owner
     * role, and check whether {@code version} may be added. Shared by both upload paths.
     */
    private ModulePrep prepareModuleVersion(HttpServletRequest request, JCRNodeWrapper repository, String groupId,
                                            String moduleName, Map<String, List<String>> moduleParameters,
                                            String version, OwnerRoleGrants ownerGrants)
            throws RepositoryException, JSONException {
        final String moduleRelPath = groupId.replace(".", FileSystem.SEPARATOR) + FileSystem.SEPARATOR + moduleName;
        final JCRNodeWrapper module = upsertModuleNode(request, repository, moduleRelPath, groupId, moduleName, moduleParameters);
        ownerGrants.recordOwner(module);
        return new ModulePrep(module, versionConflict(module, version, moduleName, ownerGrants.getCallerSession()));
    }

    /** The "version already exists" error result, or null when {@code version} may be added. */
    private ActionResult versionConflict(JCRNodeWrapper module, String version, String moduleName,
                                         JCRSessionWrapper session) throws RepositoryException, JSONException {
        if (JCRTagUtils.hasChildrenOfType(module, JNT_FORGEMODULEVERSION) && !hasValidVersionNumber(module, version)) {
            final String error = Messages.getWithArgs(RESOURCES_JAHIA_STORE, ERR_VERSION_NUMBER,
                    session.getLocale(), moduleName, version);
            return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject().put(ERROR, error));
        }
        return null;
    }

    /**
     * Split the collected form params into the module-level and version-level parameter maps
     * (populating the supplied maps) and return the resolved module title (the submitted
     * jcr:title, or the module name when none was given). Shared by both upload paths.
     */
    private String populateParameterMaps(Map<String, List<String>> params, String nameFallback,
                                         List<String> entityParamKeys, Map<String, List<String>> entityParameters,
                                         Map<String, List<String>> versionParameters) {
        String title = getParameter(params, Constants.JCR_TITLE);
        if (StringUtils.isEmpty(title)) {
            title = nameFallback;
        }
        entityParameters.put(Constants.JCR_TITLE, Arrays.asList(title));
        versionParameters.put(Constants.JCR_TITLE, Arrays.asList(title));
        partitionParams(params, entityParamKeys, VERSION_PARAM_KEYS, entityParameters, versionParameters);
        return title;
    }

    /** Create the version node under a module, set its dependency references, and grant the owner role. */
    private JCRNodeWrapper createModuleVersion(HttpServletRequest request, JCRNodeWrapper module,
                                               Map<String, List<String>> versionParameters, String version,
                                               String dependencies, OwnerRoleGrants ownerGrants) throws RepositoryException {
        final JCRNodeWrapper moduleVersion = createNode(request, versionParameters, module,
                JNT_FORGEMODULEVERSION, module.getName() + "-" + version, false);
        moduleVersion.setProperty(REFERENCES, dependencies != null ? dependencies.split(",") : EMPTY_REFERENCES);
        stampUploadDate(moduleVersion);
        ownerGrants.recordOwner(moduleVersion);
        return moduleVersion;
    }

    /** Written once, at creation: the guard stops any later path re-dating a release. */
    private void stampUploadDate(JCRNodeWrapper versionNode) throws RepositoryException {
        if (!versionNode.hasProperty(UPLOAD_DATE)) {
            versionNode.setProperty(UPLOAD_DATE, Calendar.getInstance());
        }
    }

    /** Build the success result (module URLs + safe redirect) shared by both upload paths. */
    private ActionResult buildUploadResult(HttpServletRequest request, RenderContext renderContext,
                                           JCRNodeWrapper module, Map<String, List<String>> formParams) throws JSONException {
        final String moduleUrl = renderContext.getResponse().encodeURL(module.getUrl());
        final String moduleAbsoluteUrl = module.getProvider().getAbsoluteContextPath(request) + moduleUrl;
        final ActionResult uploadResult = new ActionResult(HttpServletResponse.SC_OK, null,
                new JSONObject().put(SUCCESS_REDIRECT_URL, moduleUrl).put(SUCCESS_REDIRECT_ABSOLUTE_URL, moduleAbsoluteUrl));
        applySafeRedirect(uploadResult, formParams);
        return uploadResult;
    }

    private JCRNodeWrapper upsertModuleNode(HttpServletRequest request, JCRNodeWrapper repositoryStart,
                                            String moduleRelPath, String groupId, String moduleName,
                                            Map<String, List<String>> moduleParameters) throws RepositoryException {
        if (!repositoryStart.hasNode(moduleRelPath)) {
            JCRNodeWrapper cursor = repositoryStart;
            for (String segment : groupId.split("\\.")) {
                if (cursor.hasNode(segment)) {
                    cursor = cursor.getNode(segment);
                } else {
                    cursor = cursor.addNode(segment, JNT_CONTENT_FOLDER);
                }
            }
            return createNode(request, moduleParameters, cursor, "jnt:forgeModule", moduleName, false);
        }
        JCRNodeWrapper module = repositoryStart.getNode(moduleRelPath);
        moduleParameters.remove(DESCRIPTION);
        moduleParameters.remove(Constants.JCR_TITLE);
        setProperties(module, moduleParameters);
        return module;
    }

    public void setMavenExecutable(String mavenExecutable) {
        if (System.getProperty("os.name").toLowerCase().startsWith("windows") && !mavenExecutable.endsWith(".bat")) {
            mavenExecutable = mavenExecutable + ".bat";
        }
        this.mavenExecutable = mavenExecutable;
    }

    private JCRNodeWrapper getJahiaVersion(String requiredVersion, Resource resource, JCRSessionWrapper session) throws RepositoryException {
        JCRNodeWrapper versions = session.getNode(resource.getNode().getResolveSite().getPath() + "/contents/modules-required-versions");

        if (!versions.hasNode(requiredVersion)) {
            Version v = new Version(requiredVersion);
            JCRNodeWrapper n = versions.addNode(requiredVersion, "jnt:jahiaVersion");
            n.setProperty("major", v.getMajorVersion());
            n.setProperty("minor", v.getMinorVersion());
            n.setProperty("servicePack", v.getServicePackVersion());
            n.setProperty("patch", v.getPatchVersion());
            n.setProperty("releaseCandidate", v.getReleaseCandidateNumber());
            n.setProperty("beta", v.getBetaNumber());
            n.setProperty("qualifier", v.getQualifiers().toArray(new String[v.getQualifiers().size()]));
        }

        return versions;
    }

    private boolean hasValidVersionNumber(JCRNodeWrapper node, String versionNumber) throws RepositoryException {

        if (StringUtils.isEmpty(versionNumber)) {
            return false;
        }

        List<JCRNodeWrapper> nodeVersions;

        if (node.hasProperty(JNT_FORGEMODULEVERSION)) {
            nodeVersions = JCRTagUtils.getChildrenOfType(node, JNT_FORGEMODULEVERSION);
        } else {
            nodeVersions = JCRTagUtils.getChildrenOfType(node, JNT_FORGEPACKAGEVERSION);
        }

        for (JCRNodeWrapper nodeVersion : nodeVersions) {
            if (nodeVersion.hasProperty(VERSION_NUMBER) && nodeVersion.getProperty(VERSION_NUMBER).getString().equals(versionNumber))
                return false;
        }

        return true;
    }

    private void deployToMaven(String groupId, String artifactId, ModuleReleaseInfo releaseInfo, File generatedJar) throws IOException {
        File settings = null;
        File pomFile = null;
        try {
            if (!StringUtils.isEmpty(releaseInfo.getUsername()) && !StringUtils.isEmpty(releaseInfo.getPassword())) {
                settings = File.createTempFile("settings", ".xml");
                try (BufferedWriter w = new BufferedWriter(new FileWriter(settings))) {
                    // XML-escape every injected value to prevent settings.xml injection
                    // (e.g. an attacker-controlled username/password breaking out into <mirror> elements).
                    w.write("<settings><servers><server><id>" + StringEscapeUtils.escapeXml(releaseInfo.getRepositoryId()) + "</id><username>");
                    w.write(StringEscapeUtils.escapeXml(releaseInfo.getUsername()));
                    w.write("</username><password>");
                    w.write(StringEscapeUtils.escapeXml(releaseInfo.getPassword()));
                    w.write("</password></server></servers></settings>");
                }
            }
            try (JarFile jar = new JarFile(generatedJar)) {
                pomFile = PomUtils.extractPomFromJar(jar, groupId, artifactId);
            }

            Model pom;
            try {
                pom = PomUtils.read(pomFile);
            } catch (XmlPullParserException e) {
                throw new IOException(e);
            }
            String version = pom.getVersion();
            if (version == null) {
                version = pom.getParent().getVersion();
            }
            if (version == null) {
                throw new IOException("unable to read project version");
            }
            // The coordinates come from the uploaded artifact's pom. Maven evaluates -D values
            // (e.g. ${...} expressions) and treats a leading '-' or embedded whitespace as further
            // options, so reject anything but a plain coordinate token before building the deploy
            // command (SECURITY-571 — option/expression injection into the deploy subprocess).
            final String pomGroupId = PomUtils.getGroupId(pom);
            final String pomArtifactId = pom.getArtifactId();
            if (!isSafeMavenCoordinate(pomGroupId) || !isSafeMavenCoordinate(pomArtifactId)
                    || !isSafeMavenCoordinate(version)) {
                throw new IOException("Refusing to deploy: unsafe Maven coordinate in the uploaded artifact pom");
            }
            String[] deployParams = {"deploy:deploy-file", "-Dfile=" + generatedJar,
                    "-DrepositoryId=" + releaseInfo.getRepositoryId(), "-Durl=" + releaseInfo.getRepositoryUrl(),
                    "-DpomFile=" + pomFile.getPath(),
                    "-Dpackaging=" + StringUtils.substringAfterLast(generatedJar.getName(), "."),
                    "-DgroupId=" + pomGroupId, "-DartifactId=" + pomArtifactId,
                    "-Dversion=" + version};
            if (settings != null) {
                deployParams = (String[]) ArrayUtils.addAll(deployParams,
                        new String[]{"--settings", settings.getPath()});
            }
            StringBuilder out = new StringBuilder();
            int ret = ProcessHelper.execute(mavenExecutable, deployParams, null,
                    generatedJar.getParentFile(), out, out);

            if (ret > 0) {
                String s = getMavenError(out.toString());
                // Log only the filtered [ERROR] lines, not the full Maven output: the latter echoes
                // the configured Nexus URL ("Uploading to ...") into the application log (SECURITY-571).
                logger.error("Maven deployment failed (exit {}): {}", ret, s);
                throw new IOException("Maven invocation failed\n" + s);
            }
        } finally {
            FileUtils.deleteQuietly(settings);
            FileUtils.deleteQuietly(pomFile);
        }
    }

    private String getMavenError(String out) {
        Matcher m = Pattern.compile("^\\[ERROR\\](.*)$", Pattern.MULTILINE).matcher(out);
        StringBuilder s = new StringBuilder();
        while (m.find()) {
            s.append(m.group(1)).append("\n");
        }
        return s.toString();
    }
}
