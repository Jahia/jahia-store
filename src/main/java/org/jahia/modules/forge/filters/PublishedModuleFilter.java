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
package org.jahia.modules.forge.filters;

import org.jahia.modules.forge.visibility.StoreVisibility;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.filter.AbstractFilter;
import org.jahia.services.render.filter.RenderChain;
import org.jahia.services.render.filter.RenderFilter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import javax.jcr.PathNotFoundException;

/**
 * Keeps a draft store element (module or package, {@code published=false}) from being rendered in live
 * to a caller who cannot write it, with the store's shared rule ({@link StoreVisibility}). The draft
 * answers exactly like a node that does not exist: a {@link PathNotFoundException}, which Jahia turns
 * into its 404 page. It used to redirect to the site home, which told an anonymous visitor that a draft
 * existed at that URL (JSTORE-SEC-005). The owner and administrators (jcr:write) still preview drafts.
 */
@Component(service = RenderFilter.class)
public class PublishedModuleFilter extends AbstractFilter {

    @Activate
    public void activate() {
        setPriority(20);
        setApplyOnModes("live");
        // Gate on the shared jmix:forgeElement mixin (carried by jnt:forgeModule AND jnt:forgePackage,
        // and inherited by any future forge element type) rather than a single concrete type. Binding
        // to jnt:forgeModule only left draft jnt:forgePackage detail pages fully readable to anonymous
        // visitors at their (predictable) direct URL (SECURITY-571 #54).
        setApplyOnNodeTypes("jmix:forgeElement");
    }

    @Override
    public String prepare(RenderContext renderContext, Resource resource, RenderChain chain) throws Exception {
        JCRNodeWrapper node = resource.getNode();
        if (!StoreVisibility.isVisibleToCaller(node)) {
            // The message mirrors the one Jahia's render servlet gives a missing node; the 404 page never shows it.
            throw new PathNotFoundException("'" + node.getPath() + "' not found");
        }
        return super.prepare(renderContext, resource, chain);
    }
}
