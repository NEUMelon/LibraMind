<%@ page pageEncoding="UTF-8" %>
<%@ include file="common/head.jspf" %>
<div class="form-page">
  <div class="page-heading"><h1>登记借阅</h1><a class="button secondary" href="${ctx}/app?module=loans">返回列表</a></div>
  <section class="panel form-panel">
    <div class="section-heading"><h2>借阅信息</h2><span class="muted">* 为必填项</span></div>
    <form autocomplete="off" method="post" action="${ctx}/app" class="record-form" id="borrow-form" data-lookup-url="${ctx}/lookup">
      <input type="hidden" name="module" value="loans"><input type="hidden" name="action" value="borrow"><input type="hidden" name="csrf" value="${sessionScope.csrf}">
      <div class="field">
        <label for="studentNo">学生学号 <em>*</em></label>
        <div class="lookup-input"><input spellcheck="false" inputmode="numeric" id="studentNo" name="studentNo" autocomplete="off" value="<c:out value='${v.studentNo}'/>" required maxlength="32" pattern="[0-9]{1,32}" placeholder="输入完整学号"><button type="button" class="button secondary" data-picker-open="student" aria-haspopup="dialog" aria-controls="lookup-dialog" hidden>选择学生</button></div>
        <small class="field-error" id="studentNo-error"><c:out value="${errors.studentNo}"/></small>
        <small id="student-preview" class="selection-preview" aria-live="polite"></small>
      </div>
      <div class="field">
        <label for="bookNo">馆藏书号 <em>*</em></label>
        <div class="lookup-input"><input spellcheck="false" id="bookNo" name="bookNo" autocomplete="off" value="<c:out value='${v.bookNo}'/>" required maxlength="40" pattern="[A-Za-z0-9_-]{1,40}" placeholder="输入完整馆藏书号"><button type="button" class="button secondary" data-picker-open="book" aria-haspopup="dialog" aria-controls="lookup-dialog" hidden>选择图书</button></div>
        <small class="field-error" id="bookNo-error"><c:out value="${errors.bookNo}"/></small>
        <small id="book-preview" class="selection-preview" aria-live="polite"></small>
      </div>
      <div class="field"><label for="at">借阅日期 <em>*</em></label><input type="datetime-local" id="at" name="at" value="<c:out value='${v.at}'/>" required><small>按实际借出时间填写。</small></div>
      <div class="field"><label for="reason">备注 <span class="muted">（选填）</span></label><textarea id="reason" name="reason" maxlength="300" rows="3" placeholder="补充本次借阅的说明"><c:out value="${v.reason}"/></textarea></div>
      <div class="form-actions"><a class="button secondary" href="${ctx}/app?module=loans">取消</a><button type="submit" class="button primary">登记借阅</button></div>
    </form>
  </section>
</div>
<dialog id="lookup-dialog" class="lookup-dialog" aria-labelledby="lookup-title">
  <div class="dialog-header"><h2 id="lookup-title">选择学生</h2><button type="button" class="dialog-close" data-picker-close aria-label="关闭选择窗口">×</button></div>
  <form id="lookup-search" class="lookup-search" role="search" method="get" action="${ctx}/lookup">
    <label for="lookup-query">查询条件</label><input id="lookup-query" name="q" maxlength="120" autocomplete="off" placeholder="学号 / 姓名 / 学院 / 专业"><button type="submit" class="button primary">查询</button><button type="button" class="button secondary" id="lookup-reset">重置</button>
  </form>
  <div id="lookup-message" class="lookup-message" role="status" aria-live="polite"></div>
  <div class="table-wrap lookup-results" role="region" aria-label="查询结果，可横向滚动" tabindex="0"><table><thead id="lookup-columns"></thead><tbody id="lookup-rows"></tbody></table></div>
  <div class="pagination lookup-footer"><span id="lookup-total"></span><nav id="lookup-pages" class="page-links" aria-label="选择结果分页"></nav></div>
</dialog>
<script defer src="${ctx}/assets/borrow-picker.js?v=14"></script>
<%@ include file="common/foot.jspf" %>
