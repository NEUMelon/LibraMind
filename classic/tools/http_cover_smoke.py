"""只读验证图书详情、精确版本封面、缺图占位和路径保护。"""
from pathlib import Path
from urllib.parse import parse_qs,urlparse
import requests,json,io
from PIL import Image
from bs4 import BeautifulSoup
ROOT=Path(__file__).resolve().parents[1];BASE='http://127.0.0.1:8086/library';results=[]
s=requests.Session()
def check(name,value):
    results.append({'name':name,'passed':bool(value)})
    assert value,name
    print('PASS',name)
def get(path='',**params):return s.get(BASE+path,params=params,timeout=20)
listing=get('/app',module='books',q='CN-9787115249494',state='AVAILABLE',page=1)
p=BeautifulSoup(listing.text,'html.parser');link=p.select_one('a[href*="action=detail"]')
check('书名提供详情入口',listing.status_code==200 and link is not None)
r=s.get('http://127.0.0.1:8086'+link['href'],timeout=20);p=BeautifulSoup(r.text,'html.parser')
check('真实书目详情展示ISBN和操作',r.status_code==200 and '9787115249494' in p.get_text() and '登记借阅' in p.get_text())
back=p.find('a',string='返回列表');query=parse_qs(urlparse(back['href']).query)
check('返回列表保留关键词状态页码',query.get('q')==['CN-9787115249494'] and query.get('state')==['AVAILABLE'] and query.get('page')==['1'])
img=p.select_one('.book-cover img');picture=get(img['src'].removeprefix('/library'))
check('真实封面走本地同源路径',img['src']=='/library/covers/9787115249494.jpg' and picture.status_code==200 and picture.headers['Content-Type'].startswith('image/jpeg'))
with Image.open(io.BytesIO(picture.content)) as image:check('封面是可解码图片',image.format=='JPEG' and min(image.size)>40)
check('封面替代文字包含书名','黑客与画家' in img['alt'])
r=get('/app',module='books',action='detail',id=1);p=BeautifulSoup(r.text,'html.parser');img=p.select_one('.book-cover img')
check('未知版本使用统一缺图图片',r.status_code==200 and img['src']=='/library/assets/cover-missing.svg' and img['alt']=='封面暂缺')
borrowed_page=BeautifulSoup(get('/app',module='books',state='BORROWED').text,'html.parser')
borrowed_link=borrowed_page.select_one('a[href*="action=detail"]')
assert borrowed_link is not None,'验收需要至少一册实际在借图书'
borrowed_detail=s.get('http://127.0.0.1:8086'+borrowed_link['href'],timeout=20)
check('在借图书详情不提供借阅按钮',borrowed_detail.status_code==200 and '登记借阅' not in BeautifulSoup(borrowed_detail.text,'html.parser').get_text())
placeholder=get('/assets/cover-missing.svg')
check('占位图片可正常加载',placeholder.status_code==200 and '封面暂缺' in placeholder.text and 'image/svg+xml' in placeholder.headers['Content-Type'])
r=get('/covers/9780140328721.jpg')
check('封面文件缺失时接口回退占位',r.status_code==200 and r.headers['Content-Type'].startswith('image/svg+xml') and '封面暂缺' in r.text)
check('任意文件路径被拒绝',get('/covers/db.properties').status_code==404 and get('/covers/9787115249494.jpg/extra').status_code==404)
check('不存在的书目得到受控错误',get('/app',module='books',action='detail',id=99999999).status_code==422)
check('详情保持只读',s.post(BASE+'/covers/9787115249494.jpg',timeout=20).status_code==405)
(ROOT/'docs/封面功能验收.json').write_text(json.dumps({'date':'2026-10-26','passed':len(results),'checks':results},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('All passed:',len(results))
