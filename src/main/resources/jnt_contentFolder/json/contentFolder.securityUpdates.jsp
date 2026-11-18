<%@ page trimDirectiveWhitespaces="true" %>
<%@ page import="org.jahia.modules.forge.security.SecurityFeed" %>
<%@ page import="org.jahia.services.content.JCRNodeWrapper" %>
<%@ page import="org.slf4j.LoggerFactory" %>
<%--
  securityUpdates.json: the security feed read by Jahia instances to warn administrators about updates
  that fix security issues (schema in SecurityFeed). Served as
  /contents/modules-repository.securityUpdates.json, next to moduleList.json, which it does not change.
  Any failure answers 503 with an error object, never a partial or empty feed: an empty feed would tell
  every client "nothing to fix". The view is not cached (see the .properties file).
  The view is defined on every content folder, but the feed is served only at a site's modules-repository
  folder (JSTORE-SEC-005): anywhere else it answers 404, like a view that does not exist.
--%>
<%
    JCRNodeWrapper feedRoot = (JCRNodeWrapper) request.getAttribute("currentNode");
    if (!SecurityFeed.servesFeedAt(feedRoot)) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        out.print("{\"error\":\"not found\"}");
    } else {
        try {
            out.print(SecurityFeed.render(feedRoot));
        } catch (javax.jcr.RepositoryException | RuntimeException e) {
            LoggerFactory.getLogger(SecurityFeed.class).error("Cannot build the security feed", e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            out.print("{\"error\":\"security feed unavailable\"}");
        }
    }
%>
