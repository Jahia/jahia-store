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
--%>
<%
    try {
        out.print(SecurityFeed.render((JCRNodeWrapper) request.getAttribute("currentNode")));
    } catch (javax.jcr.RepositoryException | RuntimeException e) {
        LoggerFactory.getLogger(SecurityFeed.class).error("Cannot build the security feed", e);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        out.print("{\"error\":\"security feed unavailable\"}");
    }
%>
