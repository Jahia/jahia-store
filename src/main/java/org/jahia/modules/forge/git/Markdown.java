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

import java.util.Map;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Renders the Markdown of a repository to the HTML that a richtext property holds. Raw HTML in
 * the Markdown is escaped: the text comes from a contributor and the storefront renders it.
 */
final class Markdown {

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().escapeHtml(true).build();

    private Markdown() {
    }

    static String toHtml(String markdown) {
        return toHtml(markdown, null);
    }

    /**
     * @param baseUrl the URL a relative link or image is resolved against, with its trailing
     *                slash, or null to leave relative links as they are
     */
    static String toHtml(String markdown, String baseUrl) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        Node document = PARSER.parse(markdown);
        HtmlRenderer renderer = baseUrl == null ? RENDERER
                : HtmlRenderer.builder().escapeHtml(true).attributeProviderFactory(context -> new RelativeLinks(baseUrl)).build();
        return renderer.render(document).trim();
    }

    /** Prefixes a relative {@code href} or {@code src} with the base URL. An absolute or anchor link stays. */
    private static final class RelativeLinks implements AttributeProvider {
        private final String baseUrl;

        private RelativeLinks(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            String attribute = node instanceof Image ? "src" : node instanceof Link ? "href" : null;
            if (attribute == null) {
                return;
            }
            String value = attributes.get(attribute);
            if (value != null && isRelative(value)) {
                attributes.put(attribute, baseUrl + (value.startsWith("./") ? value.substring(2) : value));
            }
        }

        private static boolean isRelative(String url) {
            return !url.isEmpty() && !url.startsWith("/") && !url.startsWith("#") && !url.contains(":") && !url.startsWith("..");
        }
    }
}
