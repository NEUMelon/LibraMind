'use strict';

// 原生验证界面：所有数据和权限来自后端，不在浏览器模拟业务成功。
const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const state = {user: null, csrf: '', page: 1, q: '', route: 'books', conversation: null, searchMode: 'keyword'};
const statusNames = {AVAILABLE:'可借',BORROWED:'在借',MAINTENANCE:'维护中',OFF_SHELF:'已下架',ACTIVE:'在借',RETURNED:'已归还',CANCELLED:'已作废'};
const admin = () => state.user?.role === 'ADMIN';
const date = value => value ? new Date(value).toLocaleString('zh-CN', {hour12:false}) : '—';
const badge = value => `<span class="state ${['BORROWED','ACTIVE','MAINTENANCE'].includes(value)?'warning':['OFF_SHELF','CANCELLED','停用'].includes(value)?'off':''}">${esc(statusNames[value] || value)}</span>`;
const action = (name, id, label, danger = false) => `<button type="button" class="link ${danger?'danger':''}" data-action="${name}" data-id="${esc(id)}">${label}</button>`;

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (options.body && !(options.body instanceof FormData)) {
        headers.set('Content-Type', 'application/json'); options.body = JSON.stringify(options.body);
    }
    if (options.method==='POST' && dialog.open && state.dialogKeys && /^\/(loans|returns|loans\/\d+\/renew|import\/books\/execute)$/.test(path)) {
        // 同一表单的同一输入重试沿用请求键；改输入才创建新意图。
        const signature=path+'\n'+options.body;
        if(!state.dialogKeys.has(signature))state.dialogKeys.set(signature,crypto.randomUUID());
        headers.set('Idempotency-Key',state.dialogKeys.get(signature));
    }
    if (options.method && options.method !== 'GET') headers.set('X-CSRF-TOKEN', state.csrf);
    const response = await fetch('/api/v1' + path, {...options, headers, credentials:'same-origin'});
    const payload = await response.json();
    if (!response.ok || !payload.success) {
        if (response.status === 401) { state.user = null; navigation(); }
        const error = new Error(payload.message || '请求未完成'); error.code = payload.code; error.details = payload.details; throw error;
    }
    return payload.data;
}

function notice(message, error = false) {
    const box = $('#notice'); box.textContent = message; box.className = error ? 'error' : ''; box.hidden = !message;
}

async function safe(operation) {
    try { return await operation(); } catch (error) { notice(error.message, true); }
}

function navigation() {
    const items = [['books','馆藏查询']];
    if(!admin())items.push(['shelf','我的借阅'],['history','借阅历史']);
    items.push(['assistant','AI 助手'],['recognize','扫码与识书']);
    if (admin()) items.push(['desk','流通台'],['dashboard','管理概况'],['students','学生管理'],['editions','图书管理'],['copies','馆藏副本'],['loans','借书记录'],['returns','还书记录'],['knowledge','知识资料'],['audit','审计与运行']);
    $('#navigation').innerHTML = items.map(([id, label]) => `<a href="#${id}" ${state.route===id?'class="active" aria-current="page"':''}>${label}</a>`).join('');
    $('#identity').innerHTML = state.user ? `<span>${esc(state.user.username)} / ${admin()?'管理员':'学生'}</span><button id="logoutButton">退出</button>` : '<button id="loginButton">登录</button>';
    $('#loginButton')?.addEventListener('click', login);
    $('#logoutButton')?.addEventListener('click', () => safe(async () => {
        await api('/auth/logout', {method:'POST'}); state.user=null; state.conversation=null;
        state.csrf=(await api('/auth/csrf')).token; location.hash='books'; await render();
    }));
}

const dialog = $('#dialog');
$('#closeDialog').onclick = $('#cancelDialog').onclick = () => dialog.close();
function showDialog(title, body, submit = null, saveLabel = '保存') {
    state.dialogKeys=new Map();
    $('#dialogTitle').textContent = title; $('#dialogBody').innerHTML = body;
    $('#dialogError').hidden = true; $('#saveDialog').textContent = saveLabel;
    $('#dialogFooter').hidden = !submit;
    $('#dialogForm').onsubmit = async event => {
        event.preventDefault(); const button=$('#saveDialog'); button.disabled=true;
        try { await submit(new FormData(event.target)); dialog.close(); }
        catch (error) { const box=$('#dialogError'); box.textContent=error.message+(error.details?'：'+Object.values(error.details).join('；'):''); box.hidden=false; box.focus(); }
        finally { button.disabled=false; }
    };
    if (!dialog.open) dialog.showModal();
}

function fields(definitions, value = {}) {
    return '<div class="form-grid">' + definitions.map(([name,label,type='text',required=false]) => {
        let control;
        if (Array.isArray(type)) control = `<select name="${name}">${type.map(([key,text])=>`<option value="${esc(key)}" ${String(value[name])===String(key)?'selected':''}>${esc(text)}</option>`).join('')}</select>`;
        else if (type==='textarea') control=`<textarea name="${name}" ${required?'required':''}>${esc(value[name])}</textarea>`;
        else control=`<input name="${name}" type="${type}" value="${esc(value[name])}" ${required?'required':''} ${type==='password'?'autocomplete="current-password"':''}>`;
        return `<label class="field ${type==='textarea'?'wide':''}"><span>${esc(label)}${required?' *':''}</span>${control}</label>`;
    }).join('') + '</div>';
}

