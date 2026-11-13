package org.jahia.modules.forge.security;

import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.services.sites.JahiaSitesService;

/**
 * Location of a site's security advisories: {@code /sites/<site>/contents/security-advisories}, a
 * {@code jnt:contentFolder} created by the jahia-store-template site import (by hand in older sites).
 */
final class AdvisoriesFolder {

    static final String NAME = "security-advisories";
    static final String CONTENTS = "contents";

    private AdvisoriesFolder() {
    }

    /** @return the JCR path of the site's advisories folder */
    static String path(String siteKey) {
        return sitePath(siteKey) + FileSystem.SEPARATOR + CONTENTS + FileSystem.SEPARATOR + NAME;
    }

    /** @return the JCR path of the site node */
    static String sitePath(String siteKey) {
        return JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR + siteKey;
    }
}
