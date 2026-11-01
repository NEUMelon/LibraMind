# 后端结构与业务边界

## 请求经过哪些层

```text
浏览器 / 后续Vue
    → SecurityFilterChain（Session、CSRF）
    → Controller（DTO校验、HTTP输入输出）
    → Service（权限、业务规则、事务）
    → DAO / SqlStore → MyBatis参数绑定 → MySQL
```

按业务模块分包：`auth`、`student`、`catalog`、`loan`、`importing`、`vision`、`search`、`ai`、`system`。`common`是响应、异常、审计、幂等和SQL帮助类。不是每张表机械生成一套Controller：作品版本副本属于同一个馆藏模块，借还记录属于同一流通业务。

`SqlStore`是轻量数据库访问封装，内部固定SQL中的问号转为MyBatis绑定参数。没有让前端或模型传SQL。简单CRUD用DAO，跨表事务在Service中完成。

## 数据表

|表|用途与主要约束|
|---|---|
|student|学号唯一、姓名、学院、专业、enabled；已借数量由ACTIVE记录聚合|
|user_account|用户名唯一、BCrypt密码哈希、ADMIN/STUDENT、关联学生；一个学生一个账号|
|work|人工维护的作品分组|
|book_edition|出版版本：ISBN13唯一（允许空）、书名作者出版信息、封面相对路径、来源；ngram全文索引|
|book_copy|某一实体册：accession_no唯一、版本、位置、索书号、流通状态|
|loan|借阅及归还同表；student/copy/operator外键，ACTIVE/RETURNED/CANCELLED，version乐观版本|
|audit_log|关键变更前后快照、操作人、原因、requestId；不因对象归档而删除|
|idempotency_record|user+key唯一，输入摘要与成功响应；与业务同事务|
|knowledge_document|资料类型、正文、来源、可见范围、版本和启用状态|
|knowledge_chunk|稳定片段编号、文档内序号、正文hash、enabled|
|chunk_embedding|片段+模型键唯一、维度、内容hash、float32小端BLOB|
|index_task|UPSERT/DELETE、PENDING/PROCESSING/DONE/FAILED、领取token、租约、重试|
|ai_conversation|用户会话、标题、归档状态、生成租约|
|ai_message|用户及助手消息、结构化候选顺序、引用、模型和延迟|
|ai_tool_execution|工具名、操作者、结果码、耗时、requestId；不保存密钥|
|pending_action|用户、操作快照、有效期、状态和最终结果|
|recognition_log|条码/封面/版权页提取结果、候选、选择、模型和延迟；默认无原图|
|ai_budget_day / ai_request|预算预留及token费用估算，独立短事务|

完整DDL是`src/main/resources/db/migration/V*.sql`。已经执行的版本不改内容，新增结构通过下一条迁移。

## 借阅为什么不会乱

正常锁顺序：**学生 → 按id升序的副本 → 借阅**。

1. 锁学生后检查enabled和当前ACTIVE数量，避免同时借不同册突破额度。
2. 锁副本并条件更新AVAILABLE→BORROWED，检查影响行数。
3. loan的生成列`active_copy_id`仅ACTIVE时等于copy_id，唯一索引兜底同册只能一条有效在借。
4. 创建loan、改copy、写audit、写幂等结果在同一事务。任意一步失败全部回滚。

还书改loan为RETURNED并释放copy；续借只改dueAt、renewCount、version。作废ACTIVE释放copy；作废RETURNED不动当前库存。撤销归还要检查它是最新有效流通、copy仍可借、学生额度允许，才恢复ACTIVE。

已借数量不靠前端加减，也没有异步计数任务。它来自同一事实表的ACTIVE计数，所以无需修复重复计数。

## AI不是另一套业务实现

```text
当前用户 + 有限历史
    → Spring AI ChatClient
    → 显式注册的只读工具 / prepare工具
    → 原有Service和DAO
    → 候选、资料引用、实时事实、待确认卡

用户点击确认
    → 普通REST confirm
    → 锁pending_action + 重新验证快照和当前业务
    → LoanService
    → 业务与SUCCEEDED一并提交
```

工具身份只来自服务端ToolContext，提示词不能更换账号。学生用他人loanId查询或操作返回404。工具不包含任意HTTP、SQL、文件、confirm能力。原始图像只交给固定视觉模型提取书目，不给视觉请求注册工具。

每轮最多6次模型调用、16次工具调用；输出最多1024 tokens，文本与图片有大小上限，最多4轮AI请求并行。模型网络请求不放进借还数据库事务。CSRF保护普通REST确认，不把用户一句“确认”当成授权执行。

## 知识与索引更新

版本变更在提交前生成实际元数据资料，并在同一事务登记index_task。worker短事务领取8条任务，事务外请求embedding，写回时重新锁文档→片段→任务，核对token、租约和hash。失败退避，最多3次，索引状态接口可以发现失败。

向量结果排序后还会回库检查权限、资料是否启用、内容hash是否变化、版本是否归档。过期向量不会被当作有效来源。RAG返回前再次核验引用；库存和应还日期必须重新查业务表。

当前是单应用实例方案，重启从MySQL载入向量。模型维度或模型键不一致时索引不就绪，查询明确降级；不能把不同向量空间混用。多实例同步是后续增强。

## 文件与运行目录

- `src/main`：源码、配置默认值和验证页。
- `src/test`：单元与真实数据库集成测试。
- `config`：本机私有配置和公开示例。私有文件不能打包。
- `data/demo`：可复核的书目、模拟学生和来源。
- `data/covers`：本地封面；数据库只存相对文件名。
- `data/replays`：明确录制的非私人演示响应。
- `evaluation`：固定查询、标签、原始结果和报告。
- `.runtime`：日志、PID、运行jar、临时工具，不是业务源码。
- `target`：Maven生成物，可重新构建。
