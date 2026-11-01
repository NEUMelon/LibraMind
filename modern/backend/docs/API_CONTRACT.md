# API契约与业务约定

基础路径 `/api/v1`，JSON UTF-8；生成式输出、书目数据也都视为不可信文本。确切字段和HTTP方法以当前`openapi.json`为准。

## 通用响应

```json
{"success":true,"data":{"id":1},"requestId":"..."}
```

```json
{"success":false,"code":"BORROW_LIMIT_EXCEEDED","message":"同时在借数量不能超过5册","requestId":"..."}
```

字段校验失败时`details`是字段名到错误原因的映射。所有响应有`X-Request-ID`，服务端可据此关联日志。时间输入输出为带时区ISO-8601；存储与计算统一UTC，前端转换显示本地时间。

分页：`page>=1`、`1<=size<=100`；返回`items,page,size,totalElements,totalPages`。空列表不报错，不把页码0当第一页。

## 认证

|方法|路径|输入/含义|
|---|---|---|
|GET|auth/csrf|建立Session，返回headerName与token|
|POST|auth/login|username,password；必须有登录前CSRF|
|GET|auth/me|当前账号，只有id、username、role、studentId|
|POST|auth/logout|使Session失效；必须有登录后的CSRF|

登录后重新获取CSRF。密码哈希、数据库密码和模型Key不出现在任何API响应中。无需JWT或Redis。ADMIN维护所有资料；STUDENT只读本人敏感数据，公开馆藏不做“归属”限制。

## 教师四模块与馆藏

|资源|接口|
|---|---|
|学生|GET/POST students；GET/PATCH/DELETE students/{id}；POST students/{id}/account|
|出版版本|GET/POST books；GET/PATCH/DELETE books/{id}|
|实体副本|GET/POST copies；GET/PATCH/DELETE copies/{id}；GET copies/by-accession/{accessionNo}|
|版本副本|GET books/{editionId}/copies|
|作品分组|GET works/{id}；PATCH editions/{id}/work|
|封面|POST books/{id}/cover multipart file；GET covers/{filename}|
|借书记录|GET loans；POST loans；GET/PATCH/DELETE loans/{id}|
|还书记录|GET returns；POST returns；GET/PATCH/DELETE returns/{id}|
|续借|POST loans/{id}/renew|
|本人书架|GET me/loans、me/loan-history、me/borrowing-eligibility|
|学生历史|GET students/{studentId}/loans、students/{studentId}/loan-history；本人或ADMIN|

学生列表参数`q,state=enabled|disabled,page,size`。馆藏列表参数`q,available,page,size`；副本列表`q,status,page,size`。流通列表支持`q,studentId,status,from,to,page,size`；returns按归还日期过滤，loans按借阅日期过滤。作废记录默认隐藏，管理员可按CANCELLED筛选。

版本列表另支持`sort=newest|title|available`白名单排序；管理员可GET`students/by-number/{number}`按完整学号查找。POST`loans/preview`使用与借书相同的输入，返回逐册预检与额度，不写库存，不保证正式提交时仍可借。

BookInput包括title、authors、isbn13等元数据，不含前端可任意修改的coverPath。封面只能经上传接口设置。CopyInput的circulationStatus只能维护非在借状态，不能绕过流通业务直接借出或释放。

借书输入：

```json
{"studentId":123,"accessionNos":["LM-9787115251121-01","LM-9787115258540-01"],"note":"柜台登记"}
```

学生账号忽略传入studentId，身份取自Session；ADMIN代办必须提供studentId。1至10册，批内不能重复，全批原子成功/回滚。默认5册上限、30天借期。不能用ISBN、editionId代替accessionNo。

归还输入二选一：`{loanId:...}` 或 `{accessionNo:"..."}`，可带note。后者自动找ACTIVE借阅。不能新增无借阅来源的孤立还书记录。

PATCH更正只接受日期、note与必填reason，不允许换学生或副本。DELETE loans是作废；DELETE returns是撤销误归还。正常归还必须POST returns，不能通过PATCH伪造状态。

## 幂等、事务和并发

