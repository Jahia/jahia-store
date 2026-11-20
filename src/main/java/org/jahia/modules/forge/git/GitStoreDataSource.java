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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import javax.jcr.Binary;
import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.util.ISO8601;
import org.jahia.api.Constants;
import org.jahia.modules.external.ExternalData;
import org.jahia.modules.external.ExternalDataSource;
import org.jahia.modules.external.ExternalQuery;
import org.jahia.modules.external.query.QueryHelper;
import org.jahia.services.content.nodetypes.NodeTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A read-only external data source that serves one git-backed module as the node tree that
 * docs/git-backed-modules.md maps: the module at the root, one version per listed version with
 * its files, the icon folder, the screenshots list and the files of the description. The
 * repository is read once, on the
 * first request, and that reading is served until {@link #refresh()} replaces it.
 */
public class GitStoreDataSource implements ExternalDataSource, ExternalDataSource.Initializable,
        ExternalDataSource.LazyProperty, ExternalDataSource.Searchable, ExternalDataSource.CanLoadChildrenInBatch,
        ExternalDataSource.CanCheckAvailability {

    static final String TYPE_MODULE = "jnt:forgeModule";
    static final String TYPE_VERSION = "jnt:forgeModuleVersion";
    static final String TYPE_SCREENSHOTS = "jnt:forgeScreenshotsList";
    static final String TYPE_TABS = "jnt:forgeTabs";
    static final String TYPE_TAB = "jnt:forgeTab";
    static final String NODE_ICON = "icon";
    static final String NODE_SCREENSHOTS = "screenshots";
    static final String NODE_FILES = GitModuleLoader.FILES_FOLDER;
    static final String NODE_TABS = "tabs";
    static final String STATUS_COMMUNITY = "community";

    private static final Logger LOGGER = LoggerFactory.getLogger(GitStoreDataSource.class);
    private static final String ROOT = "/";
    private static final Set<String> SUPPORTED_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            TYPE_MODULE, TYPE_VERSION, TYPE_SCREENSHOTS, TYPE_TABS, TYPE_TAB,
            Constants.JAHIANT_FOLDER, Constants.JAHIANT_FILE, Constants.JAHIANT_RESOURCE)));
    private static final String[] TRUE = {"true"};
    private static final String[] FALSE = {"false"};
    /** How long a failed first reading holds before a request tries the remote again. */
    private static final long RETRY_MILLIS = 60_000;

    private final GitRepositoryReader reader;
    private final String branch;
    private final Set<String> languages;
    private final String mountPoint;
    private final String filesBaseUrl;
    private final BiPredicate<String, String> isNodeType;

    private volatile String registeredBy;

    private final AtomicReference<GitModule> snapshot = new AtomicReference<>();
    private final Object loadLock = new Object();
    private volatile long lastFailure;

    /**
     * @param reader       the repository
     * @param branch       the store branch, or null for the default branch of the repository
     * @param languages    the languages of the site, which the i18n properties are served in
     * @param mountPoint   the JCR path the provider is mounted at, which a query names
     * @param filesBaseUrl the URL under which the files of the module are served, without a
     *                     trailing slash, which the relative links of the release notes resolve against
     */
    public GitStoreDataSource(GitRepositoryReader reader, String branch, Set<String> languages, String mountPoint,
                              String filesBaseUrl) {
        this(reader, branch, languages, mountPoint, filesBaseUrl, GitStoreDataSource::registryIsNodeType);
    }

    GitStoreDataSource(GitRepositoryReader reader, String branch, Set<String> languages, String mountPoint,
                       String filesBaseUrl, BiPredicate<String, String> isNodeType) {
        this.reader = reader;
        this.branch = branch;
        this.languages = languages == null || languages.isEmpty() ? Collections.singleton("en") : new LinkedHashSet<>(languages);
        this.mountPoint = StringUtils.removeEnd(mountPoint, "/");
        this.filesBaseUrl = filesBaseUrl == null ? null : StringUtils.removeEnd(filesBaseUrl, "/");
        this.isNodeType = isNodeType;
    }

    private static boolean registryIsNodeType(String type, String queried) {
        try {
            return NodeTypeRegistry.getInstance().getNodeType(type).isNodeType(queried);
        } catch (RepositoryException e) {
            return false;
        }
    }

    /**
     * The user the module is registered for. The module and its versions read that user as their
     * creator, which is how the storefront lists a developer's own modules.
     */
    public void setRegisteredBy(String registeredBy) {
        this.registeredBy = StringUtils.trimToNull(registeredBy);
    }

    /**
     * Read the repository again and serve that reading from now on. A reading that fails leaves
     * the previous one in place.
     *
     * @return the new reading
     * @throws IOException            when the remote cannot be read
     * @throws StoreManifestException when the repository fails the contract
     */
    public GitModule refresh() throws IOException {
        synchronized (loadLock) {
            try {
                GitModule loaded = GitModuleLoader.load(reader, branch, filesBaseUrl);
                snapshot.set(loaded);
                lastFailure = 0;
                return loaded;
            } catch (IOException | StoreManifestException e) {
                lastFailure = System.currentTimeMillis();
                throw e;
            }
        }
    }

    /** The current reading of the module, read on the first request. */
    GitModule module() throws RepositoryException {
        GitModule current = snapshot.get();
        if (current != null) {
            return current;
        }
        synchronized (loadLock) {
            current = snapshot.get();
            if (current != null) {
                return current;
            }
            if (System.currentTimeMillis() - lastFailure < RETRY_MILLIS) {
                throw new RepositoryException("The module of " + reader.getUrl() + " could not be read; retrying later");
            }
            try {
                return refresh();
            } catch (IOException | StoreManifestException e) {
                throw new RepositoryException("Cannot read the module of " + reader.getUrl() + ": " + e.getMessage(), e);
            }
        }
    }

    // --- ExternalDataSource ---------------------------------------------------------------------

    @Override
    public Set<String> getSupportedNodeTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean isSupportsHierarchicalIdentifiers() {
        return true;
    }

    @Override
    public boolean isSupportsUuid() {
        return false;
    }

    @Override
    public boolean itemExists(String path) {
        try {
            return describe(module(), path) != null;
        } catch (RepositoryException e) {
            return false;
        }
    }

    @Override
    public ExternalData getItemByIdentifier(String identifier) throws ItemNotFoundException {
        try {
            return getItemByPath(identifier);
        } catch (PathNotFoundException e) {
            throw new ItemNotFoundException(identifier, e);
        }
    }

    @Override
    public ExternalData getItemByPath(String path) throws PathNotFoundException {
        try {
            ExternalData data = describe(module(), path);
            if (data == null) {
                throw new PathNotFoundException(path);
            }
            return data;
        } catch (RepositoryException e) {
            throw new PathNotFoundException(path + ": " + e.getMessage());
        }
    }

    @Override
    public List<String> getChildren(String path) throws RepositoryException {
        List<String> names = new ArrayList<>();
        for (ExternalData child : getChildrenNodes(path)) {
            names.add(child.getName());
        }
        return names;
    }

    @Override
    public List<ExternalData> getChildrenNodes(String path) throws RepositoryException {
        GitModule module = module();
        List<ExternalData> children = new ArrayList<>();
        if (ROOT.equals(path)) {
            children.add(folder(NODE_ICON, Constants.JAHIANT_FOLDER));
            children.add(folder(NODE_SCREENSHOTS, TYPE_SCREENSHOTS));
            children.add(folder(NODE_FILES, Constants.JAHIANT_FOLDER));
            children.add(folder(NODE_TABS, TYPE_TABS));
            for (GitModule.Version version : module.getVersions()) {
                children.add(versionData(module, version));
            }
            return children;
        }
        String[] segments = path.substring(1).split("/");
        if (segments.length == 1 && NODE_TABS.equals(segments[0])) {
            for (GitModule.Tab tab : module.getTabs()) {
                children.add(tabData(module, tab));
            }
        } else if (segments.length == 1) {
            // A folder: its files.
            String folderPath = "/" + segments[0];
            for (GitFile file : filesOf(module, segments[0])) {
                children.add(fileData(module, folderPath, file));
            }
        } else if (segments.length == 2) {
            // A file: its content node.
            GitFile file = fileOf(module, segments[0], segments[1]);
            if (file != null) {
                children.add(fileContent(module, "/" + segments[0], file));
            }
        }
        return children;
    }

    private ExternalData describe(GitModule module, String path) {
        if (path == null || path.isEmpty() || ROOT.equals(path)) {
            return moduleData(module);
        }
        String[] segments = path.substring(1).split("/");
        if (segments.length == 1) {
            if (NODE_ICON.equals(segments[0])) {
                return folder(NODE_ICON, Constants.JAHIANT_FOLDER);
            }
            if (NODE_SCREENSHOTS.equals(segments[0])) {
                return folder(NODE_SCREENSHOTS, TYPE_SCREENSHOTS);
            }
            if (NODE_FILES.equals(segments[0])) {
                return folder(NODE_FILES, Constants.JAHIANT_FOLDER);
            }
            if (NODE_TABS.equals(segments[0])) {
                return folder(NODE_TABS, TYPE_TABS);
            }
            GitModule.Version version = module.version(segments[0]);
            return version == null ? null : versionData(module, version);
        }
        if (NODE_TABS.equals(segments[0])) {
            GitModule.Tab tab = module.tab(segments[1]);
            return tab == null || segments.length != 2 ? null : tabData(module, tab);
        }
        GitFile file = fileOf(module, segments[0], segments[1]);
        if (file == null) {
            return null;
        }
        if (segments.length == 2) {
            return fileData(module, "/" + segments[0], file);
        }
        if (segments.length == 3 && Constants.JCR_CONTENT.equals(segments[2])) {
            return fileContent(module, "/" + segments[0], file);
        }
        return null;
    }

    /** The files served under a folder of the provider: the icon folder, the screenshots list or a version. */
    private static List<GitFile> filesOf(GitModule module, String folder) {
        if (NODE_ICON.equals(folder)) {
            return module.getIcon() == null ? Collections.emptyList() : Collections.singletonList(module.getIcon());
        }
        if (NODE_SCREENSHOTS.equals(folder)) {
            return module.getScreenshots();
        }
        if (NODE_FILES.equals(folder)) {
            return module.getFiles();
        }
        GitModule.Version version = module.version(folder);
        return version == null ? Collections.emptyList() : version.getFiles();
    }

    private static GitFile fileOf(GitModule module, String folder, String name) {
        return GitModule.file(filesOf(module, folder), name);
    }

    // --- The nodes ------------------------------------------------------------------------------

    private ExternalData moduleData(GitModule module) {
        StoreManifest manifest = module.getManifest();
        Map<String, String[]> properties = new HashMap<>();
        properties.put(Constants.JCR_TITLE, single(manifest.getTitle()));
        properties.put("groupId", single(manifest.getGroupId()));
        properties.put("published", TRUE);
        properties.put("deleted", FALSE);
        properties.put("supportedByJahia", FALSE);
        properties.put("reviewedByJahia", FALSE);
        properties.put("downloadCount", single("0"));
        properties.put("status", single(STATUS_COMMUNITY));
        properties.put("codeRepository", single(module.getRepositoryUrl()));
        putIfPresent(properties, "authorURL", manifest.getAuthorUrl());
        putIfPresent(properties, "authorEmail", manifest.getAuthorEmail());
        Instant lastModified = module.getReadAt();
        for (GitModule.Version version : module.getVersions()) {
            if (!version.isDisabled()) {
                lastModified = version.getDate();
                break;
            }
        }
        properties.put(Constants.JCR_LASTMODIFIED, single(iso(lastModified)));
        properties.put(Constants.JCR_CREATED, single(iso(oldestDate(module))));
        putCreator(properties);

        Map<String, String[]> translated = new HashMap<>();
        translated.put(Constants.JCR_TITLE, single(manifest.getTitle()));
        translated.put("description", single(module.getDescriptionHtml()));
        // A License tab of the repository takes the place of the license field on the page.
        if (!module.hasTab("License")) {
            putIfPresent(translated, "license", manifest.getLicense());
        }

        ExternalData data = new ExternalData(ROOT, ROOT, TYPE_MODULE, properties);
        data.setI18nProperties(forEveryLanguage(translated));
        return data;
    }

    private ExternalData versionData(GitModule module, GitModule.Version version) {
        String path = "/" + version.getNumber();
        Map<String, String[]> properties = new HashMap<>();
        properties.put("versionNumber", single(version.getNumber()));
        properties.put("url", single(version.getArtifactUrl()));
        // A disabled version is served unpublished: the storefront shows it to the editors of the
        // module only, and the catalogue JSON leaves it out, as it does with a local draft.
        properties.put("published", version.isDisabled() ? FALSE : TRUE);
        putIfPresent(properties, "changeLog", version.getNotesHtml());
        putIfPresent(properties, "tag", version.getTag());
        putIfPresent(properties, "tagUrl", version.getTagUrl());
        putIfPresent(properties, "changelogUrl", version.getChangelogUrl());
        if (!version.getDependencies().isEmpty()) {
            properties.put("references", version.getDependencies().toArray(new String[0]));
        }
        String date = iso(version.getDate());
        properties.put("uploadDate", single(date));
        properties.put(Constants.JCR_CREATED, single(date));
        properties.put(Constants.JCR_LASTMODIFIED, single(date));
        putCreator(properties);
        return new ExternalData(path, path, TYPE_VERSION, properties);
    }

    private ExternalData tabData(GitModule module, GitModule.Tab tab) {
        String path = "/" + NODE_TABS + "/" + tab.getName();
        Map<String, String[]> properties = new HashMap<>();
        properties.put(Constants.JCR_TITLE, single(tab.getTitle()));
        properties.put("content", single(tab.getContentHtml()));
        String date = iso(module.getReadAt());
        properties.put(Constants.JCR_CREATED, single(date));
        properties.put(Constants.JCR_LASTMODIFIED, single(date));
        putCreator(properties);
        ExternalData data = new ExternalData(path, path, TYPE_TAB, properties);
        Map<String, String[]> translated = new HashMap<>();
        translated.put(Constants.JCR_TITLE, single(tab.getTitle()));
        data.setI18nProperties(forEveryLanguage(translated));
        return data;
    }

    private void putCreator(Map<String, String[]> properties) {
        String creator = registeredBy;
        if (creator != null) {
            properties.put(Constants.JCR_CREATEDBY, single(creator));
            properties.put(Constants.JCR_LASTMODIFIEDBY, single(creator));
        }
    }

    private ExternalData folder(String name, String type) {
        String path = "/" + name;
        return new ExternalData(path, path, type, new HashMap<>());
    }

    private ExternalData fileData(GitModule module, String folderPath, GitFile file) {
        String path = folderPath + "/" + file.getName();
        Map<String, String[]> properties = new HashMap<>();
        String date = iso(module.getReadAt());
        properties.put(Constants.JCR_CREATED, single(date));
        properties.put(Constants.JCR_LASTMODIFIED, single(date));
        putCreator(properties);
        ExternalData data = new ExternalData(path, path, Constants.JAHIANT_FILE, properties);
        if (file.isImage()) {
            data.setMixin(Collections.singletonList(Constants.JAHIAMIX_IMAGE));
        }
        return data;
    }

    private ExternalData fileContent(GitModule module, String folderPath, GitFile file) {
        String path = folderPath + "/" + file.getName() + "/" + Constants.JCR_CONTENT;
        Map<String, String[]> properties = new HashMap<>();
        properties.put(Constants.JCR_MIMETYPE, single(file.getMimeType()));
        properties.put(Constants.JCR_LASTMODIFIED, single(iso(module.getReadAt())));
        ExternalData data = new ExternalData(path, path, Constants.JAHIANT_RESOURCE, properties);
        Map<String, Binary[]> binaries = new HashMap<>();
        binaries.put(Constants.JCR_DATA, new Binary[]{new BytesBinary(file.getBytes())});
        data.setBinaryProperties(binaries);
        return data;
    }

    private Map<String, Map<String, String[]>> forEveryLanguage(Map<String, String[]> translated) {
        Map<String, Map<String, String[]>> result = new HashMap<>();
        for (String language : languages) {
            result.put(language, translated);
        }
        return result;
    }

    private static Instant oldestDate(GitModule module) {
        List<GitModule.Version> versions = module.getVersions();
        return versions.isEmpty() ? module.getReadAt() : versions.get(versions.size() - 1).getDate();
    }

    private static String iso(Instant instant) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTime(Date.from(instant));
        return ISO8601.format(calendar);
    }

    private static String[] single(String value) {
        return new String[]{value};
    }

    private static void putIfPresent(Map<String, String[]> properties, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            properties.put(name, single(value));
        }
    }

    // --- LazyProperty: the i18n values are the same in every language ---------------------------

    @Override
    public String[] getPropertyValues(String path, String propertyName) throws PathNotFoundException {
        String[] values = getItemByPath(path).getProperties().get(propertyName);
        if (values == null) {
            throw new PathNotFoundException(path + "/" + propertyName);
        }
        return values;
    }

    @Override
    public String[] getI18nPropertyValues(String path, String lang, String propertyName) throws PathNotFoundException {
        Map<String, Map<String, String[]>> i18n = getItemByPath(path).getI18nProperties();
        if (i18n != null) {
            for (Map<String, String[]> values : i18n.values()) {
                if (values.containsKey(propertyName)) {
                    return values.get(propertyName);
                }
            }
        }
        throw new PathNotFoundException(path + "/" + propertyName);
    }

    @Override
    public Binary[] getBinaryPropertyValues(String path, String propertyName) throws PathNotFoundException {
        Map<String, Binary[]> binaries = getItemByPath(path).getBinaryProperties();
        if (binaries == null || !binaries.containsKey(propertyName)) {
            throw new PathNotFoundException(path + "/" + propertyName);
        }
        return binaries.get(propertyName);
    }

    // --- Searchable: a query on the module types, scoped by path and by equality constraints -----

    @Override
    public List<String> search(ExternalQuery query) throws RepositoryException {
        String queriedType = QueryHelper.getNodeType(query.getSource());
        String scope = scopeOf(query);
        if (scope == null) {
            return Collections.emptyList();
        }
        Map<String, Value> constraints;
        try {
            constraints = QueryHelper.getSimpleAndConstraints(query.getConstraint());
        } catch (UnsupportedRepositoryOperationException e) {
            constraints = Collections.emptyMap();
        }
        List<String> results = new ArrayList<>();
        for (ExternalData node : allNodes(module())) {
            if (isNodeType.test(node.getType(), queriedType) && inScope(node.getPath(), scope) && matches(node, constraints)) {
                results.add(node.getPath());
            }
        }
        return results;
    }

    /** The path inside the provider a query is scoped to, or null when the query names another tree. */
    private String scopeOf(ExternalQuery query) throws RepositoryException {
        String root;
        try {
            root = QueryHelper.getRootPath(query.getConstraint());
        } catch (UnsupportedRepositoryOperationException e) {
            return ROOT;
        }
        if (root == null || ROOT.equals(root)) {
            return ROOT;
        }
        root = StringUtils.removeEnd(root, "/");
        if (root.equals(mountPoint)) {
            return ROOT;
        }
        if (root.startsWith(mountPoint + "/")) {
            return root.substring(mountPoint.length());
        }
        if (mountPoint.startsWith(root + "/")) {
            return ROOT;
        }
        if (!root.startsWith("/")) {
            return "/" + root;
        }
        return null;
    }

    private static boolean inScope(String path, String scope) {
        return ROOT.equals(scope) || path.startsWith(scope + "/");
    }

    private static boolean matches(ExternalData node, Map<String, Value> constraints) throws RepositoryException {
        for (Map.Entry<String, Value> constraint : constraints.entrySet()) {
            String[] values = node.getProperties().get(constraint.getKey());
            if (values == null || values.length == 0 || !values[0].equals(constraint.getValue().getString())) {
                return false;
            }
        }
        return true;
    }

    private List<ExternalData> allNodes(GitModule module) throws RepositoryException {
        List<ExternalData> nodes = new ArrayList<>();
        nodes.add(moduleData(module));
        for (ExternalData child : getChildrenNodes(ROOT)) {
            nodes.add(child);
            nodes.addAll(getChildrenNodes(child.getPath()));
        }
        return nodes;
    }

    // --- Lifecycle and availability ---------------------------------------------------------------

    @Override
    public void start() {
        // The repository is read on the first request, not at mount.
    }

    @Override
    public void stop() {
        reader.close();
    }

    @Override
    public boolean isAvailable() throws RepositoryException {
        module();
        return true;
    }
}
