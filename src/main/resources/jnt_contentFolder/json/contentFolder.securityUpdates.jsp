<%@ page trimDirectiveWhitespaces="true" %>
<%@ page import="org.jahia.modules.forge.security.SecurityFeed" %>
<%@ page import="org.jahia.services.content.JCRNodeWrapper" %>
<%@ page import="org.slf4j.LoggerFactory" %>
<%--
  securityUpdates.json: the security feed read by Jahia instances to warn administrators about updates
  that fix security issues (schema in SecurityFeed). Served as
  /contents/modules-repository.securityUpdates.json, next to moduleList.json, which it does not change.
  A repository error answers 503: an empty feed would tell every client "nothing to fix".
--%>
<%
    try {
        out.print(SecurityFeed.render((JCRNodeWrapper) request.getAttribute("currentNode")));
    } catch (javax.jcr.RepositoryException e) {
        LoggerFactory.getLogger(SecurityFeed.class).error("Cannot build the security feed", e);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
%>
