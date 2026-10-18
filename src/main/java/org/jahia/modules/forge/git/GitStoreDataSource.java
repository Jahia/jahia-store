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
import java.util.regex.Pattern;
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
 * docs/git-backed-modules.md maps: the module at the root, one version per tag, the icon folder
 * and an empty screenshots list. The repository is read on demand, through a snapshot that is
 * refreshed at most once per refresh interval, or when a refresh is requested.
 */
public class GitStoreDataSource implements ExternalDataSource, ExternalDataSource.Initializable,
        ExternalDataSource.LazyProperty, ExternalDataSource.Searchable, ExternalDataSource.CanLoadChildrenInBatch,
        ExternalDataSource.CanCheckAvailability {

    static final String TYPE_MODULE = "jnt:forgeModule";
    static final String TYPE_VERSION = "jnt:forgeModuleVersion";
    static final String TYPE_SCREENSHOTS = "jnt:forgeScreenshotsList";
    static final String NODE_ICON = "icon";
    static final String NODE_SCREENSHOTS = "screenshots";
    static final String STATUS_COMMUNITY = "community";

    private static final Logger LOGGER = LoggerFactory.getLogger(GitStoreDataSource.class);
    private static final String ROOT = "/";
    private static final Set<String> SUPPORTED_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            TYPE_MODULE, TYPE_VERSION, TYPE_SCREENSHOTS, Constants.JAHIANT_FOLDER, Constants.JAHIANT_FILE, Constants.JAHIANT_RESOURCE)));
    private static final String[] TRUE = {"true"};
    private static final String[] FALSE = {"false"};

    private final GitRepositoryReader reader;
    private final String branch;
    private final Pattern tagPattern;
    private final long refreshMillis;
    private final Set<String> languages;
    private final String mountPoint;
    private final BiPredicate<String, String> isNodeType;

    private volatile String registeredBy;

    private final AtomicReference<GitModule> snapshot = new AtomicReference<>();
    private final Object loadLock = new Object();
    private volatile long lastAttempt;
    private volatile boolean refreshRequested;

    /**
     * @param reader        the repository
     * @param branch        the branch that holds the root manifest, or null for the default branch
     * @param tagPattern    the pattern a tag must match to be a version
     * @param refreshMillis how long a reading of the repository stays current
     * @param languages     the languages of the site, which the i18n properties are served in
     * @param mountPoint    the JCR path the provider is mounted at, which a query names
     */
    public GitStoreDataSource(GitRepositoryReader reader, String branch, Pattern tagPattern, long refreshMillis,
                              Set<String> languages, String mountPoint) {
        this(reader, branch, tagPattern, refreshMillis, languages, mountPoint, GitStoreDataSource::registryIsNodeType);
    }

    GitStoreDataSource(GitRepositoryReader reader, String branch, Pattern tagPattern, long refreshMillis,
                       Set<String> languages, String mountPoint, BiPredicate<String, String> isNodeType) {
        this.reader = reader;
        this.branch = branch;
        this.tagPattern = tagPattern;
        this.refreshMillis = refreshMillis;
        this.languages = languages == null || languages.isEmpty() ? Collections.singleton("en") : new LinkedHashSet<>(languages);
        this.mountPoint = StringUtils.removeEnd(mountPoint, "/");
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

    /** Make the next read refresh the module from the repository. */
    public void requestRefresh() {
        refreshRequested = true;
    }

    /** The current reading of the module, refreshed when it is stale. */
    GitModule module() throws RepositoryException {
        GitModule current = snapshot.get();
        long now = System.currentTimeMillis();
        if (current != null && !refreshRequested && now - lastAttempt < refreshMillis) {
            return current;
        }
        synchronized (loadLock) {
            current = snapshot.get();
            now = System.currentTimeMillis();
            if (current != null && !refreshRequested && now - lastAttempt < refreshMillis) {
                return current;
            }
            lastAttempt = now;
            try {
                GitModule loaded = GitModuleLoader.load(reader, branch, tagPattern);
                snapshot.set(loaded);
                refreshRequested = false;
                return loaded;
            } catch (IOException | StoreManifestException e) {
                if (current != null) {
                    LOGGER.warn("Keeping the previous reading of {}: {}", reader.getUrl(), e.getMessage());
                    return current;
                }
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
            for (GitModule.Version version : module.getVersions()) {
                children.add(versionData(module, version));
            }
        } else if (("/" + NODE_ICON).equals(path) && module.getIconName() != null) {
            children.add(iconFile(module));
        } else if (("/" + NODE_ICON + "/" + module.getIconName()).equals(path) && module.getIconName() != null) {
            children.add(iconContent(module));
        }
        return children;
    }

    private ExternalData describe(GitModule module, String path) {
        if (path == null || path.isEmpty() || ROOT.equals(path)) {
            return moduleData(module);
        }
        String relative = path.substring(1);
        if (NODE_ICON.equals(relative)) {
            return folder(NODE_ICON, Constants.JAHIANT_FOLDER);
        }
        if (NODE_SCREENSHOTS.equals(relative)) {
            return folder(NODE_SCREENSHOTS, TYPE_SCREENSHOTS);
        }
        if (module.getIconName() != null) {
            String iconPath = NODE_ICON + "/" + module.getIconName();
            if (iconPath.equals(relative)) {
                return iconFile(module);
            }
            if ((iconPath + "/" + Constants.JCR_CONTENT).equals(relative)) {
                return iconContent(module);
            }
        }
        if (!relative.contains("/")) {
            GitModule.Version version = module.version(relative);
            if (version != null) {
                return versionData(module, version);
            }
        }
        return null;
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
        Instant lastModified = module.getVersions().isEmpty() ? module.getReadAt() : module.getVersions().get(0).getDate();
        properties.put(Constants.JCR_LASTMODIFIED, single(iso(lastModified)));
        properties.put(Constants.JCR_CREATED, single(iso(oldestDate(module))));
        putCreator(properties);

        Map<String, String[]> translated = new HashMap<>();
        translated.put(Constants.JCR_TITLE, single(manifest.getTitle()));
        translated.put("description", single(manifest.getDescriptionHtml()));
        putIfPresent(translated, "license", manifest.getLicense());

        ExternalData data = new ExternalData(ROOT, ROOT, TYPE_MODULE, properties);
        data.setI18nProperties(forEveryLanguage(translated));
        return data;
    }

    private ExternalData versionData(GitModule module, GitModule.Version version) {
        String path = "/" + version.getNumber();
        Map<String, String[]> properties = new HashMap<>();
        properties.put("versionNumber", single(version.getNumber()));
        properties.put("url", single(version.getArtifactUrl()));
        properties.put("published", TRUE);
        putIfPresent(properties, "changeLog", version.getChangelogHtml());
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

    private ExternalData iconFile(GitModule module) {
        String path = "/" + NODE_ICON + "/" + module.getIconName();
        Map<String, String[]> properties = new HashMap<>();
        String date = iso(module.getReadAt());
        properties.put(Constants.JCR_CREATED, single(date));
        properties.put(Constants.JCR_LASTMODIFIED, single(date));
        ExternalData data = new ExternalData(path, path, Constants.JAHIANT_FILE, properties);
        if (module.getIconMimeType() != null && module.getIconMimeType().startsWith("image/")) {
            data.setMixin(Collections.singletonList(Constants.JAHIAMIX_IMAGE));
        }
        return data;
    }

    private ExternalData iconContent(GitModule module) {
        String path = "/" + NODE_ICON + "/" + module.getIconName() + "/" + Constants.JCR_CONTENT;
        Map<String, String[]> properties = new HashMap<>();
        properties.put(Constants.JCR_MIMETYPE, single(module.getIconMimeType()));
        ExternalData data = new ExternalData(path, path, Constants.JAHIANT_RESOURCE, properties);
        Map<String, Binary[]> binaries = new HashMap<>();
        binaries.put(Constants.JCR_DATA, new Binary[]{new BytesBinary(module.getIconBytes())});
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
        nodes.addAll(getChildrenNodes(ROOT));
        if (module.getIconName() != null) {
            nodes.add(iconFile(module));
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
