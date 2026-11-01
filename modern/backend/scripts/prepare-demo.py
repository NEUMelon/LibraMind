"""将工作区既有来源整理为现代版导入格式，不访问数据库或模型。"""
import json
import random
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COURSE = ROOT.parents[1]
DATA = ROOT / "data" / "demo"
DATA.mkdir(parents=True, exist_ok=True)
source = json.loads((COURSE / "图书数据/中文书目/中文书目_精选500.json").read_text(encoding="utf-8-sig"))
books = []
for row in source:
    books.append(dict(workId=None, title=row["title"], subtitle="", authors=row["author"],
                      translators=row.get("translators", ""), isbn13=row["isbn13"], publisher=row.get("publisher", ""),
                      publicationDate=row.get("published_date", ""), editionStatement=row.get("edition", ""),
                      volume="", language=row.get("language", "zh"), classificationNo="",
                      category=row.get("category", ""), description="", sourceUrl=row["source"]["url"]))
cleaning = []
for book in books:
    for field, value in list(book.items()):
        if isinstance(value, str) and any(ord(char) < 32 for char in value):
            clean = "".join(char for char in value if ord(char) >= 32)
            cleaning.append(dict(isbn13=book["isbn13"], field=field, rawValue=value, cleanedValue=clean,
                                 reason="移除目录抽取引入的不可打印控制字符"))
            book[field] = clean
    if re.search(r"\s978-", book["title"]):
        raw_title = book["title"]
        book["title"] = re.split(r"\s978-", raw_title, maxsplit=1)[0].strip()
        cleaning.append(dict(isbn13=book["isbn13"], rawTitle=raw_title, cleanedTitle=book["title"],
                             reason="目录PDF抽取把后续书目行拼入标题；只保留下一ISBN之前的标题"))
(DATA / "cleaning-notes.json").write_text(json.dumps(cleaning, ensure_ascii=False, indent=2), encoding="utf-8")
(DATA / "books-import.json").write_text(json.dumps(dict(books=books, copiesPerEdition=2), ensure_ascii=False, indent=2), encoding="utf-8")
(DATA / "book-provenance.json").write_text(json.dumps(source, ensure_ascii=False, indent=2), encoding="utf-8")
text = (COURSE / "东北大学学院专业表以及学生学号构成.txt").read_text(encoding="utf-8-sig")
colleges = re.findall(r"\| \*\*(.*?)\*\* \| (.*?) \|", text)
rng = random.Random(20261027)
students = []
surnames = "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程魏苏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤"
given_names = ["明", "妍", "曦", "宁", "宇航", "思远", "佳怡", "浩然", "欣悦", "子涵", "梓轩", "雨桐", "子墨", "一诺", "俊杰", "诗涵", "佳宁", "博文", "宇浩", "可欣", "奕辰", "文静", "嘉禾", "景行", "知远", "若溪", "安然", "清扬", "嘉宁", "思齐"]
for year in range(2021, 2027):
    for index in range(1, 21 if year < 2023 else 101):
        college, majors = rng.choice(colleges)
        students.append(dict(studentNo=f"{year}{index:04}", name=rng.choice(surnames) + rng.choice(given_names),
                             college=college, major=rng.choice(majors.split("、")), enabled=year >= 2023 or index <= 2))
(DATA / "students.json").write_text(json.dumps(students, ensure_ascii=False, indent=2), encoding="utf-8")
(DATA / "README.md").write_text("""# 演示数据

500条真实书目来自原有出版社目录整理，来源和原始字段见book-provenance.json。
未提供简介和目录的字段保持空，不用AI补写。

440名学生的姓名、学号和状态均为模拟；学院专业参照用户提供的表，学号按入学年份加四位序号生成。
位置、每版两册的库存和借阅记录也都是演示数据。2021/2022级各2名仍启用以演示有书未还的情形。

封面复用传统版已有Open Library缓存，只复制可匹配ISBN的图片，来源见cover-manifest.json。
图片和书目供课程本地演示使用，来源公开不等于获得再分发授权。
""", encoding="utf-8")
cover_source = COURSE / "traditional/library-traditional/data/covers"
target = ROOT / "data/covers"
target.mkdir(exist_ok=True)
isbns = {book["isbn13"] for book in books}
count = 0
for image in cover_source.glob("*.jpg"):
    if image.stem in isbns:
        shutil.copy2(image, target / image.name)
        count += 1
shutil.copy2(cover_source / "manifest.json", DATA / "cover-manifest.json")
print(f"Prepared {len(books)} books, {len(students)} simulated students, {count} matching covers")