function login() {
    showDialog('登录', fields([['username','用户名','text',true],['password','密码','password',true]]), async form => {
        state.csrf=(await api('/auth/csrf')).token;
        state.user=await api('/auth/login',{method:'POST',body:Object.fromEntries(form)});
        state.csrf=(await api('/auth/csrf')).token; navigation();
        if(!location.hash || location.hash==='#books'){location.hash=admin()?'desk':'shelf';}else await render();
    }, '登录');
}

function toolbar(title, extra = '') {
    return `<h1>${title}</h1><form class="toolbar" id="searchForm"><label><span class="subtle">查询条件</span><input id="query" aria-label="查询条件" placeholder="输入关键词" value="${esc(state.q)}"></label><button class="primary">查询</button><button type="button" id="resetSearch">重置</button><div class="right">${extra}</div></form>`;
}

function table(headers, rows) {
    return `<div class="table-wrap"><table><thead><tr>${headers.map(h=>`<th scope="col">${h}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.join(''):`<tr><td colspan="${headers.length}" class="empty">没有找到记录，请调整查询条件。</td></tr>`}</tbody></table></div>`;
}

function paging(page) {
    return `<div class="pagination"><span>共 ${page.totalElements} 条记录</span><div><button data-page="${page.page-1}" ${page.page<=1?'disabled':''}>上一页</button><span>第 ${page.page} / ${Math.max(1,page.totalPages)} 页</span><button data-page="${page.page+1}" ${page.page>=page.totalPages?'disabled':''}>下一页</button></div></div>`;
}

function attachList() {
    $('#searchForm')?.addEventListener('submit', event => {event.preventDefault(); state.q=$('#query').value.trim(); state.searchMode=$('#searchMode')?.value||'keyword'; state.page=1; safe(render);});
    $('#resetSearch')?.addEventListener('click',()=>{state.q='';state.page=1;safe(render);});
    document.querySelectorAll('[data-page]').forEach(button=>button.onclick=()=>{state.page=Number(button.dataset.page);safe(render);});
}

async function render() {
    state.route = location.hash.slice(1) || 'books'; navigation(); notice('');
    const content = $('#content');
    if (!['books','editions'].includes(state.route) && !state.user) {
        content.innerHTML='<h1>请先登录</h1><div class="panel"><p>登录后查看本人借阅、办理借还书或使用 AI 助手。</p><button id="pageLogin" class="primary">登录</button></div>';
        $('#pageLogin').onclick=login; return;
    }
    content.innerHTML='<p role="status">正在加载…</p>';
    if (['books','editions','copies','students','loans','returns','shelf','history'].includes(state.route)) await listPage();
    else if (state.route==='desk') circulationDesk();
    else if (state.route==='dashboard') await dashboard();
    else if (state.route==='assistant') await assistant();
    else if (state.route==='recognize') recognize();
    else if (state.route==='knowledge') await knowledge();
    else if (state.route==='audit') await audit();
    else { location.hash='books'; }
    attachList();
}

async function listPage() {
    const route=state.route, isBook=['books','editions'].includes(route), isLoan=['loans','returns','shelf','history'].includes(route);
    let path=isBook?'books':route==='shelf'?'me/loans':route==='history'?'me/loan-history':route;
    let data,degraded=false;
    if(route==='books'&&state.q&&state.searchMode!=='keyword'){
        const found=await api('/search/'+(state.searchMode==='vector'?'semantic':'hybrid'),{method:'POST',body:{query:state.q,limit:20,availableOnly:false}});
        data={items:found.hits.map(hit=>hit.book),page:1,totalPages:1,totalElements:found.hits.length};degraded=found.degraded.length>0;
    }else data=await api(`/${path}?q=${encodeURIComponent(state.q)}&page=${state.page}&size=20`);
    let title,headers,rows,extra='';
    if (isBook) {
        title=route==='books'?'馆藏查询':'图书管理'; headers=['书名 / ISBN','作者','出版社 / 版本','可借 / 馆藏','操作'];
        if(admin()) extra='<button type="button" class="primary" data-action="edit-book">新增版本</button>';
        rows=data.items.map(b=>`<tr><td class="wrap"><strong>${esc(b.title)}</strong><div class="subtle">${esc(b.isbn13 || 'ISBN未录入')}</div></td><td class="wrap">${esc(b.authors)}</td><td class="wrap">${esc(b.publisher)}<div class="subtle">${esc(b.editionStatement)}</div></td><td>${b.availableCount} / ${b.copyCount}</td><td>${action('book-detail',b.id,'详情')}${admin()?action('edit-book',b.id,'编辑')+action('delete-book',b.id,'删除',true):''}</td></tr>`);
    } else if(route==='students') {
        title='学生管理';headers=['学号','姓名','学院 / 专业','已借数量','状态','操作'];extra='<button class="primary" type="button" data-action="edit-student">新增学生</button>';
        rows=data.items.map(s=>`<tr><td>${esc(s.studentNo)}</td><td>${esc(s.name)}</td><td>${esc(s.college)}<div class="subtle">${esc(s.major)}</div></td><td>${s.currentBorrowedCount}</td><td>${badge(s.enabled?'启用':'停用')}</td><td>${action('edit-student',s.id,'编辑')}${action('account',s.id,'开通账号')}${action('delete-student',s.id,'删除',true)}</td></tr>`);
    } else if(route==='copies') {
        title='馆藏副本';headers=['选择','馆藏登录号','书名 / 版本','位置 / 索书号','状态','操作'];extra='<button type="button" data-action="print-labels">打印所选标签</button> <button type="button" class="primary" data-action="edit-copy">新增副本</button>';
        rows=data.items.map(c=>`<tr><td><input type="checkbox" class="copy-check" value="${c.id}" aria-label="选择 ${esc(c.accessionNo)}"></td><td>${esc(c.accessionNo)}</td><td class="wrap">${esc(c.title)}<div class="subtle">${esc(c.editionStatement)}</div></td><td>${esc(c.location)}<div class="subtle">${esc(c.callNumber)}</div></td><td>${badge(c.circulationStatus)}</td><td>${action('edit-copy',c.id,'编辑')}${action('barcode',c.id,'条码')}${action('delete-copy',c.id,'删除',true)}</td></tr>`);
    } else if(isLoan) {
        title={loans:'借书记录',returns:'还书记录',shelf:'我的借阅',history:'借阅历史'}[route];headers=['编号','学生','图书 / 馆藏登录号','借阅 / 应还日期','归还日期','状态','操作'];
        extra='<button type="button" data-action="return-form">登记归还</button> <button class="primary" type="button" data-action="borrow-form">登记借阅</button>';
        rows=data.items.map(l=>`<tr><td>${l.id}</td><td>${esc(l.studentName)}<div class="subtle">${esc(l.studentNo)}</div></td><td class="wrap">${esc(l.title)}<div class="subtle">${esc(l.accessionNo)}</div></td><td>${date(l.borrowedAt)}<div class="subtle">应还 ${date(l.dueAt)}${l.overdue?' / 已逾期':''}</div></td><td>${date(l.returnedAt)}</td><td>${badge(l.status)}</td><td>${l.status==='ACTIVE'?action('return',l.id,'归还')+action('renew',l.id,'续借'):''}${admin()&&l.status!=='CANCELLED'?action('correct',l.id,'更正')+action(l.status==='RETURNED'?'undo-return':'cancel-loan',l.id,l.status==='RETURNED'?'撤销归还':'作废',true):''}</td></tr>`);
    }
    $('#content').innerHTML=toolbar(title,extra)+(degraded?'<p role="status">向量暂不可用，已退回关键词检索。</p>':'')+table(headers,rows)+paging(data);
    if(route==='books'){$('#searchForm').insertAdjacentHTML('beforeend','<label style="min-width:130px;max-width:160px">检索方式<select id="searchMode"><option value="keyword">关键词</option><option value="vector">语义（前20条）</option><option value="hybrid">混合（前20条）</option></select></label>');$('#searchMode').value=state.searchMode;}
    if (route==='shelf') {
        const eligibility=await api('/me/borrowing-eligibility');
        $('#content').insertAdjacentHTML('afterbegin',`<p>当前在借 ${eligibility.currentBorrowedCount} 册，上限 ${eligibility.limit} 册，剩余额度 ${eligibility.remaining} 册。</p>`);
    }
}

const bookFields=[['title','书名','text',true],['authors','作者','text',true],['isbn13','ISBN'],['publisher','出版社'],['publicationDate','出版日期文本'],['editionStatement','版本说明'],['translators','译者'],['language','语言'],['volume','卷次'],['category','分类'],['classificationNo','分类号'],['sourceUrl','来源网址'],['description','简介','textarea']];
async function editBook(id) {
    const value=id?await api('/books/'+id):{language:'zh'};
    showDialog(id?'编辑出版版本':'新增出版版本',fields(bookFields,value),async form=>{
        const input={...Object.fromEntries(form),workId:value.workId||null,subtitle:value.subtitle||''};
        await api('/books'+(id?'/'+id:''),{method:id?'PATCH':'POST',body:input});await render();notice('图书资料已保存');
    });
}

async function bookDetail(id) {
    const book=await api('/books/'+id),copies=await api(`/books/${id}/copies?size=30`);
    showDialog('图书详情',`<div class="book-detail"><img src="${esc(book.coverUrl)}" alt="${esc(book.title)}封面" onerror="this.onerror=null;this.src='/assets/cover-missing.svg'"><div><h2>${esc(book.title)}</h2><dl><dt>版本编号</dt><dd>${book.id}</dd><dt>作者</dt><dd>${esc(book.authors)}</dd><dt>出版社</dt><dd>${esc(book.publisher)}</dd><dt>版本</dt><dd>${esc(book.editionStatement)}</dd><dt>ISBN</dt><dd>${esc(book.isbn13)}</dd><dt>可借</dt><dd>${book.availableCount} / ${book.copyCount} 册</dd></dl></div></div>${book.description?`<h3>简介</h3><p>${esc(book.description)}</p>`:''}<h3>馆藏副本</h3>${table(['登录号','位置','状态','操作'],copies.items.map(c=>`<tr><td>${esc(c.accessionNo)}</td><td>${esc(c.location)}</td><td>${badge(c.circulationStatus)}</td><td>${state.user&&c.circulationStatus==='AVAILABLE'?action('borrow-copy',c.accessionNo,'借阅'):''}${admin()?action('edit-copy',c.id,'编辑'):''}</td></tr>`))}${admin()?`<div class="actions"><button type="button" data-action="new-book-copy" data-id="${id}">新增副本</button><button type="button" data-action="group-book" data-id="${id}">调整作品分组</button><label>上传封面 <input id="coverFile" type="file" accept="image/jpeg,image/png,image/webp"></label></div>`:''}`);
    $('#coverFile')?.addEventListener('change',()=>safe(async()=>{const form=new FormData();form.append('file',$('#coverFile').files[0]);await api(`/books/${id}/cover`,{method:'POST',body:form});await bookDetail(id);}));
}

async function editStudent(id) {
    const value=id?await api('/students/'+id):{enabled:true};
    showDialog(id?'编辑学生':'新增学生',fields([['studentNo','学号','text',true],['name','姓名','text',true],['college','学院','text',true],['major','专业','text',true],['enabled','状态',[['true','启用'],['false','停用']]]],value),async form=>{
        const input=Object.fromEntries(form);input.enabled=input.enabled==='true';
        await api('/students'+(id?'/'+id:''),{method:id?'PATCH':'POST',body:input});await render();notice('学生资料已保存');
    });
}

async function editCopy(id, editionId) {
    const value=id?await api('/copies/'+id):{editionId:editionId||'',circulationStatus:'AVAILABLE'};
    const options=['AVAILABLE','MAINTENANCE','OFF_SHELF'];if(value.circulationStatus==='BORROWED')options.push('BORROWED');
    showDialog(id?'编辑馆藏副本':'新增馆藏副本',fields([['accessionNo','馆藏登录号','text',true],['editionId','所属版本编号','number',true],['callNumber','索书号'],['location','位置'],['circulationStatus','状态',options.map(v=>[v,statusNames[v]])]],value),async form=>{
        const input=Object.fromEntries(form);input.editionId=Number(input.editionId);
        await api('/copies'+(id?'/'+id:''),{method:id?'PATCH':'POST',body:input});await render();notice('馆藏副本已保存');
    });
}

async function borrowForm(accessionNo='') {
    const definitions=admin()?[['studentNo','学生学号','text',true]]:[];
    definitions.push(['accessionNos','馆藏登录号（批量用逗号或换行分隔）','textarea',true],['note','备注']);
    showDialog('登记借阅',fields(definitions,{accessionNos:accessionNo}),async form=>{
        const value=Object.fromEntries(form);let studentId=null;
        if(admin()) {const matches=await api('/students?q='+encodeURIComponent(value.studentNo)+'&size=20');const student=matches.items.find(s=>s.studentNo===value.studentNo);if(!student)throw new Error('没有找到这个学号，请先维护学生资料');studentId=student.id;}
        await api('/loans',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:{studentId,accessionNos:value.accessionNos.split(/[,，\s]+/).filter(Boolean),note:value.note}});await render();notice('借阅已登记');
    },'登记借阅');
}

function returnForm() {
    showDialog('登记归还',fields([['accessionNo','馆藏登录号','text',true]]),async form=>{
        await api('/returns',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:{accessionNo:form.get('accessionNo')}});await render();notice('归还已登记');
    },'登记归还');
}

function reasonDialog(title, operation) {
    showDialog(title,fields([['reason','原因','textarea',true]]),async form=>{await operation(form.get('reason'));await render();notice('操作已完成');},title);
}

async function correctLoan(id) {
    const loan=await api('/loans/'+id);
    const local=value=>{if(!value)return '';const d=new Date(value);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);};
    const definitions=[['borrowedAt','借阅日期','datetime-local'],['dueAt','应还日期','datetime-local']];
    if(loan.status==='RETURNED')definitions.push(['returnedAt','归还日期','datetime-local']);
    definitions.push(['note','备注','textarea'],['reason','更正原因','text',true]);
    showDialog('更正借阅记录',fields(definitions,{...loan,borrowedAt:local(loan.borrowedAt),dueAt:local(loan.dueAt),returnedAt:local(loan.returnedAt)}),async form=>{
        const value=Object.fromEntries(form);for(const key of ['borrowedAt','dueAt','returnedAt'])if(value[key])value[key]=new Date(value[key]).toISOString();
        await api('/loans/'+id,{method:'PATCH',body:value});await render();notice('记录已更正');
    });
}

async function dashboard() {
    const stats=await api('/dashboard/overview');
    $('#content').innerHTML='<h1>管理概况</h1><div class="metrics">'+[['学生',stats.students],['出版版本',stats.editions],['馆藏副本',stats.copies],['当前可借',stats.available],['当前在借',stats.activeLoans],['逾期',stats.overdueLoans]].map(([label,n])=>`<div>${label}<strong>${n}</strong></div>`).join('')+'</div><div class="panel"><h2>流通办理</h2><div class="actions"><button class="primary" data-action="borrow-form">登记借阅</button><button data-action="return-form">登记归还</button><button data-action="edit-student">新增学生</button><button data-action="edit-book">新增图书</button></div></div>';
    const recent=await api('/loans?size=8');
    $('#content').insertAdjacentHTML('beforeend','<h2>最近借阅</h2>'+table(['编号','学生','图书','应还日期','状态'],recent.items.map(l=>`<tr><td>${l.id}</td><td>${esc(l.studentName)}</td><td class="wrap">${esc(l.title)}</td><td>${date(l.dueAt)}</td><td>${badge(l.status)}</td></tr>`)));
}

function circulationDesk() {
    if(!admin())throw new Error('流通台需要管理员权限');
    let student=null,cart=[],batchKey=crypto.randomUUID();
    $('#content').innerHTML='<h1>流通台</h1><div class="panel"><div class="toolbar"><label>办理方式<select id="deskMode"><option value="borrow">借书</option><option value="return">还书</option></select></label></div><form id="readerForm" class="toolbar"><label>学生学号<input id="readerNumber" inputmode="numeric" autocomplete="off" placeholder="输入或扫描学号，回车查询" required></label><button>查询学生</button></form><div id="readerInfo" role="status"></div></div><div class="panel"><form id="scannerForm" class="toolbar"><label>馆藏登录号<input id="scannerNumber" autocomplete="off" placeholder="扫描或输入登录号，回车处理" required></label><button class="primary">处理这一本</button></form><div id="scanResults"></div><div class="actions"><button id="commitBatch" class="primary" disabled>确认借阅本批次</button><button id="clearBatch">清空待办</button></div></div>';
    const readerInfo=$('#readerInfo'),results=$('#scanResults'),commit=$('#commitBatch');
    async function loadReader(){student=null;cart=[];batchKey=crypto.randomUUID();draw();readerInfo.textContent='正在查询学生…';student=await api('/students/by-number/'+encodeURIComponent($('#readerNumber').value.trim()));const e=await api('/me/borrowing-eligibility?studentId='+student.id);readerInfo.textContent=`${student.name} / ${student.college} / ${student.major}；当前在借 ${e.currentBorrowedCount} / ${e.limit}，逾期 ${e.overdueCount}，${student.enabled?'启用':'停用'}`;}
    $('#readerForm').onsubmit=event=>{event.preventDefault();safe(loadReader);};
    function draw(){results.innerHTML=table(['登录号','图书','预检结果'],cart.map(c=>`<tr><td>${esc(c.accessionNo)}</td><td class="wrap">${esc(c.copy?.title||'—')}</td><td>${esc(c.reason)}</td></tr>`));commit.disabled=!cart.length||cart.some(c=>!c.available)||!student;}
    $('#clearBatch').onclick=()=>{cart=[];batchKey=crypto.randomUUID();draw();};
    $('#deskMode').onchange=()=>{student=null;cart=[];batchKey=crypto.randomUUID();draw();const returning=$('#deskMode').value==='return';$('#readerForm').hidden=returning;commit.hidden=returning;readerInfo.textContent=returning?'归还只需馆藏登录号，无需选择学生。':'';$('#scannerNumber').focus();};
    $('#scannerForm').onsubmit=event=>{event.preventDefault();safe(async()=>{const field=$('#scannerNumber'),code=field.value.trim();if(!code)return;const button=event.target.querySelector('button');button.disabled=true;
        try{if($('#deskMode').value==='return'){const returned=await api('/returns',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:{accessionNo:code}});results.insertAdjacentHTML('afterbegin',`<p>${esc(code)} / ${esc(returned.title)}：归还成功</p>`);}
        else{if(!student)throw new Error('请先查询学生');if(cart.length>=10)throw new Error('每批最多十册，请先提交本批次');const input={studentId:student.id,accessionNos:[...cart.map(c=>c.accessionNo),code]};const preview=await api('/loans/preview',{method:'POST',body:input});cart=preview.copies;batchKey=crypto.randomUUID();draw();commit.disabled=!preview.valid;}
        field.value='';field.focus();}finally{button.disabled=false;}
    });};
    commit.onclick=()=>safe(async()=>{commit.disabled=true;try{await api('/loans',{method:'POST',headers:{'Idempotency-Key':batchKey},body:{studentId:student.id,accessionNos:cart.map(c=>c.accessionNo),note:'流通台批量借阅'}});cart=[];batchKey=crypto.randomUUID();draw();await loadReader();notice('本批次借阅已登记');}catch(error){commit.disabled=false;throw error;}});
}

async function knowledge() {
    const documents=await api('/knowledge');
    $('#content').innerHTML='<h1>知识资料</h1><div class="toolbar"><button class="primary" data-action="new-knowledge">新增资料</button><button data-action="import-books">导入书目 JSON</button></div>'+table(['编号','标题','类型','可见范围','版本','操作'],documents.map(d=>`<tr><td>${d.id}</td><td class="wrap">${esc(d.title)}</td><td>${esc(d.documentType)}</td><td>${esc(d.visibility)}</td><td>${d.version}</td><td>${action('view-knowledge',d.id,'查看')}${d.documentType!=='BOOK_METADATA'?action('edit-knowledge',d.id,'编辑')+action('delete-knowledge',d.id,'删除',true):''}</td></tr>`));
}

async function editKnowledge(id) {
    const value=id?await api('/knowledge/'+id):{documentType:'MANUAL_NOTE',visibility:'PUBLIC'};
    const types=['DESCRIPTION','TABLE_OF_CONTENTS','PUBLIC_SUMMARY','MANUAL_NOTE','LIBRARY_POLICY'];
    showDialog(id?'编辑资料':'新增资料',fields([['editionId','版本编号（规则资料可留空）','number'],['title','标题','text',true],['documentType','类型',types.map(v=>[v,v])],['visibility','可见范围',[['PUBLIC','公开'],['ADMIN','仅管理员']]],['source','来源'],['sourceUrl','来源网址'],['content','资料正文','textarea',true]],value),async form=>{
        const input=Object.fromEntries(form);input.editionId=input.editionId?Number(input.editionId):null;
        await api('/knowledge'+(id?'/'+id:''),{method:id?'PATCH':'POST',body:input});await render();notice('资料已保存，向量将异步更新');
    });
}

async function audit() {
    const index=await api('/admin/search/index-status'),logs=await api('/audit-logs?page='+state.page+'&size=20');
    $('#content').innerHTML='<h1>审计与运行</h1><div class="panel"><h2>检索索引</h2><p>'+`已载入 ${index.loaded} 个向量；待处理 ${index.pending}；失败 ${index.failed}；过期 ${index.stale}。</p></div>`+table(['时间','操作','对象','操作人编号','原因'],logs.items.map(l=>`<tr><td>${date(l.createdAt)}</td><td>${esc(l.operationType)}</td><td>${esc(l.targetType)} #${l.targetId}</td><td>${l.operatorId}</td><td class="wrap">${esc(l.reason)}</td></tr>`))+paging(logs);
}

