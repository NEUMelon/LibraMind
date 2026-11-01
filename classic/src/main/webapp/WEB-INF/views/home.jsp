<%@ page pageEncoding="UTF-8" %>
<%@ include file="common/head.jspf" %>
<div class="page-heading"><h1>工作台</h1></div>
<section class="panel" aria-labelledby="system-summary">
  <div class="section-heading"><h2 id="system-summary">系统概况</h2></div>
  <dl class="summary-grid">
    <div title="已启用的学生资料"><dt>在册学生</dt><dd><a href="${ctx}/app?module=students&state=enabled">${stats.students}</a> 人</dd></div>
    <div title="可借与在借图书，不含已下架资料"><dt>在册图书</dt><dd><a href="${ctx}/app?module=books">${stats.books}</a> 册</dd></div>
    <div><dt>当前在借</dt><dd><a href="${ctx}/app?module=loans&state=ACTIVE">${stats.active}</a> 册</dd></div>
    <div><dt>当前可借</dt><dd><a href="${ctx}/app?module=books&state=AVAILABLE">${stats.available}</a> 册</dd></div>
  </dl>
</section>
<section class="panel" aria-labelledby="quick-title">
  <div class="section-heading"><h2 id="quick-title">快捷操作</h2></div>
  <div class="quick-actions">
    <a class="button primary" href="${ctx}/app?module=loans&action=new">登记借阅</a>
    <a class="button secondary" href="${ctx}/app?module=returns&action=new">登记归还</a>
    <a class="button secondary" href="${ctx}/app?module=students&action=new">新增学生</a>
    <a class="button secondary" href="${ctx}/app?module=books&action=new">新增图书</a>
  </div>
</section>
<section class="panel" aria-labelledby="recent-title">
  <div class="section-heading"><h2 id="recent-title">最近借阅记录</h2><a class="text-link" href="${ctx}/app?module=loans">查看全部</a></div>
  <div class="table-wrap" role="region" aria-label="最近借阅记录" tabindex="0">
    <table class="recent-table">
      <thead><tr><th scope="col">学号</th><th scope="col">姓名</th><th scope="col">书号</th><th scope="col">书名</th><th scope="col">借阅日期</th><th scope="col">状态</th></tr></thead>
      <tbody>
        <c:forEach items="${recent}" var="row"><tr><td class="mono nowrap"><c:out value="${row.studentNo}"/></td><td class="nowrap"><c:out value="${row.studentName}"/></td><td class="mono nowrap"><c:out value="${row.bookNo}"/></td><td class="title-cell"><c:out value="${row.bookTitle}"/></td><td class="date-cell"><c:out value="${row.borrowedText}"/></td><td><span class="badge ${row.status.name()=='ACTIVE'?'amber':'green'}">${row.status.name()=='ACTIVE'?'在借':'已归还'}</span></td></tr></c:forEach>
        <c:if test="${empty recent}"><tr><td colspan="6" class="empty">暂无借阅记录。<a href="${ctx}/app?module=loans&action=new">登记借阅</a></td></tr></c:if>
      </tbody>
    </table>
  </div>
</section>
<%@ include file="common/foot.jspf" %>