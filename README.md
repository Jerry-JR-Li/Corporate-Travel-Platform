# Corporate Travel Platform

基于 GoGo Agent 的企业差旅平台，使用 Spring Boot 3.5、Java 21、AgentScope Java 1.0.12 和 React。支持差旅对话、申请与审批、政策检索、行程规划、用户偏好和第三方旅行工具。

本仓库从本地 `LLMentor/gogo-agent` 独立复制，保留原作者 Hollis 等人的源码署名。当前仓库不依赖 LLMentor 父 POM，也不会修改原项目的 Git remote 或运行数据。

## 本次架构调整

| 能力 | 当前实现 | 配置入口 |
| --- | --- | --- |
| 长期记忆 | 自托管 Mem0，按登录用户与项目隔离；PostgreSQL 持久化 | `MEM0_*` |
| 知识库 | PostgreSQL 17 + pgvector，1024 维向量、HNSW 余弦检索 | `PGVECTOR_*` |
| Agent 观测 | Micrometer → OpenTelemetry → Collector → Langfuse | `LANGFUSE_*`、`OTEL_*` |
| 模型与 Embedding | DashScope / 通义千问、text-embedding-v4 | `DASHSCOPE_API_KEY` |
| 业务与会话 | MySQL；登录状态、事件与工具状态使用 Redis | `DB_*`、`REDIS_*` |
| 行程 HTML | MinIO 对象存储 | `MINIO_*` |

不再要求开通百炼记忆服务或百炼知识库。模型调用仍使用 DashScope。Mem0 是开源服务，自托管无需购买 Mem0 Cloud key；服务间鉴权 key 由本地初始化脚本生成。

## 从零启动（Docker 基础服务 + 本机 Java / Node）

需要 Java 21、Maven 3.9+、Node.js 22.12+，以及已经运行的 Docker Engine / Docker Desktop。以下命令在仓库根目录的 PowerShell 中执行。

```powershell
# 生成随机数据库、Mem0、Langfuse、MinIO 凭据；已有文件会保留
./scripts/init-integrations.ps1
Copy-Item .env.example .env.local
# 编辑 .env.local，填入 DASHSCOPE_API_KEY；可选填 NEWS_API_KEY
# JAVA_HOME 和 Maven、Node 应在当前终端可用
. ./scripts/load-env.ps1

docker compose --env-file .env.integrations -f compose.business.yml up -d
docker compose --env-file .env.integrations -f compose.langfuse.yml up -d
docker compose --env-file .env.integrations -f compose.integrations.yml --profile observability up -d --build

# 构建后端和前端，启动应用，首次导入三套知识语料
./start-local.ps1 -Build -ImportKnowledge
```

`.env.local` 和 `.env.integrations` 均被 Git 忽略。初始化脚本不会覆盖已有凭据。已有 `.env.local` 时直接编辑，避免用示例覆盖已有配置。Compose 使用终端环境和指定的 env 文件；`load-env.ps1` 会先加载集成默认值，再加载本地覆盖值。

访问：

| 服务 | 默认地址 |
| --- | --- |
| 应用 | http://localhost:5174 |
| 后端健康检查 | http://localhost:8081/actuator/health |
| Langfuse | http://localhost:3031 |
| MinIO 控制台 | http://localhost:29001 |
| Mem0 存活检查 | http://localhost:18888/health |

数据库初始样例账号为 `admin / 123456`、`alice / 123456`。Langfuse 登录邮箱与随机密码在 `.env.integrations` 的 `LANGFUSE_INIT_USER_EMAIL`、`LANGFUSE_INIT_USER_PASSWORD` 中；MinIO 使用 `MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`。

停止本机应用使用 `./stop-local.ps1`。停止各 Docker 项目使用对应的 `docker compose ... down`，保留卷即可保留数据。不要在需要保留数据时加 `-v`。

## 当前电脑的本机部署

此电脑使用独立的本机 MySQL（23306）、Redis（6381）、Mem0（18888）、MinIO（29000）和已有 PostgreSQL（5432）中的独立 `corporate_travel` 数据库。原 GoGo 的 8080 / 5173 保持独立。

凭据和程序路径已经配置在被 Git 忽略的 `.env.local`、`.env.integrations`；本机数据库文件在 `.local-data`，PostgreSQL 数据由本机 PostgreSQL 服务管理。重新启动：

```powershell
./start-local.ps1 -NativeMem0
# 仅在修改语料后需要加 -ImportKnowledge；相同版本自动跳过
```

