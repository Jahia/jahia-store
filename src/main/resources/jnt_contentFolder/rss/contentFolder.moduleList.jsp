<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="functions" uri="http://www.jahia.org/tags/functions" %>
<%@ taglib prefix="jcr" uri="http://www.jahia.org/tags/jcr" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ page import="org.jahia.modules.forge.visibility.StoreVisibility" %>
<%@ page import="org.jahia.services.content.JCRNodeWrapper" %>
<%-- An item is listed only when its version AND its module are published: the store's shared rule
     (StoreVisibility.isPublished), the rule of moduleList.json. Testing the version flag alone listed the
     published versions of unpublished modules, with their download links (JSTORE-SEC-005, SEC-1015).
     No owner exception: the feed is public and cached, it lists what everyone may see. --%>

<template:addCacheDependency flushOnPathMatchingRegexp="\Q${currentNode.path}\E/.*"/>
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
    <channel>
        <title>Jahia modules RSS feed</title>
        <link>${url.server}${url.context}</link>
        <c:set var="language" value="${currentResource.locale.language}"/>
        <language>${language}</language>
            <jsp:useBean id="now" class="java.util.Date"/>
        <lastBuildDate><fmt:formatDate value="${now}" pattern="EEE, dd MMM yyyy HH:mm:ss Z"/></lastBuildDate>
            <atom:link href="${url.server}${url.context}/feed.xml" rel="self" type="application/rss+xml"/>
        <description>New Jahia modules.</description>
            <jcr:jqom var="result" statement="SELECT * FROM [jnt:forgeModuleVersion] as module WHERE ISDESCENDANTNODE(module,'${currentNode.path}') ORDER BY module.[jcr:lastModified] DESC"/>
            <c:forEach items="${result.nodes}" var="version">
                <% pageContext.setAttribute("listed", StoreVisibility.isPublished((JCRNodeWrapper) pageContext.getAttribute("version"))); %>
                <c:if test="${listed}">
                    <c:set var="module" value="${version.parent}"/>
                    <c:set var="files" value="${jcr:getChildrenOfType(version, 'jnt:file')}"/>
                    <%-- Reset per version so an unsafe-coordinate skip below can't leak the previous
                         item's URL into this <item>. --%>
                    <c:set var="downloadUrl" value=""/>
                    <c:choose>
                        <c:when test="${not empty files}">
                            <c:forEach var="file" items="${files}">
                                <c:url var="downloadUrl" value="${url.server}${url.context}${url.files}${file.path}" context="/"/>
                            </c:forEach>
                        </c:when>
                        <c:otherwise>
                            <%-- No attached artifact: the module was deployed to the site's Maven
                                 repository, so the download is served by the MavenProxy servlet at
                                 /modules/mavenproxy. Generate the URL from the request server + the
                                 module coordinates (matching moduleList.json) rather than storing an
                                 absolute URL on the node.
                                 CANONICAL MAVENPROXY GRAMMAR (keep in sync — see the note in
                                 json/contentFolder.moduleList.jsp; MavenProxy.java parses it back):
                                 {server}{context}/modules/mavenproxy/{siteKey}/{groupId-as-path}/
                                 {name}/{version}/{name}-{version}.jar --%>
                            <c:set var="grp" value="${module.properties['groupId'].string}"/>
                            <c:set var="vNum" value="${version.properties.versionNumber.string}"/>
                            <%-- Skip the URL when a coordinate could deepen/traverse the proxy path
                                 (".." or "/"), mirroring SAFE_MAVEN_SEGMENT in versions.ts. --%>
                            <c:if test="${not (fn:contains(grp, '..') or fn:contains(grp, '/') or fn:contains(module.name, '..') or fn:contains(module.name, '/') or fn:contains(vNum, '..') or fn:contains(vNum, '/'))}">
                                <c:set var="downloadUrl" value="${url.server}${url.context}/modules/mavenproxy/${currentNode.resolveSite.siteKey}/${fn:replace(grp, '.', '/')}/${module.name}/${vNum}/${module.name}-${vNum}.jar"/>
                            </c:if>
                        </c:otherwise>
                    </c:choose>
                <item>
                    <title>${fn:escapeXml(version.name)}, ${fn:escapeXml(version.properties['versionNumber'].string)}</title>
                    <link>${fn:escapeXml(downloadUrl)}</link>
                    <guid>${fn:escapeXml(downloadUrl)}</guid>
                    <description>${fn:escapeXml(version.displayableName)}, version ${fn:escapeXml(version.properties['versionNumber'].string)}.</description>
                        <jcr:nodeProperty node="${version}" name="jcr:lastModified" var="modified"/>
                    <pubDate><fmt:formatDate value="${modified.time}" pattern="EEE, dd MMM yyyy HH:mm:ss Z" /></pubDate>
                </item>
            </c:if>
        </c:forEach>
    </channel>
</rss>