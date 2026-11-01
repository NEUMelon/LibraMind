# 后端改动说明

结论：**没有修改后端源码、配置模板、Flyway 迁移、依赖版本或接口契约**，所以 `openapi.json` 和 `API_CONTRACT.md` 不需要同步。前端完全按现有接口实现。

## 新增的文件（只为在云端 Linux 上启动）

后端原来只有 Windows 的 `dev.ps1` / `start.ps1` / `stop.ps1`。为了在云端容器运行，补了三个等价的 Linux 脚本，行为与 PowerShell 版本一致：

| 文件 | 作用 |
|---|---|
| `scripts/dev.sh [run\|test\|verify\|package]` | 调用 `mvn -B -ntp` 的对应目标 |
| `scripts/start.sh` | 把 jar 复制成 `.runtime/server-app.jar` 后台启动，记录 PID，轮询 `/actuator/health/readiness`；端口被占用时直接退出，不杀其它进程。模型 Key 只从环境变量 `AIHUBMIX_API_KEY` 读取 |
| `scripts/stop.sh` | 只停止 PID 文件里记录、且命令行是 `server-app.jar` 的进程 |

脚本副本在交付目录的 `backend-patch/scripts/`。放到后端的 `scripts/` 下、加执行权限即可。

## 只在本环境存在、不交付的文件

- `config/application-local.properties`：云端 MySQL 账号、首个管理员的随机密码、`app.ai.mode=live` 等。按 `application-local.example.properties` 填写，权限 600。
- `config/demo-credentials.properties`：演示学生 `student20240001` 的随机密码。
- `data/covers/` 下 27 张封面：由后端自带的 `seed_demo.py` 通过正式上传接口写入。

两份配置含密码，已在后端 `.gitignore` 里，不要提交。封面是演示用的运行数据，原工程里也是由导入脚本生成，是否入库由你决定。

## 为什么没有改后端

联调中所有前端需求都能用现有接口满足。发现的几处后端行为（SSE 端点在错误的 `Accept` 下返回 500、知识资料列表只返回最近 100 条、客户端中止流后上游请求可能没有立即取消、审计只记录操作人编号）都不阻塞功能，前端已适配或说明，详见 `INTEGRATION.md` 第 6 节。是否要改，留给你决定；如果要改，建议各自配一个测试再改。

## 测试中用到、但不属于后端改动的东西

`e2e/mock-provider/` 的模拟模型服务只改测试容器的 `/etc/hosts` 和后端进程的 JVM 参数（信任模拟证书、`aihubmix.com` 不走出站代理），不碰后端代码。用完运行 `down.sh` 撤销 hosts 映射，再按正常方式重启后端。
