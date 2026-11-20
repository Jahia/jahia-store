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
package org.jahia.modules.forge.actions;

import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.StringUtils;
import org.jahia.bin.ActionResult;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.utils.i18n.Messages;
import org.json.JSONObject;

/** The failure answers of the git-backed module actions: HTTP 200 with {@code {error}}, as the upload action answers. */
final class GitModuleActions {

    private static final String RESOURCES_JAHIA_STORE = "resources.jahia-store";
    private static final String ERROR = "error";
    /** The longest reason echoed to the browser; the whole message is in the log. */
    private static final int MAX_REASON_LENGTH = 250;

    private GitModuleActions() {
    }

    static ActionResult error(JCRSessionWrapper session, String key) throws Exception {
        return new ActionResult(HttpServletResponse.SC_OK, null,
                new JSONObject().put(ERROR, Messages.get(RESOURCES_JAHIA_STORE, key, session.getLocale())));
    }

    static ActionResult errorWithReason(JCRSessionWrapper session, String key, String reason) throws Exception {
        String text = StringUtils.abbreviate(StringUtils.defaultString(reason), MAX_REASON_LENGTH);
        return new ActionResult(HttpServletResponse.SC_OK, null,
                new JSONObject().put(ERROR, Messages.getWithArgs(RESOURCES_JAHIA_STORE, key, session.getLocale(), text)));
    }
}
