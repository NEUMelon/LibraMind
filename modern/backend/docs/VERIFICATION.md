# 验证记录

日期：2026-10-27。以下区分实际执行、缓存图片基线与仍缺少的真实世界材料，不把接口返回200当成全部质量验收。

## 构建与自动测试

环境：Windows，JDK21.0.12.1，Maven3.9.4，独立MySQL8.0.44测试库`libramind_test`。未使用Docker/Testcontainers，不读写传统版库。

最终执行：

```text
mvn -B -ntp verify -Plocal-it -Dlibramind.live-tests=true
BUILD SUCCESS
33 tests executed, 0 failures, 0 errors, 0 skipped
```

|测试组|实际验证内容|
|---|---|
|RequestIdFilterTest|输入请求标识校验与MDC清理|
|ImageServiceTest|伪装文件拒绝、真实PNG解码与缩放、空文件拒绝|
|RecordedChatModelTest|精确请求回放、重建模型后恢复、未知上下文拒绝，不访问供应商|
|FoundationIT|真实HTTP登录、CSRF、Session轮换、退出、错误、OpenAPI、匿名首页与资源|
|CirculationIT|借还续借、幂等、批量回滚、同册竞争、学生额度竞争、历史更正/作废/撤销、归属隔离、异常整批回滚|
|CatalogAndImportIT|同语言卷次人工分组、独立版本更新、导入预检不写库、非法输入整批拒绝、读者不能维护馆藏|
|PendingActionIT|准备不借出、并发确认只写一次、重复确认、过期/取消/越权、旧续借快照失效、归还确认、会话归属与租约、候选顺序恢复|
|KnowledgeIndexIT|旧领取不能覆盖新内容、向量重载恢复、禁用与私有资料过滤、固定ISBN校验和关键词查询|
|LiveAiIT|实际低价对话与1024维embedding、真实流式工具调用和消息持久化、真实封面输入及结构化提取|

普通verify默认跳过LiveAiIT，付费调用必须显式启用。机器可读汇总为`evaluation/verification-summary.json`；本机详细JUnit报告在target，未把带环境属性的原始XML日志分发给Claude。

## 真实HTTP与AI边界

`scripts/smoke_http.py`实际通过13项检查，包括公开首页、排序、学生越权403、他人借阅404、准备不写库、确认幂等、HTTP归还、库存恢复、Code128真实生成/解码、标签列表、允许/拒绝CORS来源。使用模拟数据创建并归还一条验收借阅，历史保留，当前借阅与库存计数恢复。

`scripts/smoke_ai.py`实际通过5个实时模型场景：本人借阅查询、准备续借、要求模型自行confirm、要求归还他人借阅、只给ISBN要求借书。没有产生真实借还变更，准备卡已取消，临时会话归档。另用明确标注的合成排版版权页验证字段提取、人工确认复用已有ISBN版本、重复确认返回同一版本。

这五个场景是固定边界冒烟，不宣称覆盖所有提示注入攻击。服务层权限和确认边界不依赖模型是否听话。

## 断网模式

实际把配置切到`replay`并重启，使用新会话重放已录制规则问题：调用规则工具并返回带引用的录制回答，没有供应商请求；借还计数不变。混合检索退为关键词并返回`degraded:["vector"]`。之后已恢复`live`，录制开关关闭。

`data/replays/README.md`给出精确问题。回放只支持已有上下文，不能准备业务操作，不是通用离线生成模型。

## 检索与封面基线

- 20条固定查询，三种模式共60次检索；指定目标Hit@5、MRR@5均为1.000。查询偏容易，关键词已经足够，不能据此宣称混合检索更优。
- 另有一条固定无结果ISBN用例，三种检索入口都正确返回空；结果单独保存，不混进正例排名均值。
- 关键词P50约25毫秒，向量/混合约1.2至1.3秒；单次外部网络抖动导致部分P95接近6秒。详情与逐次数据见`evaluation/RETRIEVAL_REPORT.md`。
- 20张既有封面缓存：作品Top1为0.850，Top5为0.950，正确版本在候选中为0.950；P50约2.14秒，P95约2.46秒。不是“唯一版本识别准确率”。失败和原始候选均保留在`cover-baseline-results.json`。

以上标签与图片关联尚未独立人工复核，缓存不是实拍，正例集没有负例。完整真实评估缺口见`evaluation/PENDING_REAL_WORLD_EVALUATION.md`，不能在报告里写成全部完成。

## 数据、索引和模型

开发库导入500个版本、1000册模拟馆藏、440名模拟学生、27张匹配封面。流通场景包含停用学生、毕业未还、逾期、维护和下架。数据来源与17项清洗见`data/demo`，未捏造简介/正文。

本轮检查索引已载入501个有效片段，待处理0、失败0、过期0，模型键固定`text-embedding-v4:1024:v1`。真实开发调用记录只出现`qwen3.5-flash`和`text-embedding-v4`，没有昂贵回退。开发库已知token估算费用约USD0.006；这是部分范围的应用估算，不含测试库或早期直接连通性调用，不是平台账单。

定价核对来源：[Aihubmix qwen3.5-flash](https://aihubmix.com/model/qwen3.5-flash)、[text-embedding-v4](https://aihubmix.com/model/text-embedding-v4)、[接入文档](https://docs.aihubmix.com/cn/quick-start)。价格和分档以平台当时公布为准；本机限制输入/输出并设置每日预算，不依赖自动路由。

## 前端验证边界

原生验证页已在浏览器检查登录、学生列表/表单、ISBN查询、学生书架与权限导航，并实际完成SSE规则问答，展示了工具轨迹和S1引用，浏览器没有脚本错误。正式Vue视觉、同学可用性研究和真实照片集尚未完成；它们不应从这些后端测试中推导出来。

## 交付检查

OpenAPI从当前运行版本导出，62条路径、82个操作、108个schema，本地schema引用通过检查。交付脚本采用白名单，并检查本机真实数据库密码、管理员/学生密码及API Key，禁止私有配置、运行日志、临时照片进入提交包。运行jar可本机重建，源包不含target或.runtime。
