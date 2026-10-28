# 本机验证记录

验证日期：2026-10-28。使用 Java 21、独立的 MySQL 23306、Redis 6381、Mem0 18888、MinIO 29000，以及本机 PostgreSQL 17 中的 `corporate_travel` 数据库。

## 自动化验证

| 检查 | 结果 |
| --- | --- |
| Maven `verify`（配置真实 `PGVECTOR_TEST_URL`） | 275 项测试通过，0 失败，0 跳过；独立 Spring Boot JAR 打包成功 |
| Mem0 Python HTTP 适配测试 | 3 项通过 |
| 前端 `npm ci`、`npm run build` | 依赖安装、TypeScript 与 Vite 构建通过 |
| 三套 Compose `config --quiet` | 通过；这是配置检查，不代表容器已启动 |
| PowerShell 脚本解析 | 通过 |
| Git 提交候选文件检查 | 没有包含本机已知凭据、env 私密文件或本机数据目录；Office 语料 XML 一并检查 |

记忆测试覆盖鉴权、用户与项目隔离、HTTP 失败传播和偏好 API 的 503 响应。数据库测试覆盖余弦检索、语料隔离、重复导入、追加写入、失败事务回滚与 Embedding 模型不匹配。观测测试使用真实 OTel SDK 验证 Span 层级、Langfuse 类型、实际 Token 用量、脱敏、关闭内容采集，以及异常时结束 Span。

## 实际服务验证

- 前端、后端健康检查与 MinIO readiness 均返回 HTTP 200。
- 演示用户能够登录，偏好保存经过真实 Mem0 LLM 抽取并写入 pgvector，读取返回长期记忆及结构化偏好。
- Mem0 重启后仍能检索之前写入的测试记忆；不同用户或项目的搜索不返回该记忆。
- SSE 对话返回正常消息和后续建议事件。
- 景点、差旅制度与注意事项分别导入 2443、4、3 个知识分块，HNSW 索引已建立。
- 使用真实 `text-embedding-v4` 查询差旅审批内容，制度与注意事项的最相似结果余弦分数分别约为 0.736、0.719。
- 后端重启并带导入参数时，三套语料均识别为已有相同版本并跳过 Embedding 请求。

## 尚未验证的外部链路

Docker Engine 当前未运行，Langfuse 与 Collector 容器尚未启动，因此还没有验证 Langfuse UI 实际接收数据。已完成 Java 侧 OTel 导出内容测试和部署配置检查。启用步骤见 [观测接入](observability.md)。

天气、付费签证查询、每用户途牛等第三方业务仍取决于各自凭据和外部服务；新的业务库没有复制旧库中的个人 API key。旧百炼记忆数据未自动迁移。
