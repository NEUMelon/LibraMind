"""通过正式业务API导入演示数据；可重复执行，不清表，不访问传统版。"""
import datetime
import hashlib
import json
import secrets
import urllib.parse
from api_client import Client, ROOT, properties

api = Client()
api.login()
dataset = json.loads((ROOT / "data/demo/books-import.json").read_text(encoding="utf-8"))
preview = api.request("POST", "/api/v1/import/books/preview", dataset)
if preview["errors"]:
    print(json.dumps(preview["errors"], ensure_ascii=False))
    raise SystemExit("Import preview failed")
dataset_hash = hashlib.sha256(json.dumps(dataset, sort_keys=True).encode()).hexdigest()[:24]
imported = api.request("POST", "/api/v1/import/books/execute", dataset, "demo-books-" + dataset_hash)
print("Book import:", imported)
students = json.loads((ROOT / "data/demo/students.json").read_text(encoding="utf-8"))
ids = {}
for index, student in enumerate(students):
    rows = api.request("GET", "/api/v1/students?q=" + student["studentNo"] + "&size=10")["items"]
    existing = next((row for row in rows if row["studentNo"] == student["studentNo"]), None)
    if existing is None:
        existing = api.request("POST", "/api/v1/students", student)
    ids[student["studentNo"]] = existing["id"]
    if (index + 1) % 100 == 0:
        print("Students prepared:", index + 1)

private = ROOT / "config/demo-credentials.properties"
if not private.exists():
    private.write_text("username=student20240001\npassword=" + secrets.token_urlsafe(18) + "\n", encoding="utf-8")
credentials = properties(private)
try:
    reader = Client()
    reader.login(credentials["username"], credentials["password"])
except RuntimeError as failure:
    if "AUTHENTICATION_FAILED" not in str(failure):
        raise
    api.request("POST", f"/api/v1/students/{ids['20240001']}/account", credentials)

matched = 0
for cover in sorted((ROOT / "data/covers").glob("978*.jpg")):
    found = api.request("GET", "/api/v1/books?q=" + cover.stem + "&size=10")["items"]
    book = next((row for row in found if row.get("isbn13") == cover.stem), None)
    if book and book["coverUrl"].endswith("cover-missing.svg"):
        api.upload(f"/api/v1/books/{book['id']}/cover", cover)
        matched += 1

copies = api.request("GET", "/api/v1/copies?size=40")["items"]
student_nos = ["20210001", "20210002", "20220001", "20220002", "20240001", "20240002", "20250001", "20260001"]
for index, copy in enumerate(copies[:24]):
    result = api.request("POST", "/api/v1/loans", dict(studentId=ids[student_nos[index % len(student_nos)]],
                         accessionNos=[copy["accessionNo"]], note="模拟借阅，用于课程演示"), f"demo-loan-20261027-{index}")
    loan = result["loans"][0]
    if index >= 16:
        api.request("POST", "/api/v1/returns", dict(loanId=loan["id"], note="模拟归还"), f"demo-return-20261027-{index}")
    elif index == 15:
        api.request("POST", f"/api/v1/loans/{loan['id']}/renew", {}, f"demo-renew-20261027-{index}")

print("Covers uploaded:", matched)
print("Overview:", api.request("GET", "/api/v1/dashboard/overview"))
print("Demo account saved locally in config/demo-credentials.properties (password not printed)")
