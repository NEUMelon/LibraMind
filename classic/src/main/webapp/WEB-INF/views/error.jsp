<%@ page pageEncoding="UTF-8" %>
<%@ include file="common/head.jspf" %>
<section class="panel error-panel"><h1>未能完成操作</h1><p>请检查上方提示，返回后修改。</p><div class="error-actions"><button class="button primary" type="button" data-back>返回上一页</button><a class="button secondary" href="${ctx}/app?module=${empty module?'home':module}">回到列表</a></div></section>
<%@ include file="common/foot.jspf" %>