- 借书、归还、续借、导入执行支持Idempotency-Key，同一user+key+相同内容返回原结果，改内容返回409。
- 成功结果和借还写入在同一事务；业务回滚时幂等占位一起回滚。记录没有定时提前清除，保留超过24小时。
- 确认卡用actionId幂等，不额外依赖请求key；重复与并发confirm只提交一次。
- 409是正常业务冲突，不应该在前端无限自动重试；换副本、修改输入或重新准备确认卡后属于新操作。

## AI、识图与检索

|功能|接口|
|---|---|
|关键词|GET search/books?q=...&limit=...&availableOnly=...，公开且不调用模型|
|语义/混合|POST search/semantic、search/hybrid `{query,limit,availableOnly}`，需登录|
|导入|POST import/books/preview、execute `{books:[BookInput...],copiesPerEdition}`|
|条码|POST vision/barcode multipart file；ISBN EAN13与馆藏CODE128|
|标签|GET copies/{id}/barcode PNG；POST copies/labels `{copyIds:[...]}`返回标签清单|
|封面|POST vision/book-cover；POST vision/recognitions/{id}/choice `{editionId:null或候选ID}`|
|版权页|POST vision/copyright-page；POST vision/copyright-page/{id}/confirm完整BookInput，ADMIN|
|知识|GET/POST knowledge、GET/PATCH/DELETE knowledge/{id}；列表/维护仅ADMIN|
|问答|POST ai/ask `{question,editionId?}`|
|会话与SSE|见FRONTEND_HANDOFF.md|
|确认卡|POST ai/actions/prepare-borrow、prepare-renew、prepare-return；GET actions/{id}；POST confirm/cancel|

检索limit为1至20，query最多500字。关键词+向量分别召回，RRF的k固定60。返回`mode,hits,degraded,latencyMs`；degraded包含vector时当前结果退为关键词。有效ISBN精确指定版本，没有该版本或筛选后无可借册就返回空，不用相似书冒充命中。

图片只接受真实JPEG/PNG/WebP，最多10MB、2000万像素，文件名由服务器生成。视觉模型仅提取看得见的字段，ISBN校验不通过会清空并提示核对；库存从真实数据库读。版权页识别不保存书目，只有人工确认完整表单才写库。

导入每批1至500条，ISBN必须有效且批内不重复，每版可生成0至5册。预览不写库；执行有任意错误全批回滚。源数据来源与模拟副本位置要在展示/文档中区分。

## 运行与审计

GET dashboard/overview、dashboard/loan-trend、audit-logs、audit-logs/{id}、admin/search/index-status、admin/ai/requests，均要求ADMIN。Actuator只开放health，匿名不展示内部详情。

## 稳定错误码

|HTTP|代表错误|
|---|---|
|400|INVALID_ARGUMENT、VALIDATION_FAILED、INVALID_REQUEST_BODY：输入不合法|
|401|AUTHENTICATION_REQUIRED、AUTHENTICATION_FAILED：未登录或登录失败|
|403|CSRF_TOKEN_INVALID、ACCESS_DENIED：Token失效或没有角色权限|
|404|NOT_FOUND：不存在，或不属于本人，返回同样的提示|
|405|METHOD_NOT_ALLOWED：HTTP方法不支持|
|409|DATA_CONFLICT、COPY_UNAVAILABLE、BORROW_LIMIT_EXCEEDED、LOAN_NOT_ACTIVE、LOAN_OVERDUE、RENEW_LIMIT_REACHED、ACTION_STALE、IDEMPOTENCY_KEY_REUSED、CONVERSATION_BUSY、SOURCE_CHANGED等|
|413|IMAGE_TOO_LARGE：文件超过上传上限|
|429|AI_BUDGET_EXCEEDED、AI_REQUEST_LIMIT、AI_BUSY：预算、单轮或并发限制|
|503|AI_UNAVAILABLE、REPLAY_MISS：模型故障、离线或没有匹配录制|
|500|INTERNAL_ERROR：内部失败，按requestId排查，不回显SQL或供应商认证信息|

PendingAction的FAILED、EXPIRED、CANCELLED也会作为200响应中的真实资源状态返回，前端必须读取status，不能只判断HTTP200就提示“借书成功”。
