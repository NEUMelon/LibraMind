import json
from api_client import Client, ROOT

api = Client()
api.login()
query = json.loads((ROOT / "evaluation/no-result-query.json").read_text(encoding="utf-8"))
rows = []
for mode in ("keyword", "vector", "hybrid"):
    if mode == "keyword":
        result = api.request("GET", "/api/v1/search/books?q=" + query["query"])
    else:
        result = api.request("POST", "/api/v1/search/" + ("semantic" if mode == "vector" else mode),
                             dict(query=query["query"], limit=5, availableOnly=False))
    assert result["hits"] == [], mode
    rows.append(dict(mode=mode, returnedMode=result["mode"], hits=0, passed=True))
(ROOT / "evaluation/no-result-results.json").write_text(json.dumps(dict(query=query, rows=rows), ensure_ascii=False, indent=2), encoding="utf-8")
print("Fixed no-result ISBN: all three retrieval routes correctly return empty")
