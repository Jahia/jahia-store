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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang.StringUtils;

/**
 * Content-based (magic-byte) detector for the raster image types the store accepts as module
 * icons / screenshots. The upload path stores {@code jcr:mimeType} from the browser-supplied
 * {@code File.type}, which a caller can spoof by hitting the generic JCR GraphQL mutation directly
 * (SECURITY-571 #28). This class sniffs the actual leading bytes so a server-side check can reject
 * or correct a spoofed declaration instead of trusting the client.
 *
 * <p>SVG is deliberately NOT recognised: {@code image/svg+xml} can carry scripts and, when served
 * inline, would execute in the site origin — so anything that is not one of the allow-listed raster
 * signatures is treated as disallowed.</p>
 */
public final class MagicByteImageValidator {

    /** Canonical MIME types for the accepted raster formats. */
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String GIF = "image/gif";
    public static final String WEBP = "image/webp";

    /** The allow-list, lower-cased for case-insensitive comparison against a declared mime. */
    private static final Set<String> ALLOWED_RASTER_MIMES = Set.of(PNG, JPEG, GIF, WEBP);

    /** Enough leading bytes to identify every signature below (WEBP needs bytes 8-11 = "WEBP"). */
    public static final int SNIFF_LENGTH = 16;

    /**
     * Bytes to read for the markup check. Aligns with the ~512-byte window browsers use when
     * MIME-sniffing, so padding past it also defeats the browser's own sniff (bounded residual).
     */
    public static final int MARKUP_SNIFF_LENGTH = 512;

    /**
     * MIME types a browser will render as script-capable when served inline. A module-owned file
     * declared as one of these (or whose bytes sniff as markup) must never be served from the site
     * origin (SECURITY-571 #61). Lower-cased for case-insensitive comparison.
     */
    private static final Set<String> SCRIPT_CAPABLE_MIMES = Set.of(
            "image/svg+xml", "image/svg-xml", "text/html", "application/xhtml+xml",
            "text/xml", "application/xml");

    /** File extensions of SVG and HTML documents, lower-cased. */
    private static final Set<String> SVG_HTML_EXTENSIONS =
            Set.of("svg", "svgz", "html", "htm", "xhtml", "xht", "shtml");

    /** Lower-cased tokens that mark SVG or HTML content. */
    private static final List<String> SVG_HTML_TOKENS = List.of("<svg", "<!doctype html", "<html", "<script");

    private MagicByteImageValidator() {
        // utility class - prevent instantiation
    }

    /**
     * Detect the raster image type from the file's leading bytes.
     *
     * @param header the first bytes of the file (at least {@link #SNIFF_LENGTH} recommended)
     * @return the canonical MIME type ({@link #PNG}/{@link #JPEG}/{@link #GIF}/{@link #WEBP}), or
     *         {@code null} when the bytes match no allow-listed raster signature (incl. SVG, HTML,
     *         empty/short input).
     */
    public static String detectRasterMime(byte[] header) {
        if (header == null) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (startsWith(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        // JPEG: FF D8 FF
        if (startsWith(header, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        // GIF: "GIF8" (covers both GIF87a and GIF89a)
        if (startsWith(header, 0x47, 0x49, 0x46, 0x38)) {
            return GIF;
        }
        // WEBP: "RIFF" .... "WEBP" (bytes 0-3 = RIFF, bytes 8-11 = WEBP)
        if (isWebp(header)) {
            return WEBP;
        }
        return null;
    }

    /** True when {@code mime} is one of the accepted raster types (case-insensitive). */
    public static boolean isAllowedRasterMime(String mime) {
        return mime != null && ALLOWED_RASTER_MIMES.contains(mime.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * True when {@code mime} names a type a browser renders as script-capable inline (SVG/HTML/XML
     * family). Any {@code ;charset=...} parameter is ignored and comparison is case-insensitive.
     */
    public static boolean isScriptCapableMime(String mime) {
        if (mime == null) {
            return false;
        }
        final String base = StringUtils.substringBefore(mime, ";").trim().toLowerCase(Locale.ROOT);
        return SCRIPT_CAPABLE_MIMES.contains(base);
    }

    /**
     * True when the leading bytes look like an XML/HTML/SVG document — i.e. the first non-whitespace
     * byte (after an optional UTF-8 BOM) is {@code '<'}. This catches a file that lies about its
     * MIME type but whose bytes a browser could sniff and execute as markup (SECURITY-571 #61).
     */
    public static boolean looksLikeMarkup(byte[] header) {
        if (header == null) {
            return false;
        }
        int i = 0;
        // Skip a UTF-8 BOM (EF BB BF) if present.
        if (header.length >= 3 && (header[0] & 0xFF) == 0xEF && (header[1] & 0xFF) == 0xBB
                && (header[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        // Skip ASCII whitespace (space, tab, CR, LF, form-feed, vertical tab).
        while (i < header.length) {
            final int b = header[i] & 0xFF;
            if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0C || b == 0x0B) {
                i++;
            } else {
                break;
            }
        }
        return i < header.length && (header[i] & 0xFF) == 0x3C; // '<'
    }

    /**
     * True when {@code name} has an SVG or HTML file extension ({@code .svg}, {@code .svgz}, {@code .html},
     * {@code .htm}, {@code .xhtml}, {@code .xht}, {@code .shtml}), case-insensitively. SVG and HTML are never
     * accepted as module media (JSTORE-SEC-012), whatever their declared mime or bytes.
     */
    public static boolean isSvgOrHtmlName(String name) {
        if (name == null || name.indexOf('.') < 0) {
            return false;
        }
        return SVG_HTML_EXTENSIONS.contains(StringUtils.substringAfterLast(name, ".").toLowerCase(Locale.ROOT));
    }

    /**
     * True when the bytes contain an SVG or HTML token ({@code <svg}, {@code <!doctype html}, {@code <html},
     * {@code <script}) anywhere, case-insensitively, not only at the start: a file that starts with other
     * bytes (a raster header, text) and carries markup further on is refused too (JSTORE-SEC-012).
     */
    public static boolean containsSvgOrHtmlMarkup(byte[] header) {
        if (header == null) {
            return false;
        }
        // ISO-8859-1 maps every byte to one char, so the tokens are found whatever the encoding of the rest.
        final String text = new String(header, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        for (String token : SVG_HTML_TOKENS) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the bytes start with a zip ({@code PK 03 04}: JAR, WAR) or gzip ({@code 1F 8B}: tgz) signature.
     * A module artifact is compressed, but a stored (uncompressed) entry may hold HTML text: the markup token
     * sniff does not apply to it. Such a file is never rendered as markup by a browser.
     */
    public static boolean isArchive(byte[] header) {
        return header != null && (startsWith(header, 0x50, 0x4B, 0x03, 0x04) || startsWith(header, 0x1F, 0x8B));
    }

    private static boolean isWebp(byte[] header) {
        return startsWith(header, 0x52, 0x49, 0x46, 0x46) // "RIFF"
                && header.length >= 12
                && matchesAt(header, 8, 0x57, 0x45, 0x42, 0x50); // "WEBP"
    }

    private static boolean startsWith(byte[] data, int... signature) {
        return matchesAt(data, 0, signature);
    }

    private static boolean matchesAt(byte[] data, int offset, int... signature) {
        if (data.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
