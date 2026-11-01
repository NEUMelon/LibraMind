/* 借阅登记的分页选择器；只增强选择和核对，真正借出仍由原来的 POST 事务完成。 */
(() => {
  const form = document.getElementById('borrow-form');
  if (!form) return;
  const picker = document.getElementById('lookup-dialog');
  const query = document.getElementById('lookup-query');
  const rows = document.getElementById('lookup-rows');
  const columns = document.getElementById('lookup-columns');
  const message = document.getElementById('lookup-message');
  const total = document.getElementById('lookup-total');
  const pages = document.getElementById('lookup-pages');
  const settings = {
    student: { title: '选择学生', hint: '学号 / 姓名 / 学院 / 专业', headers: ['学号', '姓名', '学院', '专业'] },
    book: { title: '选择图书', hint: '书号 / 书名 / 作者', headers: ['书号', '书名', '作者', '版本号'] }
  };
  const checks = new Map();
  let kind = '', opener, focusAfterClose, searchRequest;

  /** 每次请求有超时；关闭弹窗或新搜索会中止旧请求，旧结果不能覆盖新条件。 */
  async function request(params, controller) {
    const url = new URL(form.dataset.lookupUrl, location.href);
    Object.entries(params).forEach(([key, value]) => url.searchParams.set(key, String(value)));
    const timer = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch(url, { signal: controller.signal, credentials: 'same-origin' });
      const data = await response.json();
      if (!response.ok) throw new Error(data.message || '查询失败，请重试。');
      return data;
    } catch (error) {
      if (error.name === 'AbortError') throw error;
      throw new Error('暂时无法查询，请稍后重试。');
    } finally { clearTimeout(timer); }
  }

  /** 记录已核对的编号和说明；输入被修改后立即撤销核对结果。 */
  function selectValue(type, code, label) {
    const input = document.getElementById(`${type}No`);
    input.value = code;
    input.dataset.choiceCode = code;
    input.dataset.choiceLabel = label;
    input.removeAttribute('aria-invalid');
    const preview = document.getElementById(`${type}-preview`);
    preview.dataset.state = 'valid';
    preview.textContent = `已选择：${label}`;
  }

  /** 完整编号精确核对，明确显示不存在或不可借；重复输入和异步旧结果不会串行。 */
  async function resolve(type, force = false) {
    const input = document.getElementById(`${type}No`);
    const preview = document.getElementById(`${type}-preview`);
    const code = input.value.trim();
    input.value = code;
    if (!force && input.dataset.choiceCode === code) return true;
    checks.get(type)?.abort();
    delete input.dataset.choiceCode;
    delete input.dataset.choiceLabel;
    if (!code) { preview.textContent = ''; return false; }
    if (!input.validity.valid) {
      preview.dataset.state = 'invalid';
      preview.textContent = type === 'student' ? '请输入完整的数字学号。' : '请输入完整馆藏书号。';
      return false;
    }
    const controller = new AbortController();
    checks.set(type, controller);
    preview.dataset.state = 'loading';
    preview.textContent = '正在核对…';
    try {
      const data = await request({ kind: type, code }, controller);
      if (checks.get(type) !== controller || input.value.trim() !== code) return false;
      if (data.found && data.eligible) { selectValue(type, code, data.label); return true; }
      preview.dataset.state = 'invalid';
      preview.textContent = [data.label, data.message].filter(Boolean).join('；');
      input.setAttribute('aria-invalid', 'true');
    } catch (error) {
      if (checks.get(type) !== controller || input.value.trim() !== code) return false;
      preview.dataset.state = 'invalid';
      preview.textContent = error.name === 'AbortError' ? '核对超时，请重试。' : error.message;
    }
    return false;
  }

  /** 为选择表生成一行；数据库文本只作为文字节点，不解析为 HTML。 */
  function renderRow(item) {
    const tr = document.createElement('tr');
    item.cells.forEach((value, index) => {
      const td = document.createElement('td');
      td.textContent = value;
      if (index === 0) td.className = 'mono nowrap';
      tr.append(td);
    });
    const td = document.createElement('td');
    const button = document.createElement('button');
    button.type = 'button';button.className = 'button small secondary';button.textContent = '选择';
    button.setAttribute('aria-label', `选择 ${item.code} ${item.cells[1]}`);
    button.addEventListener('click', () => {
      checks.get(kind)?.abort();
      checks.delete(kind);
      selectValue(kind, item.code, item.label);
      focusAfterClose = document.getElementById(`${kind}No`);
      picker.close();
    });
    td.append(button);tr.append(td);
    return tr;
  }

  /** 长结果集只显示附近页码和首末页，保持按钮数量有限。 */
  function renderPages(data) {
    pages.replaceChildren();
    const add = (label, page, disabled, current = false) => {
      const button = document.createElement('button');
      button.type = 'button';button.textContent = label;button.disabled = disabled;
      if (current) button.setAttribute('aria-current', 'page');
      if (/^\d+$/.test(label)) button.setAttribute('aria-label', `第${page}页`);
      button.addEventListener('click', () => search(page));
      pages.append(button);
    };
    add('上一页', data.page - 1, data.page <= 1);
    let previous = 0;
    for (let page = 1; page <= data.pages; page++) {
      if (page !== 1 && page !== data.pages && Math.abs(page - data.page) > 2) continue;
      if (previous && page - previous > 1) {
        const dots = document.createElement('span');dots.className = 'page-gap';dots.textContent = '…';pages.append(dots);
      }
      add(String(page), page, page === data.page, page === data.page);
      previous = page;
    }
    add('下一页', data.page + 1, data.page >= data.pages);
  }

  /** 从数据库按当前条件查询十条，不再依赖首次打开页面时的前 200 条数据。 */
  async function search(page = 1) {
    searchRequest?.abort();
    const controller = new AbortController();searchRequest = controller;
    rows.replaceChildren();pages.replaceChildren();total.textContent = '';
    message.textContent = '正在查询…';rows.setAttribute('aria-busy', 'true');
    try {
      const data = await request({ kind, q: query.value.trim(), page }, controller);
      if (searchRequest !== controller || !picker.open) return;
      rows.replaceChildren(...data.items.map(renderRow));
      if (!data.items.length) {
        const tr = document.createElement('tr'), td = document.createElement('td');
        td.colSpan = 5;td.className = 'lookup-empty';td.textContent = '没有匹配记录，请调整查询条件。';tr.append(td);rows.append(tr);
      }
      message.textContent = kind === 'student' ? '仅显示已启用学生。' : '仅显示当前可借图书。';
      total.textContent = `共 ${data.total} 条记录`;
      renderPages(data);
      document.querySelector('.lookup-results').scrollTop = 0;
    } catch (error) {
      if (searchRequest === controller && picker.open) message.textContent = error.name === 'AbortError' ? '查询超时，请重试。' : error.message;
    } finally {
      if (searchRequest === controller) rows.removeAttribute('aria-busy');
    }
  }

  document.querySelectorAll('[data-picker-open]').forEach(button => {
    button.hidden = false;
    button.addEventListener('click', () => {
      kind = button.dataset.pickerOpen;opener = button;focusAfterClose = null;
      document.getElementById('lookup-title').textContent = settings[kind].title;
      query.placeholder = settings[kind].hint;query.value = '';
      const tr = document.createElement('tr');
      [...settings[kind].headers, '操作'].forEach(label => {
        const th = document.createElement('th');th.scope = 'col';th.textContent = label;tr.append(th);
      });
      columns.replaceChildren(tr);
      picker.showModal();query.focus();search();
    });
  });
  document.querySelector('[data-picker-close]').addEventListener('click', () => picker.close());
  picker.addEventListener('close', () => { searchRequest?.abort();(focusAfterClose || opener)?.focus(); });
  document.getElementById('lookup-search').addEventListener('submit', event => { event.preventDefault();search(); });
  document.getElementById('lookup-reset').addEventListener('click', () => { query.value = '';query.focus();search(); });

  ['student', 'book'].forEach(type => {
    const input = document.getElementById(`${type}No`), preview = document.getElementById(`${type}-preview`);
    input.addEventListener('input', () => {
      checks.get(type)?.abort();checks.delete(type);
      delete input.dataset.choiceCode;delete input.dataset.choiceLabel;
      input.removeAttribute('aria-invalid');preview.textContent = '';
    });
    input.addEventListener('blur', () => resolve(type));
    input.addEventListener('keydown', async event => {
      if (event.key !== 'Enter') return;
      event.preventDefault();
      if (await resolve(type)) document.getElementById(type === 'student' ? 'bookNo' : 'at').focus();
    });
    if (input.value) resolve(type);
  });

  /** 提交前再取一次最新状态；失败只提示核对，不改动任何借还数据。 */
  window.borrowPicker = {
    async validate() {
      const results = await Promise.all(['student', 'book'].map(type => resolve(type, true)));
      const failed = results.indexOf(false);
      if (failed >= 0) document.getElementById(failed === 0 ? 'studentNo' : 'bookNo').focus();
      return failed < 0;
    }
  };
})();
