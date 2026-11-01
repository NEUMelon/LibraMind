"""真实HTTP验收：权限、确认卡幂等、借还恢复、条码和CORS。只操作模拟数据。"""
import datetime
import json
import urllib.error
import urllib.request
from api_client import Client, ROOT, properties

admin = Client()
admin.login()
reader = Client()
credentials = properties(ROOT / "config/demo-credentials.properties")
reader.login(credentials["username"], credentials["password"])
evidence = []

def check(name, result):
    assert result, name
    evidence.append(dict(check=name, passed=True))

def denied(client, method, path, status):
    try:
        client.request(method, path)
    except RuntimeError as failure:
        return f"HTTP {status}:" in str(failure)
    return False

check("public-index", urllib.request.urlopen(admin.base + "/").status == 200)
check("book-sorting", len(admin.request("GET", "/api/v1/books?sort=title&size=5")["items"]) == 5)
check("reader-student-list-forbidden", denied(reader, "GET", "/api/v1/students", 403))
mine = reader.request("GET", "/api/v1/me/loans")["items"]
foreign = next(row for row in admin.request("GET", "/api/v1/loans?status=ACTIVE&size=100")["items"] if row["studentId"] != mine[0]["studentId"])
check("foreign-loan-404", denied(reader, "GET", "/api/v1/loans/" + str(foreign["id"]), 404))
before = admin.request("GET", "/api/v1/dashboard/overview")
copy = admin.request("GET", "/api/v1/copies?status=AVAILABLE&size=1")["items"][0]
card = reader.request("POST", "/api/v1/ai/actions/prepare-borrow", {"accessionNo": copy["accessionNo"]})
check("prepare-no-write", admin.request("GET", "/api/v1/dashboard/overview") == before)
confirmed = reader.request("POST", f"/api/v1/ai/actions/{card['id']}/confirm", {})
again = reader.request("POST", f"/api/v1/ai/actions/{card['id']}/confirm", {})
check("http-confirm-once", confirmed["status"] == "SUCCEEDED" and confirmed["result"] == again["result"])
loan_id = confirmed["result"]["loans"][0]["id"]
check("copy-borrowed", admin.request("GET", f"/api/v1/copies/{copy['id']}")["circulationStatus"] == "BORROWED")
return_card = reader.request("POST", "/api/v1/ai/actions/prepare-return", {"loanId": loan_id})
returned = reader.request("POST", f"/api/v1/ai/actions/{return_card['id']}/confirm", {})
check("http-confirm-return", returned["result"]["status"] == "RETURNED")
check("counts-restored", admin.request("GET", "/api/v1/dashboard/overview") == before)

barcode_path = ROOT / ".runtime/http-barcode.png"
with admin.opener.open(admin.base + f"/api/v1/copies/{copy['id']}/barcode") as response:
    barcode_path.write_bytes(response.read())
decoded = admin.upload("/api/v1/vision/barcode", barcode_path)
check("actual-code128-decode", decoded["value"] == copy["accessionNo"] and decoded["copy"]["id"] == copy["id"])
labels = admin.request("POST", "/api/v1/copies/labels", {"copyIds": [copy["id"]]})
check("label-list", labels[0]["copy"]["id"] == copy["id"])

for origin, expected in (("http://127.0.0.1:5173", 200), ("https://untrusted.invalid", 403)):
    request = urllib.request.Request(admin.base + "/api/v1/loans", method="OPTIONS", headers={
        "Origin": origin, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type,x-csrf-token"})
    try:
        with urllib.request.urlopen(request) as response:
            check("cors-"+origin, response.status == expected and response.headers.get("Access-Control-Allow-Origin") == origin)
    except urllib.error.HTTPError as failure:
        check("cors-"+origin, failure.code == expected)

report = dict(time=datetime.datetime.now(datetime.timezone.utc).isoformat(), checks=evidence,
              mockLoanCreatedAndReturned=loan_id, circulationCounts=before)
(ROOT / "evaluation/http-smoke-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print("HTTP checks passed:", len(evidence), "; mock loan", loan_id, "created and returned; counts restored")