async function assistant() {
    const all=await api('/ai/conversations');
    if(!state.conversation || !all.some(c=>c.id===state.conversation))state.conversation=all[0]?.id||null;
    $('#content').innerHTML='<h1>AI 助手</h1><div class="toolbar"><label>会话 <select id="conversationSelect">'+all.map(c=>`<option value="${c.id}" ${c.id===state.conversation?'selected':''}>${esc(c.title)}</option>`).join('')+'</select></label><button id="newConversation">新会话</button><button id="archiveConversation">删除会话</button></div><p class="muted">查馆藏、问借阅规则、查看本人借阅。借还操作需要你点击确认卡。</p><div id="messages" class="messages" aria-live="polite"></div><form id="chatForm" class="chat-compose"><label><span class="subtle">消息</span><textarea id="chatInput" required maxlength="2000" placeholder="例如：找几本 Java 入门书，或者查一下我当前借了什么"></textarea></label><button class="primary" id="sendMessage">发送</button></form>';
    if(state.conversation) {
        for(const message of await api(`/ai/conversations/${state.conversation}/messages`))addMessage(message.role==='USER'?'user':'assistant',message.content);
        const savedActions=await api('/ai/actions?conversationId='+state.conversation);
        if(savedActions.length){const holder=addMessage('assistant','已保存的操作卡');for(const pending of savedActions)renderAction(holder,pending);}
    }
    $('#newConversation').onclick=()=>safe(async()=>{const c=await api('/ai/conversations',{method:'POST',body:{title:'新会话 '+new Date().toLocaleTimeString()}});state.conversation=c.id;await assistant();});
    $('#archiveConversation').onclick=()=>{if(state.conversation)reasonDialog('删除会话',async()=>{await api('/ai/conversations/'+state.conversation,{method:'DELETE'});state.conversation=null;});};
    $('#conversationSelect').onchange=event=>{state.conversation=Number(event.target.value);safe(assistant);};
    $('#chatForm').onsubmit=event=>{event.preventDefault();safe(sendMessage);};
}

