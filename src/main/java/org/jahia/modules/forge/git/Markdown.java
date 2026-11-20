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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Renders the Markdown of a repository to the HTML that a richtext property holds. Raw HTML in
 * the Markdown is escaped: the text comes from a contributor and the storefront renders it. A
 * relative image or link names a file of the repository, which the store serves beside the
 * node under its base name.
 */
final class Markdown {

    /** A bare URL in the text, such as the compare link of a GitHub release, is a link. */
    private static final List<Extension> EXTENSIONS = Collections.singletonList(AutolinkExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).build();

    private Markdown() {
    }

    static String toHtml(String markdown) {
        return toHtml(markdown, null);
    }

    /**
     * @param baseUrl the URL under which the files are served, with its trailing slash, which a
     *                relative image or link is resolved against by its base name; or null to
     *                leave relative links as they are
     */
    static String toHtml(String markdown, String baseUrl) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        Node document = PARSER.parse(markdown);
        HtmlRenderer renderer = baseUrl == null ? RENDERER
                : HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true)
                        .attributeProviderFactory(context -> new RelativeLinks(baseUrl)).build();
        return renderer.render(document).trim();
    }

    /** The relative images and links of the Markdown, as written, in the order of the text, without duplicates. */
    static List<String> relativeReferences(String markdown) {
        List<String> references = new ArrayList<>();
        if (markdown == null || markdown.trim().isEmpty()) {
            return references;
        }
        PARSER.parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                add(image.getDestination());
                super.visit(image);
            }

            @Override
            public void visit(Link link) {
                add(link.getDestination());
                super.visit(link);
            }

            private void add(String destination) {
                if (destination != null && isRelative(destination) && !references.contains(destination)) {
                    references.add(destination);
                }
            }
        });
        return references;
    }

    /** A path of the repository: not absolute, not an anchor, not a URL with a scheme, not climbing out. */
    static boolean isRelative(String url) {
        return !url.isEmpty() && !url.startsWith("/") && !url.startsWith("#") && !url.contains(":")
                && !url.startsWith("..") && !url.contains("/../");
    }

    /** The base name of a relative reference, which is the name the store serves the file under. */
    static String baseName(String reference) {
        return StringUtils.substringAfterLast("/" + StringUtils.removeEnd(reference, "/"), "/");
    }

    /** Points a relative {@code href} or {@code src} at the served file. An absolute or anchor link stays. */
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
                attributes.put(attribute, baseUrl + baseName(value));
            }
        }
    }
}
