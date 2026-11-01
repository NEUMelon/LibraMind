"""一次性按已有 ISBN 查询封面，缓存到本地；页面访问不调用外部 API。"""
from pathlib import Path
import json,time,requests,re,hashlib,argparse,io
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--metadata-only',action='store_true');args=parser.parse_args()
books=json.loads((ROOT/'data/demo-20261026/books.json').read_text(encoding='utf-8'))
cache=ROOT/'.runtime/cover-fetch';cache.mkdir(parents=True,exist_ok=True)
out=ROOT/'data/covers';out.mkdir(parents=True,exist_ok=True)
s=requests.Session();s.headers['User-Agent']='LibraryTraditionalCourseProject/1.0 (local educational catalog)'
metadata={};issues=[]
for start in range(0,len(books),50):
    batch=books[start:start+50];path=cache/f'metadata-{start//50:02}.json'
    if start==0 and (cache/'sample-metadata.json').exists() and not path.exists():path.write_bytes((cache/'sample-metadata.json').read_bytes())
    if path.exists():data=json.loads(path.read_text(encoding='utf-8'))
    else:
        try:
            r=s.get('https://openlibrary.org/api/books',params={'bibkeys':','.join('ISBN:'+b['isbn13'] for b in batch),'format':'json','jscmd':'data'},timeout=40)
            if r.status_code==429:issues.append({'batch':start//50,'reason':'rate_limited'});break
            r.raise_for_status();data=r.json();path.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
        except Exception as e:issues.append({'batch':start//50,'reason':type(e).__name__});continue
        time.sleep(1.1)
    metadata.update(data)
    print(f'Metadata: {min(start+50,len(books))}/{len(books)}; matched={len(metadata)}',flush=True)
manifest={};available=0
for b in books:
    isbn=b['isbn13'];item=metadata.get('ISBN:'+isbn,{})
    entry={'isbn13':isbn,'book_no':b['book_no'],'title':b['title'],'status':'missing','metadata_url':'https://openlibrary.org/isbn/'+isbn}
    url=item.get('cover',{}).get('medium','')
    if not re.fullmatch(r'https://covers.openlibrary.org/b/id/\d+-M\.jpg',url):manifest[isbn]=entry;continue
    # 必须由本 ISBN 的元数据返回具体 cover ID，绝不遍历或猜测封面 ID。
    path=out/(isbn+'.jpg')
    if not path.exists() and not args.metadata_only:
        try:
            r=s.get(url,timeout=25)
            if r.status_code==429:issues.append({'isbn13':isbn,'reason':'image_rate_limited'});manifest[isbn]=entry;break
            r.raise_for_status()
            if len(r.content)>3_000_000:raise ValueError('image too large')
            with Image.open(io.BytesIO(r.content)) as im:
                if im.format!='JPEG' or min(im.size)<40:raise ValueError('not a usable JPEG cover')
                im.verify()
            path.write_bytes(r.content)
            time.sleep(.45)
        except Exception as e:entry['reason']=type(e).__name__
    if path.exists():
        entry.update(status='available',image_url=url,file=isbn+'.jpg',sha256=hashlib.sha256(path.read_bytes()).hexdigest());available+=1
    manifest[isbn]=entry
for b in books:
    manifest.setdefault(b['isbn13'],{'isbn13':b['isbn13'],'book_no':b['book_no'],'title':b['title'],'status':'missing'})
(out/'manifest.json').write_text(json.dumps({'retrieved_at':'2026-10-26','source':'Open Library Books API and Covers API','isbn_count':len(books),'available':available,'missing':len(books)-available,'issues':issues,'covers':manifest},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'Local covers: {available}; placeholder: {len(books)-available}; issues: {len(issues)}',flush=True)