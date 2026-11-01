/* 只增强页面交互；所有数据校验、权限检查和借还事务仍由服务端完成。 */
document.body.classList.add('js-ready');

const menuToggle = document.querySelector('.menu-toggle');
if (menuToggle) {
  menuToggle.addEventListener('click', () => {
    const open = document.body.classList.toggle('nav-open');
    menuToggle.setAttribute('aria-expanded', String(open));
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && document.body.classList.contains('nav-open')) {
      document.body.classList.remove('nav-open');
      menuToggle.setAttribute('aria-expanded', 'false');
      menuToggle.focus();
    }
  });
}
document.querySelectorAll('.dismiss').forEach(button => {
  button.addEventListener('click', () => button.closest('.notice').remove());
});
document.querySelectorAll('[data-back]').forEach(button => {
  button.addEventListener('click', () => history.length > 1 ? history.back() : location.assign('./app'));
});

// 服务端错误与具体字段关联；帮助文字保留给读屏软件。
document.querySelectorAll('.field').forEach(field => {
  const input = field.querySelector('input, select, textarea');
  if (!input || !input.id) return;
  const descriptions = [];
  field.querySelectorAll('small').forEach((hint, index) => {
    if (!hint.id) hint.id = `${input.id}-hint-${index}`;
    if (hint.textContent.trim() || hint.hasAttribute('aria-live')) descriptions.push(hint.id);
    if (hint.classList.contains('field-error') && hint.textContent.trim()) input.setAttribute('aria-invalid', 'true');
  });
  if (descriptions.length) input.setAttribute('aria-describedby', descriptions.join(' '));
});
const errorNotice = document.querySelector('.notice.error');
if (errorNotice) errorNotice.focus();

const dialog = document.getElementById('confirm-dialog');
/** 确认框默认聚焦取消，Escape / 关闭按钮均取消本次操作。 */
function confirmOperation(message, label, danger) {
  return new Promise(resolve => {
    dialog.returnValue = 'cancel';
    document.getElementById('confirm-title').textContent = label;
    document.getElementById('confirm-message').textContent = message;
    const confirmButton = document.getElementById('confirm-submit');
    confirmButton.textContent = label;
    confirmButton.className = `button ${danger ? 'danger' : 'primary'}`;
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), { once: true });
    dialog.showModal();
  });
}
/** 用已核对且仍与输入一致的资料显示确认文字，避免显示过期姓名或书名。 */
function choiceLabel(input) {
  return input.dataset.choiceCode === input.value.trim() ? `${input.value}（${input.dataset.choiceLabel}）` : input.value;
}
const borrowForm = document.getElementById('borrow-form');

document.querySelectorAll('form[method="post"]').forEach(form => {
  form.addEventListener('submit', async event => {
    if (form.dataset.confirmed === 'yes') {
      delete form.dataset.confirmed;
      markSubmitting(form);
      return;
    }
    if (form.dataset.confirming === 'yes' || form.dataset.submitting === 'yes') {
      event.preventDefault();
      return;
    }
    const submitter = event.submitter || form.querySelector('button:not([type]), button[type="submit"]');
    const label = submitter?.textContent.trim() || '确认操作';
    let message = form.dataset.confirm;
    let danger = Boolean(message || form.dataset.danger);
    if (form.dataset.danger) message = `确认对记录 #${form.elements.id.value} 执行“${label}”？请核对所填原因。`;
    if ((message || form === borrowForm) && dialog) {
      event.preventDefault();
      form.dataset.confirming = 'yes';
      try {
        if (form === borrowForm) {
          if (await window.borrowPicker?.validate() === false) return;
          message = `学生：${choiceLabel(form.elements.studentNo)}\n图书：${choiceLabel(form.elements.bookNo)}\n借阅时间：${form.elements.at.value.replace('T', ' ')}`;
          danger = false;
        }
        if (await confirmOperation(message, label, danger)) {
          form.dataset.confirmed = 'yes';
          form.requestSubmit(submitter);
        }
      } finally { delete form.dataset.confirming; }
    } else markSubmitting(form);
  });
});
/** 真实表单提交时锁定按钮；浏览器后退恢复页面时解除锁定。 */
function markSubmitting(form) {
  form.dataset.submitting = 'yes';
  form.querySelectorAll('button[type="submit"], button:not([type])').forEach(button => {
    button.dataset.originalText = button.textContent;
    button.disabled = true;
    button.textContent = '正在保存…';
  });
}
window.addEventListener('pageshow', () => {
  document.querySelectorAll('form[data-submitting]').forEach(form => delete form.dataset.submitting);
  document.querySelectorAll('button[data-original-text]').forEach(button => {
    button.disabled = false;
    button.textContent = button.dataset.originalText;
    delete button.dataset.originalText;
  });
});
/** 图片文件损坏或在页面打开后被移走时，用同一张占位图替代，避免出现破图。 */
document.querySelectorAll('img[data-cover-fallback]').forEach(image => {
  const showFallback = () => {
    const fallback = image.dataset.coverFallback;
    if (!fallback) return;
    delete image.dataset.coverFallback;
    image.alt = '封面暂缺';
    image.src = fallback;
    image.closest('figure')?.querySelector('figcaption')?.remove();
  };
  image.addEventListener('error', showFallback, { once: true });
  if (image.complete && image.naturalWidth === 0) showFallback();
});
