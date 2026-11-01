# LibraMind 图书管理系统

Java 课程项目，围绕学生管理、图书管理、借书记录和还书记录，实现了两个独立版本：

- **经典版**：Java 17、Servlet、JSP、JDBC、Tomcat 9 和 MySQL。
- **拓展版**：Java 21、Spring Boot、MyBatis、MySQL、Vue 3 与 Spring AI，支持馆藏副本、角色权限、条码及 AI 辅助功能。

```text
classic/                 经典版
modern/backend/          拓展版 Spring Boot 后端
modern/frontend/         拓展版 Vue 前端
```

两个版本使用独立数据库。演示学生、馆藏与借阅数据为模拟数据，真实书目仅用于课程演示，不代表实际馆藏。

## 拓展版快速开始

需要 JDK 21、Maven、MySQL 8、Node.js（建议 22）和 pnpm 10。首次构建需要网络下载依赖。

1. 在 MySQL 中创建独立的空数据库 `libramind`，字符集使用 `utf8mb4`，并准备有建表、读写权限的本地数据库账号。
2. 在 `modern/backend`，把 `config/application-local.example.properties` 复制为 `config/application-local.properties`，填写数据库地址、用户名、密码和 `app.bootstrap.password`。首次没有管理员账号时，应用使用配置创建 `admin`。之后修改该配置不会重置现有账号。
3. 在 `modern/backend` 目录执行 `mvn spring-boot:run`；Flyway 自动执行数据库迁移。确保当前 Maven 使用 JDK 21。
4. 在另一个终端进入 `modern/frontend`，执行 `pnpm install --frozen-lockfile` 和 `pnpm dev`；浏览器打开 <http://127.0.0.1:5173/>。后端默认监听 8090。
5. `modern/backend/data/demo` 包含模拟学生及附来源的书目资料；本库不包含第三方图书封面图片，也不包含已有数据库。需要演示数据时，阅读该目录说明并确认使用独立演示库。

后端示例默认 `app.ai.mode=offline`，普通业务不依赖模型。要启用实时 AI，请自行配置 `AIHUBMIX_API_KEY` 环境变量和 `app.ai.mode=live`；调用外部模型可能产生费用。不要将密钥或本机私有配置提交到仓库。

## 经典版快速开始

需要 JDK 17、Maven 和 MySQL 8。在 `classic` 目录：

1. 运行 `mvn -B -ntp package` 构建项目。
2. 把 `config/admin.example.properties` 复制为 `config/admin.properties`，按本机 MySQL 管理账号填写配置。
3. Windows PowerShell 执行 `powershell -ExecutionPolicy Bypass -File scripts/init.ps1`，按脚本提示初始化独立演示数据库。
4. 执行 `powershell -ExecutionPolicy Bypass -File scripts/start.ps1`，访问 <http://127.0.0.1:8086/library/>。脚本需要可用的 Tomcat 9 安装包并执行校验；首次使用可能需要联网下载对应版本。

## 技术与验证边界

拓展版提供关键词与向量混合检索、基于工具调用的助手、确认卡、多模态封面和版权页识别；条码使用 ZXing 在后端解码。历史测试及局限见 `modern/backend/docs/VERIFICATION.md`。示例运行结果不保证所有网络、图片或模型场景都通过。

演示学生为虚构；书目基本信息及其来源见数据目录中的记录。外部图书封面、完整出版物和第三方资料不随项目代码以 MIT 许可证授权。使用外部 API 须自行确认其服务条款。

## 许可证

项目原创代码采用 MIT License，见 [LICENSE](LICENSE)。依赖及其他第三方内容分别遵循各自许可证。
