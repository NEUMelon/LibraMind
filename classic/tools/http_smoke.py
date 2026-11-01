"""真实 HTTP 表单验收；使用临时虚构资料，结束后精确清理。需 requests、beautifulsoup4。"""
from pathlib import Path
from datetime import datetime, timedelta
from urllib.parse import urlparse, parse_qs
import json, time, subprocess, sys
import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8086/library/app'
if urlparse(BASE).hostname not in ('127.0.0.1', 'localhost'):
    raise ValueError('只允许本机演示实例')
session = requests.Session()
results = []
completed = False
suffix = str(time.time_ns())
no, book_no = '9090' + suffix, 'QA-HTTP-' + suffix


def check(name, condition):
    """记录一次有明确断言的验收结果。"""
    results.append({'name': name, 'passed': bool(condition)})
    assert condition, name
    print('PASS', name)


def get(**params):
    """读取真实 Servlet 路由。"""
    return session.get(BASE, params=params, timeout=20)


def soup(response):
    """仅解析 HTTP 返回的 HTML，不使用浏览器注入脚本。"""
    return BeautifulSoup(response.text, 'html.parser')


def post(module, action, token=None, **values):
    """提交与网页相同的表单，并保持同一会话。"""
    if token is None:
        form = get(module=module, action='new')
        node = soup(form).find('input', {'name': 'csrf'})
        # 还书选择页没有写入表单，从学生新增页取得同一会话的令牌。
        if node is None:
            node = soup(get(module='students', action='new')).find('input', {'name': 'csrf'})
        token = node['value']
    return session.post(BASE, data={'module': module, 'action': action, 'csrf': token, **values}, timeout=20)


def find_id(response, action='edit'):
    """从精确搜索所得的编辑链接读取新建记录编号。"""
    link = soup(response).select_one(f'a[href*="action={action}&id="]')
    if not link:
        raise AssertionError('未找到预期记录链接')
    return parse_qs(urlparse(link['href']).query)['id'][0]


