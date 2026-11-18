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
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.actions;

import org.jahia.api.Constants;
import org.jahia.services.content.DefaultEventListener;
import org.osgi.service.component.annotations.Component;

/** {@link ForgeMediaMimeListener} on the edit (default) workspace. */
@Component(service = DefaultEventListener.class, immediate = true)
public class ForgeMediaMimeEditListener extends ForgeMediaMimeListener {

    public ForgeMediaMimeEditListener() {
        super(Constants.EDIT_WORKSPACE);
    }
}