function addMessage(role, text) {
    const node=document.createElement('div');node.className='message '+role;
    const label=document.createElement('div');label.className='role';label.textContent=role==='user'?'我':'LibraMind';
    const body=document.createElement('div');body.className='message-text';body.textContent=text;node.append(label,body);$('#messages').append(node);node.scrollIntoView({block:'nearest'});return node;
}

async function sendMessage() {
    const input=$('#chatInput'),button=$('#sendMessage'),text=input.value.trim();if(!text)return;
    button.disabled=true;input.disabled=true;input.value='';
    let output;
    try {
        if(!state.conversation)state.conversation=(await api('/ai/conversations',{method:'POST',body:{title:text.slice(0,60)}})).id;
        addMessage('user',text);output=addMessage('assistant','正在查询…');let content='',buffer='';
        const response=await fetch(`/api/v1/ai/conversations/${state.conversation}/stream`,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':state.csrf},body:JSON.stringify({content:text})});
        if(!response.ok){const e=await response.json();throw new Error(e.message||'请求失败');}
        const reader=response.body.getReader(),decoder=new TextDecoder();
        let completed=false;
        while(true){const {done,value}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true}).replace(/\r\n/g,'\n');let end;
            while((end=buffer.indexOf('\n\n'))>=0){const block=buffer.slice(0,end);buffer=buffer.slice(end+2);let event='message',data='';for(const line of block.split('\n')){if(line.startsWith('event:'))event=line.slice(6).trim();if(line.startsWith('data:'))data+=line.slice(5).trim();}if(!data)continue;const payload=JSON.parse(data);
                if(event==='delta'){content+=payload.text;output.querySelector('.message-text').textContent=content;}
                if(event==='tool'){let trace=output.querySelector('.tools');if(!trace){trace=document.createElement('div');trace.className='tools';output.append(trace);}trace.textContent+=' '+payload.name+(payload.status==='FAILED'?'（未完成）':'');}
                if(event==='result'){output.querySelector('.message-text').textContent=payload.content;for(const citation of payload.citations){const c=document.createElement('div');c.className='citation';c.textContent=`[${citation.id}] ${citation.title}：${citation.excerpt}`;output.append(c);}for(const pending of payload.actions)renderAction(output,pending);}
                if(event==='done')completed=true;
                if(event==='error')throw new Error(payload.message);
            }
        }
        if(!completed)throw new Error('连接已结束，但没有收到完整回答，请重新查询。');
    } catch(error){if(output)output.querySelector('.message-text').textContent+='\n本轮未完成：'+error.message;throw error;}
    finally{button.disabled=false;input.disabled=false;input.focus();}
}

