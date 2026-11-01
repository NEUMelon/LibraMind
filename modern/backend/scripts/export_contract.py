"""从当前运行后端导出OpenAPI并检查本地schema引用，不读取私有凭据。"""
import json
import urllib.request
from api_client import ROOT

with urllib.request.urlopen("http://127.0.0.1:8090/openapi.json", timeout=30) as response:
    document = json.load(response)
schemas = document["components"]["schemas"]

def validate(value):
    if isinstance(value, dict):
        reference = value.get("$ref", "")
        if reference.startswith("#/components/schemas/"):
            assert reference.rsplit("/", 1)[1] in schemas, reference
        for child in value.values():
            validate(child)
    elif isinstance(value, list):
        for child in value:
            validate(child)

validate(document)
for name in ("LoanView", "BookView", "StudentView", "ActionView", "Reply", "ToolTrace", "ErrorResponse"):
    assert name in schemas, name
(ROOT / "docs/openapi.json").write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")
methods = {"get", "post", "patch", "delete", "put", "head", "options"}
operations = sum(method in methods for item in document["paths"].values() for method in item)
print("Exported OpenAPI:", len(document["paths"]), "paths,", operations, "operations,", len(schemas), "schemas")