try:
    for module in ('home', 'students', 'books', 'loans', 'returns'):
        r = get(module=module)
        check(f'{module} 页面与 UTF-8', r.status_code == 200 and '阅序' in r.text and 'é¦' not in r.text)
    check('未注册模块返回404', get(module='unknown').status_code == 404)
    check('JSP 内部路径不可直接访问', session.get(BASE.rsplit('/app', 1)[0] + '/WEB-INF/views/home.jsp').status_code == 404)
    check('缺失 CSRF 拒绝写入', post('students', 'save', token='wrong', studentNo=no, name='QA', college='测试学院', major='自动化').status_code == 403)
    check('GET 不能删除', get(module='students', action='delete', id=1).status_code == 422)
    malicious = '<script>alert("QA")</script>'
    r = post('students', 'save', studentNo=no, name=malicious, college='测试学院', major='自动化')
    check('学生新增并PRG重定向', r.status_code == 200 and len(r.history) == 1 and r.history[0].status_code == 302)
    r = get(module='students', q=no)
    sid = find_id(r)
    check('学生查询与输出转义', malicious not in r.text and '&lt;script&gt;' in r.text and no in r.text)
    r = post('students', 'save', studentNo=no, name='重复学号', college='测试学院', major='自动化')
    check('重复学号友好反馈且保留输入', r.status_code == 409 and '编号已存在' in r.text and soup(r).find('input', {'name': 'name'})['value'] == '重复学号')
    r = post('students', 'save', id=sid, name='HTTP验收读者', college='测试学院', major='软件工程', enabled='true')
    check('学生编辑成功', r.status_code == 200 and 'HTTP验收读者' in get(module='students', q=no).text)
    r = post('students', 'save', studentNo='NOT_NUMBER', name='校验', college='测试学院', major='自动化')
    check('服务端字段校验', r.status_code == 422 and '学号格式不正确' in r.text)
    r = post('books', 'save', bookNo=book_no, bookTitle='HTTP测试图书 <&>', author="O'Neil", edition='测试第1版')
    check('图书新增成功', r.status_code == 200)
    bid = find_id(get(module='books', q=book_no))
    r = post('books', 'save', bookNo=book_no, bookTitle='重复书号', author='QA', edition='第1版')
    check('重复书号拒绝', r.status_code == 409)
    r = post('books', 'save', id=bid, bookTitle='HTTP测试图书 <&>', author="O'Neil / 修改", edition='测试第2版', status='AVAILABLE')
    check('图书编辑与特殊字符', r.status_code == 200 and '测试第2版' in get(module='books', q=book_no).text)
    r = get(module='books', action='edit', id=bid)
    selected = soup(r).select_one('select[name="status"] option[selected]')
    check('枚举状态正确回显到编辑表单', r.status_code == 200 and selected is not None and selected['value'] == 'AVAILABLE')
    r = post('books', 'save', id=bid, bookTitle='非法状态不能覆盖书名', author='QA', edition='第1版', status='AVALIABLE')
    check('非法枚举状态拒绝且保留表单输入', r.status_code == 422 and '请选择有效的馆藏状态' in r.text and soup(r).find('input', {'name': 'bookTitle'})['value'] == '非法状态不能覆盖书名' and '非法状态不能覆盖书名' not in get(module='books', q=book_no).text)
    at = (datetime.now() - timedelta(days=2)).strftime('%Y-%m-%dT%H:%M')
    returned = (datetime.now() - timedelta(days=1)).strftime('%Y-%m-%dT%H:%M')
    r = post('loans', 'borrow', studentNo=no, bookNo=book_no, at=at, reason='HTTP自动验收')
    check('借阅登记成功', r.status_code == 200 and '借阅登记成功' in r.text)
    lid = find_id(get(module='loans', q=book_no))
    check('学生已借数量更新', '>1<small>册' in get(module='students', q=no).text)
    check('无效状态筛选保持原有默认查询行为', all((lambda response: response.status_code == 200 and book_no in soup(response).select_one('tbody').get_text())(get(module=module, q=book_no, state='NOT_A_STATUS')) for module in ('books', 'loans')))
    check('馆藏转为在借', '在借' in soup(get(module='books', q=book_no)).select_one('tbody').get_text())
    r = post('loans', 'borrow', studentNo=no, bookNo=book_no, at=at, reason='重复提交')
    check('重复借阅被拒绝且输入保留', r.status_code == 422 and soup(r).find('input', {'name': 'bookNo'})['value'] == book_no)
    check('在借学生删除保护', post('students', 'delete', id=sid).status_code == 422)
    check('在借图书删除保护', post('books', 'delete', id=bid).status_code == 422)
    check('在借状态不能普通编辑释放', post('books', 'save', id=bid, bookTitle='HTTP测试图书', author='QA', edition='第2版', status='AVAILABLE').status_code == 422)
    r = post('loans', 'correct', id=lid, at=at, reason='核对借阅日期')
    check('借阅日期更正', r.status_code == 200 and '借阅日期已更正' in r.text)
    r = post('returns', 'return', id=lid, at=returned, reason='测试归还')
    check('归还登记成功', r.status_code == 200 and '归还登记已完成' in r.text)
    r = post('returns', 'return', id=lid, at=returned, reason='重复点击')
    check('重复归还幂等', r.status_code == 200 and '>0<small>册' in get(module='students', q=no).text)
    r = post('returns', 'correct', id=lid, at=returned, reason='核对归还日期')
    check('归还日期更正', r.status_code == 200 and '归还日期已更正' in r.text)
    r = post('returns', 'undo', id=lid, reason='撤销误归还验收')
    check('撤销归还恢复在借', r.status_code == 200 and '>1<small>册' in get(module='students', q=no).text)
    r = post('loans', 'cancel', id=lid, reason='本次验收结束，作废演示借阅')
    check('作废借阅释放馆藏', r.status_code == 200 and '>0<small>册' in get(module='students', q=no).text)
    check('作废记录可检索且有原因', '本次验收结束' in get(module='loans', state='CANCELLED', q=book_no).text)
    check('有历史学生转为停用', '已停用' in post('students', 'delete', id=sid).text)
    check('有历史图书转为下架', '已下架' in post('books', 'delete', id=bid).text)
    r = get(module='books', q='完全不存在的查询词')
    check('空状态页面', r.status_code == 200 and '这里还没有匹配的图书' in r.text)
    r = get(module='books', q='BK-', page=2)
    check('分页保留搜索条件', 'q=BK-' in r.text and soup(r).select_one('.page-current').get_text(strip=True).startswith('2'))
    check('非法日期筛选友好错误', get(module='loans', **{'from': 'bad-date'}).status_code == 422)
    check('SQL注入关键词不扩大结果', '这里还没有匹配的图书' in get(module='books', q="' OR 1=1 --").text)
    completed = True
    print('全部通过：', len(results))
finally:
    driver = ROOT / 'target/library/WEB-INF/lib/mysql-connector-j-9.7.0.jar'
    subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(driver), str(ROOT / 'tools/HttpFixtureCleanup.java'), str(ROOT / 'config/db.properties'), no, book_no], check=True)
    output = ROOT / 'docs/http-验收结果.json'
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({'time': datetime.now().isoformat(timespec='seconds'), 'base': BASE, 'completed': completed, 'passed': completed and all(r['passed'] for r in results), 'checks': results}, ensure_ascii=False, indent=2), encoding='utf-8')