function renderAction(parent, pending) {
    const card=document.createElement('div');card.className='action-card';
    const type={BORROW:'借阅',RENEW:'续借',RETURN:'归还'}[pending.type];
    const title=document.createElement('div');title.textContent=`待确认${type}：${pending.preview.title} / ${pending.preview.accessionNo}（${date(pending.expiresAt)}前有效）`;card.append(title);
    if(pending.status!=='PENDING'){title.textContent=type+'操作状态：'+pending.status+' / '+pending.preview.title;parent.append(card);return;}
    for(const [verb,label] of [['confirm','确认'+type],['cancel','取消']]){const button=document.createElement('button');button.textContent=label;button.onclick=()=>safe(async()=>{card.querySelectorAll('button').forEach(b=>b.disabled=true);try{const result=await api(`/ai/actions/${pending.id}/${verb}`,{method:'POST'});title.textContent=type+'操作状态：'+result.status+(result.failureCode?' / '+result.failureCode:'');}catch(error){title.textContent='结果暂时不明，请查询状态';const check=document.createElement('button');check.textContent='查询状态';check.onclick=()=>safe(async()=>{const latest=await api('/ai/actions/'+pending.id);title.textContent='操作状态：'+latest.status;});card.append(check);throw error;}});card.append(button);}
    parent.append(card);
}

