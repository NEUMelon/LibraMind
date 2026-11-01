"""现有封面缓存的正例基线，不能替代需求中的真实拍摄评估集A。"""
import datetime
import json
import math
import statistics
import time
from api_client import Client, ROOT

api = Client()
api.login()
rows = []
for image in sorted((ROOT / "data/covers").glob("978*.jpg"))[:20]:
    book = api.request("GET", "/api/v1/books?q=" + image.stem)["items"][0]
    start = time.perf_counter()
    row = dict(expectedIsbn=image.stem, expectedEditionId=book["id"], expectedWorkId=book["workId"],
               file="data/covers/" + image.name)
    try:
        result = api.upload("/api/v1/vision/book-cover", image)
        works = [work["id"] for work in result["candidates"]]
        editions = [edition["id"] for work in result["candidates"] for edition in work["editions"]]
        row.update(outcome=result["outcome"], extracted=result["extracted"], warnings=result["warnings"],
                   workTop1=bool(works and works[0] == book["workId"]), workTop5=book["workId"] in works[:5],
                   versionInCandidates=book["id"] in editions, candidateWorks=works, candidateEditions=editions,
                   model=result["model"], recognitionId=result["recognitionId"])
    except RuntimeError as failure:
        row.update(error=str(failure), workTop1=False, workTop5=False, versionInCandidates=False)
    row["latencyMs"] = round((time.perf_counter()-start)*1000, 2)
    rows.append(row)
    print("Cover baseline", len(rows), image.stem, row.get("outcome", "ERROR"), flush=True)
latencies = sorted(row["latencyMs"] for row in rows)
report = dict(time=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              dataset="20张既有Open Library封面缓存，只有馆内正例，没有真实拍摄、负例或人工候选确认",
              limitations="ISBN文件名关联尚未独立人工复核；不能据此宣称真实拍照识书准确率；不是v1.2评估集A的完成报告",
              count=len(rows), workTop1=statistics.mean(row["workTop1"] for row in rows),
              workTop5=statistics.mean(row["workTop5"] for row in rows),
              versionInCandidates=statistics.mean(row["versionInCandidates"] for row in rows),
              p50Ms=statistics.median(latencies), p95Ms=latencies[math.ceil(.95*len(rows))-1], rows=rows)
(ROOT / "evaluation/cover-baseline-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
text = f"""# 现有封面缓存正例基线

{report['dataset']}。

{report['limitations']}。

|指标|实际结果|
|---|---:|
|样本数|{report['count']}|
|作品Top-1|{report['workTop1']:.3f}|
|作品Top-5|{report['workTop5']:.3f}|
|正确版本在候选集合中|{report['versionInCandidates']:.3f}|
|P50毫秒|{report['p50Ms']:.1f}|
|P95毫秒|{report['p95Ms']:.1f}|

“版本在候选中”不是“模型已唯一确认正确版本”。没有馆外负例与人工最终选择，本次不计算错误确认率、拒识质量或确认后准确率。
逐张提取字段、候选与异常见cover-baseline-results.json，失败也保留。
"""
(ROOT / "evaluation/COVER_BASELINE_REPORT.md").write_text(text, encoding="utf-8")
print(text)
