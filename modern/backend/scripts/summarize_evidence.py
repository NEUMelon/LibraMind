"""汇总当前真实测试及应用模型费用估算，输出无凭据的证据摘要。"""
import datetime
import decimal
import json
import xml.etree.ElementTree as ET
from api_client import Client, ROOT

tests = []
for directory in ("surefire-reports", "failsafe-reports"):
    for file in sorted((ROOT / "target" / directory).glob("TEST-*.xml")):
        suite = ET.parse(file).getroot()
        tests.append(dict(suite=suite.get("name"), tests=int(suite.get("tests", 0)),
                          skipped=int(suite.get("skipped", 0)), failures=int(suite.get("failures", 0)),
                          errors=int(suite.get("errors", 0))))
assert not sum(row["failures"] + row["errors"] for row in tests)
api = Client()
api.login()
records = []
page = 1
while True:
    result = api.request("GET", f"/api/v1/admin/ai/requests?page={page}&size=100")
    records += result["items"]
    if len(records) >= result["totalElements"]: break
    page += 1
models = sorted({row["model"] for row in records})
assert set(models) <= {"qwen3.5-flash", "text-embedding-v4"}
known = sum((decimal.Decimal(str(row["estimatedUsd"] or 0)) for row in records), decimal.Decimal(0))
report = dict(time=datetime.datetime.now(datetime.timezone.utc).isoformat(), suites=tests,
              testsExecuted=sum(row["tests"] - row["skipped"] for row in tests),
              skipped=sum(row["skipped"] for row in tests), models=models,
              developmentAiRequests=len(records), knownEstimatedUsd=str(known),
              failedOrUnknownRequests=sum(row["status"] != "SUCCEEDED" for row in records),
              costScope="仅开发库本后端记录，不含测试库及早期直接连通性请求；不是供应商账单",
              index=api.request("GET", "/api/v1/admin/search/index-status"),
              data=api.request("GET", "/api/v1/dashboard/overview"))
(ROOT / "evaluation/verification-summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print("Executed tests:", report["testsExecuted"], "; skipped:", report["skipped"], "; fixed models:", models)
print("Known development request estimate USD:", known, "; index pending:", report["index"]["pending"])