function recognize() {
    $('#content').innerHTML='<h1>扫码与识书</h1><div class="panel"><form id="imageForm"><div class="toolbar"><label>识别方式<select id="imageMode"><option value="barcode">ISBN / 馆藏条码</option><option value="book-cover">封面找书</option>'+ (admin()?'<option value="copyright-page">版权页辅助编目</option>':'')+'</select></label><label>图片<input id="imageFile" type="file" accept="image/jpeg,image/png,image/webp" required></label><button class="primary">识别</button></div></form><p class="muted">条码在本地解码；封面和版权页会发送至配置的低价视觉模型。图片不默认保存。</p></div><div id="imageResult"></div>';
    $('#imageForm').onsubmit=event=>{event.preventDefault();safe(async()=>{
        const button=event.target.querySelector('button');button.disabled=true;
        try{const form=new FormData();form.append('file',$('#imageFile').files[0]);const mode=$('#imageMode').value,result=await api('/vision/'+mode,{method:'POST',body:form});
            if(mode==='barcode')$('#imageResult').innerHTML=`<div class="panel"><h2>${result.found?'馆藏匹配成功':'没有找到馆藏'}</h2><p>${esc(result.value)}</p>${result.book?action('book-detail',result.book.id,'查看图书'):''}</div>`;
            else {const fields=result.extracted;$('#imageResult').innerHTML=`<div class="panel"><h2>识别结果</h2><p>${esc(fields.title||'未识别到书名')} / ${esc(fields.authors||'作者未识别')}</p><p>ISBN：${esc(fields.isbn13||'未识别，请人工核对')}</p><p>${esc(result.warnings.join('；'))}</p>${result.candidates.flatMap(w=>w.editions).map(b=>action('book-detail',b.id,b.title+' / '+b.editionStatement)).join('')}</div>`;
                if(mode==='copyright-page'){const b=document.createElement('button');b.textContent='核对并确认编目';b.className='primary';b.onclick=()=>{showDialog('核对版权页字段',fieldsHtml(fields),async form=>{const input={...Object.fromEntries(form),workId:null,category:'',description:'',sourceUrl:'',classificationNo:form.get('classificationNo')||''};await api(`/vision/copyright-page/${result.recognitionId}/confirm`,{method:'POST',body:input});notice('已确认保存书目');},'确认保存');};$('#imageResult .panel').append(b);}
            }
        }finally{button.disabled=false;}
    });};
}
function fieldsHtml(value){return fields(bookFields,value);}

