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

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang.StringUtils;
import org.jahia.settings.SettingsBean;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The configuration of the git-backed modules, from {@code org.jahia.modules.forge.git.cfg}: where
 * the clones live, how often the tags are refreshed, and which tags are versions when a
 * registration names no pattern.
 */
@Component(service = GitStoreSettings.class, immediate = true, configurationPid = GitStoreSettings.PID)
public class GitStoreSettings {

    static final String PID = "org.jahia.modules.forge.git";
    static final String P_CACHE_DIR = "cacheDir";
    static final String P_REFRESH_SECONDS = "refreshSeconds";
    static final String P_TAG_PATTERN = "tagPattern";
    static final long DEFAULT_REFRESH_SECONDS = 300;
    private static final String DEFAULT_CACHE_DIR_NAME = "forge-git";

    private static final Logger LOGGER = LoggerFactory.getLogger(GitStoreSettings.class);

    /** One immutable reading of the configuration, swapped as a whole on each update. */
    private static final class Values {
        private final File cacheDir;
        private final long refreshMillis;
        private final String tagPattern;

        private Values(File cacheDir, long refreshMillis, String tagPattern) {
            this.cacheDir = cacheDir;
            this.refreshMillis = refreshMillis;
            this.tagPattern = tagPattern;
        }
    }

    private final AtomicReference<Values> values = new AtomicReference<>(defaults());

    @Activate
    public void activate(Map<String, Object> properties) {
        apply(properties);
    }

    @Modified
    public void modified(Map<String, Object> properties) {
        apply(properties);
    }

    void apply(Map<String, Object> properties) {
        Values defaults = defaults();
        File cacheDir = defaults.cacheDir;
        long refreshMillis = defaults.refreshMillis;
        String tagPattern = defaults.tagPattern;
        if (properties != null) {
            String dir = str(properties.get(P_CACHE_DIR));
            if (dir != null) {
                cacheDir = new File(dir);
            }
            String seconds = str(properties.get(P_REFRESH_SECONDS));
            if (seconds != null) {
                try {
                    refreshMillis = Math.max(0, Long.parseLong(seconds)) * 1000;
                } catch (NumberFormatException e) {
                    LOGGER.warn("Ignoring {}={} in {}: not a number of seconds", P_REFRESH_SECONDS, seconds, PID);
                }
            }
            String pattern = str(properties.get(P_TAG_PATTERN));
            if (pattern != null) {
                VersionTags.compile(pattern);
                tagPattern = pattern;
            }
        }
        values.set(new Values(cacheDir, refreshMillis, tagPattern));
        LOGGER.info("Git-backed modules: clones under {}, tags refreshed every {} s, version tags match {}",
                cacheDir, refreshMillis / 1000, tagPattern);
    }

    private static Values defaults() {
        return new Values(new File(defaultDataDir(), DEFAULT_CACHE_DIR_NAME), DEFAULT_REFRESH_SECONDS * 1000, VersionTags.DEFAULT_PATTERN);
    }

    private static String defaultDataDir() {
        SettingsBean settings = SettingsBean.getInstance();
        return settings != null && settings.getJahiaVarDiskPath() != null ? settings.getJahiaVarDiskPath() : System.getProperty("java.io.tmpdir");
    }

    private static String str(Object value) {
        return value == null || StringUtils.isBlank(String.valueOf(value)) ? null : String.valueOf(value).trim();
    }

    /** The directory under which each repository keeps its clone. */
    public File getCacheDir() {
        return values.get().cacheDir;
    }

    /** How long a reading of the tags stays current, in milliseconds. */
    public long getRefreshMillis() {
        return values.get().refreshMillis;
    }

    /** The pattern a tag must match to be a version, for a registration that names none. */
    public String getDefaultTagPattern() {
        return values.get().tagPattern;
    }
}
