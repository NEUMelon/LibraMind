"""固定二十条查询测量命中、MRR和延迟；初始单目标标签不等同完整相关性判断。"""
import datetime
import json
import math
import statistics
import time
import urllib.parse
from api_client import Client, ROOT

api = Client()
api.login()
index = api.request("GET", "/api/v1/admin/search/index-status")
if index["pending"] or index["failed"] or index["stale"]:
    raise SystemExit("Index is not settled; finish/retry indexing before evaluation")
queries = json.loads((ROOT / "evaluation/retrieval-queries.json").read_text(encoding="utf-8"))
rows = []
for query in queries:
    for mode in ("keyword", "vector", "hybrid"):
        start = time.perf_counter()
        if mode == "keyword":
            result = api.request("GET", "/api/v1/search/books?q=" + urllib.parse.quote(query["query"]) + "&limit=5")
        else:
            result = api.request("POST", "/api/v1/search/" + ("semantic" if mode == "vector" else mode),
                                 dict(query=query["query"], limit=5, availableOnly=False))
        found = [hit["book"].get("isbn13") for hit in result["hits"]]
        ranks = [rank for rank, isbn in enumerate(found, 1) if isbn in query["relevantIsbns"]]
        rows.append(dict(queryId=query["id"], query=query["query"], type=query["type"], mode=mode,
                         requestedModel="text-embedding-v4:1024:v1", returnedIsbns=found,
                         hit5=int(bool(ranks)), reciprocalRank5=1/min(ranks) if ranks else 0,
                         ndcg5=1/math.log2(min(ranks)+1) if ranks else 0,
                         latencyMs=round((time.perf_counter()-start)*1000, 2), degraded=result["degraded"]))
    print("Evaluated", query["id"], flush=True)
summary = []
for group in ("all", "exact", "natural"):
    for mode in ("keyword", "vector", "hybrid"):
        selected = [row for row in rows if row["mode"] == mode and (group == "all" or row["type"] == group)]
        latencies = sorted(row["latencyMs"] for row in selected)
        summary.append(dict(group=group, mode=mode, count=len(selected),
                            hit5=statistics.mean(row["hit5"] for row in selected),
                            mrr5=statistics.mean(row["reciprocalRank5"] for row in selected),
                            ndcg5=statistics.mean(row["ndcg5"] for row in selected),
                            p50Ms=statistics.median(latencies), p95Ms=latencies[math.ceil(.95*len(latencies))-1],
                            degradedCount=sum(bool(row["degraded"]) for row in selected)))
report = dict(time=datetime.datetime.now(datetime.timezone.utc).isoformat(), index=index,
              labelStatus="开发助手依据书目标题整理的单目标初始标签，未经过独立人工评审，不覆盖全部相关书目",
              summary=summary, rows=rows)
(ROOT / "evaluation/retrieval-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
lines = ["# 固定查询检索评估", "", report["labelStatus"], "",
         "使用500条真实书目；同一固定查询分别调用三种检索。标签是一个明确目标，不使用不完整标签计算Precision。",
         "Hit@5表示指定目标是否在前五，MRR@5表示指定目标的倒数排名；并不表示其它返回书目都不相关。", "",
         "|查询组|模式|数量|Hit@5|MRR@5|nDCG@5|P50毫秒|P95毫秒|降级数|", "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
for item in summary:
    lines.append("|{group}|{mode}|{count}|{hit5:.3f}|{mrr5:.3f}|{ndcg5:.3f}|{p50Ms:.1f}|{p95Ms:.1f}|{degradedCount}|".format(**item))
lines += ["", "原始排名和逐次时间见retrieval-results.json。一次小样本、本地缓存及网络波动会影响结果，不能据此宣称混合检索普遍优于关键词。"]
(ROOT / "evaluation/RETRIEVAL_REPORT.md").write_text("\n".join(lines)+"\n", encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