document.addEventListener('click',event=>{const button=event.target.closest('[data-action]');if(!button)return;safe(async()=>{
    const id=button.dataset.id, name=button.dataset.action;
    if(name==='edit-book')return editBook(id);
    if(name==='book-detail')return bookDetail(id);
    if(name==='edit-student')return editStudent(id);
    if(name==='edit-copy')return editCopy(id);
    if(name==='new-book-copy')return editCopy(null,id);
    if(name==='borrow-form')return borrowForm();
    if(name==='borrow-copy')return borrowForm(id);
    if(name==='return-form')return returnForm();
    if(name==='correct')return correctLoan(id);
    if(name==='print-labels'){
        const copyIds=[...document.querySelectorAll('.copy-check:checked')].map(c=>Number(c.value));if(!copyIds.length)throw new Error('请先勾选要打印的馆藏副本');
        const labels=await api('/copies/labels',{method:'POST',body:{copyIds}});
        showDialog('馆藏条码标签','<div class="label-sheet">'+labels.map(l=>`<section><strong>${esc(l.copy.title)}</strong><p>${esc(l.copy.accessionNo)}</p><img src="${esc(l.barcodeUrl)}" alt="${esc(l.copy.accessionNo)}条码"></section>`).join('')+'</div><button type="button" id="printLabels">打印</button>');$('#printLabels').onclick=()=>window.print();return;
    }
    if(name==='new-knowledge')return editKnowledge();
    if(name==='edit-knowledge')return editKnowledge(id);
    if(name==='view-knowledge'){const d=await api('/knowledge/'+id);return showDialog(d.title,`<p class="subtle">${esc(d.source)}</p><pre>${esc(d.content)}</pre>`);}
    if(name==='delete-knowledge')return reasonDialog('删除资料',reason=>api('/knowledge/'+id,{method:'DELETE',body:{reason}}));
    if(name==='account')return showDialog('开通学生账号',fields([['username','用户名','text',true],['password','初始密码（12至64位）','password',true]]),async form=>{await api(`/students/${id}/account`,{method:'POST',body:Object.fromEntries(form)});notice('学生账号已开通');});
    if(name==='delete-student')return reasonDialog('删除学生',reason=>api('/students/'+id,{method:'DELETE',body:{reason}}));
    if(name==='delete-book')return reasonDialog('删除出版版本',reason=>api('/books/'+id,{method:'DELETE',body:{reason}}));
    if(name==='delete-copy')return reasonDialog('删除副本',reason=>api('/copies/'+id,{method:'DELETE',body:{reason}}));
    if(name==='cancel-loan')return reasonDialog('作废借阅',reason=>api('/loans/'+id,{method:'DELETE',body:{reason}}));
    if(name==='undo-return')return reasonDialog('撤销归还',reason=>api('/returns/'+id,{method:'DELETE',body:{reason}}));
    if(name==='return'||name==='renew')return showDialog(name==='return'?'归还图书':'续借图书','<p>请核对借阅编号：'+esc(id)+'</p>',async()=>{await api(name==='return'?'/returns':`/loans/${id}/renew`,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:name==='return'?{loanId:Number(id)}:{}});await render();notice(name==='return'?'归还已登记':'续借已完成');},name==='return'?'确认归还':'确认续借');
    if(name==='barcode')return showDialog('馆藏条码',`<img style="width:100%;max-width:520px" src="/api/v1/copies/${esc(id)}/barcode" alt="馆藏副本条码"><div class="actions"><button type="button" id="printLabel">打印标签</button></div>`),$('#printLabel').onclick=()=>window.print();
    if(name==='group-book')return showDialog('调整作品分组',fields([['workId','目标作品编号（留空创建新分组）','number'],['reason','调整原因','text',true]]),async form=>{await api(`/editions/${id}/work`,{method:'PATCH',body:{workId:form.get('workId')?Number(form.get('workId')):null,reason:form.get('reason')}});await render();notice('作品分组已调整');});
    if(name==='import-books')return showDialog('导入书目 JSON','<label>选择符合接口格式的书目文件<input id="importFile" type="file" accept="application/json" required></label><p>可使用 data/demo/books-import.json。执行前会先完整校验。</p>',async()=>{const payload=JSON.parse(await $('#importFile').files[0].text());const preview=await api('/import/books/preview',{method:'POST',body:payload});if(preview.errors.length)throw new Error(preview.errors.map(e=>'第'+e.row+'行：'+e.message).join('；'));await api('/import/books/execute',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:payload});await render();notice('书目已导入');},'校验并导入');
});});

window.addEventListener('hashchange',()=>{if(location.hash==='#main'){$('#main').focus();return;}state.page=1;state.q='';safe(render);});
safe(async()=>{state.csrf=(await api('/auth/csrf')).token;try{state.user=await api('/auth/me');}catch(error){if(error.code!=='AUTHENTICATION_REQUIRED')throw error;}await render();});
