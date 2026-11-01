<%@ page pageEncoding="UTF-8" %>
<%@ include file="common/head.jspf" %>
<c:url var="listUrl" value="/app"><c:param name="module" value="books"/><c:param name="q" value="${param.q}"/><c:param name="state" value="${param.state}"/><c:param name="page" value="${param.page}"/></c:url>
<div class="page-heading book-detail-heading"><h1>图书详情</h1><a class="button secondary" href="<c:out value='${listUrl}'/>">返回列表</a></div>
<section class="panel book-detail" aria-labelledby="book-detail-title">
  <div class="section-heading"><h2 id="book-detail-title"><c:out value="${book.title}"/></h2></div>
  <div class="book-detail-body">
    <figure class="book-cover">
      <img src="<c:out value='${coverUrl}'/>" width="240" height="330" alt="<c:out value='${hasCover ? book.title : "封面暂缺"}'/>${hasCover ? '的封面' : ''}" data-cover-fallback="${ctx}/assets/cover-missing.svg">
      <c:if test="${hasCover and not empty coverSourceUrl}"><figcaption><a href="<c:out value='${coverSourceUrl}'/>" target="_blank" rel="noopener noreferrer">封面来源：Open Library</a></figcaption></c:if>
    </figure>
    <div class="book-detail-info">
      <dl class="book-facts">
        <div><dt>馆藏书号</dt><dd class="mono"><c:out value="${book.bookNo}"/></dd></div>
        <div><dt>书名</dt><dd><c:out value="${book.title}"/></dd></div>
        <div><dt>作者</dt><dd><c:out value="${book.author}"/></dd></div>
        <div><dt>版本号</dt><dd><c:out value="${book.edition}"/></dd></div>
        <c:if test="${not empty isbn}"><div><dt>ISBN</dt><dd class="mono"><c:out value="${isbn}"/></dd></div></c:if>
        <div><dt>馆藏状态</dt><dd><span class="badge ${book.status.name()=='AVAILABLE'?'green':book.status.name()=='BORROWED'?'amber':'gray'}">${book.status.name()=='AVAILABLE'?'可借':book.status.name()=='BORROWED'?'在借':'已下架'}</span></dd></div>
      </dl>
      <div class="book-detail-actions">
        <c:if test="${book.status.name()=='AVAILABLE'}"><c:url var="borrowUrl" value="/app"><c:param name="module" value="loans"/><c:param name="action" value="new"/><c:param name="bookNo" value="${book.bookNo}"/></c:url><a class="button primary" href="<c:out value='${borrowUrl}'/>">登记借阅</a></c:if>
        <a class="button secondary" href="${ctx}/app?module=books&amp;action=edit&amp;id=${book.id}">编辑图书</a>
      </div>
    </div>
  </div>
</section>
<%@ include file="common/foot.jspf" %>
