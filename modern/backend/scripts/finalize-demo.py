"""补充可复核的数据清洗和少量状态示例，全部通过正式API，不清表。"""
import datetime
import json
from api_client import Client, ROOT

api = Client()
api.login()
books = json.loads((ROOT / "data/demo/books-import.json").read_text(encoding="utf-8"))["books"]
cleaned_isbns = {note["isbn13"] for note in json.loads((ROOT / "data/demo/cleaning-notes.json").read_text(encoding="utf-8"))}
for input_book in books:
    if input_book["isbn13"] in cleaned_isbns:
        found = api.request("GET", "/api/v1/books?q=" + input_book["isbn13"])["items"][0]
        if any(found.get(field) != value for field, value in input_book.items() if field not in {"workId", "isbn13"}):
            api.request("PATCH", f"/api/v1/books/{found['id']}", input_book)
            print("Cleaned metadata for ISBN", input_book["isbn13"])

copies = api.request("GET", "/api/v1/copies?size=40")["items"]
for index, copy in enumerate(copies[25:28]):
    target = "MAINTENANCE" if index == 0 else "OFF_SHELF"
    if copy["circulationStatus"] == "AVAILABLE":
        body = {key: copy[key] for key in ("accessionNo", "editionId", "callNumber", "location")}
        body["circulationStatus"] = target
        api.request("PATCH", f"/api/v1/copies/{copy['id']}", body)

active = api.request("GET", "/api/v1/loans?status=ACTIVE&size=100")["items"]
for loan in ([] if any(row["note"] == "模拟毕业学生未还书及逾期场景" for row in active) else active):
    if loan["studentNo"] == "20210001" and not loan["overdue"] and loan["note"] == "模拟借阅，用于课程演示":
        now = datetime.datetime.now(datetime.timezone.utc)
        api.request("PATCH", f"/api/v1/loans/{loan['id']}", dict(
            borrowedAt=(now - datetime.timedelta(days=40)).isoformat(),
            dueAt=(now - datetime.timedelta(days=10)).isoformat(),
            note="模拟毕业学生未还书及逾期场景", reason="构造课程演示数据，非真实借阅"))
        break
print("Overview:", api.request("GET", "/api/v1/dashboard/overview"))