这套本机配置不等同于已启动 Langfuse。启用 Langfuse 前先启动其 Compose 项目及 Collector，然后将 `.env.local` 中的 `LANGFUSE_ENABLED`、`TRACING_ENABLED` 改为 `true` 并重启后端。具体说明见 [观测接入](docs/observability.md)。不要同时启动占用相同端口的本机服务和 Docker 服务。

## Mem0 的行为

- Java 使用 AgentScope 的 Mem0 客户端，`self-hosted` 模式通过 `X-API-Key` 访问 `/memories` 和 `/search`。
- Mem0 服务封装官方 `mem0ai==2.2.1`，使用真实 LLM 抽取事实、Embedding 和 pgvector 存储，没有用临时内存模拟服务。
- 所有搜索必须带 `user_id` 和 `agent_id`；项目命名空间为 `corporate-travel-platform`，多轮会话共享同一用户的长期偏好。
- 普通聊天遇到记忆读取故障可继续；偏好管理接口采用严格读写，失败返回 HTTP 503，前端显示错误。没有仅按 userId 缓存所有查询结果。
- `/health` 仅检查服务进程存活；完整可用性需要实际保存和读取偏好。
- 本机 Python 启动方式：`uv venv --python 3.12 .venv`，然后 `uv pip install --python .venv/Scripts/python.exe -r infra/mem0/requirements.txt`，设置 `MEM0_PG_*` 后执行 `./scripts/start-native-mem0.ps1`。

## PostgreSQL 知识库

语料位于 `src/main/resources/dataset`：景点 Excel、差旅制度 Word、差旅注意事项 Word。无需用户手动上传到云知识库。

导入时解析文件、分块、生成 embedding，并在 `travel_knowledge_chunk` 中存储文本、来源、向量。`travel_knowledge_corpus` 记录文件内容、解析版本和模型配置的指纹。同版本重启不重复请求 Embedding；替换在事务内完成，失败回滚保留原语料。切换 Embedding 模型必须重新导入，否则检索会明确报错。

首次导入会调用真实 Embedding API，2443 条景点资料可能耗时数分钟。可在 `target/run/backend.stdout.log` 中查看 `Knowledge corpus ... imported successfully`。导入完成前不要把空检索视为知识库已就绪。

PostgreSQL 需要先安装 pgvector 扩展；原生 PostgreSQL 下由管理员在目标库执行 `CREATE EXTENSION vector` 并授予应用账号该库的建表权限。向量表使用固定 1024 维；不同维度必须显式迁移表和索引。MySQL 中的差旅单、用户、审批、聊天记录继续保存在 MySQL。

旧百炼记忆不会自动迁移：需要先导出旧数据，确认用户映射后再导入 Mem0。本地知识语料可直接重新索引。详见 [迁移说明](docs/migration.md)。

## 验证

```powershell
mvn.cmd -B -ntp verify
Push-Location frontend
npm.cmd ci --no-audit --no-fund
npm.cmd run build
Pop-Location

# Python HTTP 合约测试，无数据库或模型调用
./.venv/Scripts/python.exe -m unittest discover -s infra/mem0 -p test_server.py

# 启用真实 pgvector 集成测试（仅操作随机 test- 前缀语料）
. ./scripts/load-env.ps1
$env:PGVECTOR_TEST_URL = $env:PGVECTOR_URL
$env:PGVECTOR_TEST_USER = $env:PGVECTOR_USERNAME
$env:PGVECTOR_TEST_PASSWORD = $env:PGVECTOR_PASSWORD
mvn.cmd -B -ntp '-Dtest=PgVectorIntegrationTest' test
```

测试覆盖记忆鉴权和隔离、失败状态、知识库检索和事务回滚，以及真实 OTel SDK 导出的 Span 层级、Token 用量、内容脱敏和内容关闭开关。不配置 `PGVECTOR_TEST_URL` 时，真实数据库测试会显式跳过。

## 可选业务配置

NewsData.io 使用 `NEWS_API_KEY`。天气 MCP、Orizn 签证 API、高级旅行服务和每用户的途牛 key 需要单独配置，缺失时相关功能不可完整体验。行程 HTML 上传要求 MinIO 可用。新业务数据库只包含演示数据，不会复制原库里加密保存的个人 API key；请在界面重新配置需要的旅行工具凭据。

参考实现来自本机 Operations Intelligence Platform 的 Mem0、pgvector 与 Langfuse 接入方式，具体适配保持 GoGo 现有业务和 AgentScope 调用结构。
