# 从 GoGo / 百炼配置迁移

本仓库是独立项目。保留原 GoGo 目录，使用独立 Git remote、端口、业务数据库、Redis 数据和 PostgreSQL 数据库。无需修改 LLMentor 父工程。

| 原配置 | 新配置 / 行为 |
| --- | --- |
| 百炼长期记忆 workspace / memory store | `MEM0_API_BASE_URL`、`MEM0_API_KEY`、`MEM0_API_TYPE=self-hosted` |
| 百炼 / 进程内知识检索 | PostgreSQL `travel_knowledge_corpus`、`travel_knowledge_chunk` |
| 启动时反复构造临时向量库 | 显式 `-ImportKnowledge`，相同指纹跳过 |
| 只显示通用追踪 | Langfuse agent/generation/tool 类型、用户/会话及模型 Token 用量 |
| 业务数据库与历史会话 | 继续使用 MySQL；`DB_NAME` 必须与 `DB_URL` 中的数据库一致 |
| 用户级长期记忆查询缓存 | 删除；检索与具体 query 及项目相关，不跨查询复用结果 |

## 数据迁移范围

- 代码与内置语料已经复制，构建产物、日志、Node 依赖、本地密钥配置没有进入 Git。
- 旧百炼内存数据没有自动导出，也没有迁移到 Mem0。需要账户权限和旧数据导出后才能执行迁移。
- 新业务库默认只导入仓库中的演示数据，不复制旧数据库的真实聊天、订单或个人 API key。
- 内置 Word / Excel 语料通过新导入器重新索引；更新失败不会删除当前有效知识库。
- 切换 Embedding 模型需要重新导入。模型名、维度和源文件参与版本计算；维度改变需要数据库迁移。

## 凭据与数据位置

`.env.integrations` 保存初始化生成的服务凭据，`.env.local` 保存当前电脑的覆盖项。不要将它们加入版本库。`.local-data` 保存本机 MySQL、Redis、MinIO、Mem0 历史数据；Docker 模式使用各自命名卷。PostgreSQL 数据存放于其自身数据目录。

如果复用旧 MySQL 数据库，必须使用旧 `API_KEY_ENCRYPT_SECRET` 才能解密已有工具 key；新库使用独立生成的密钥。

## 回退

停止新项目本机进程或对应 Compose 项目后，原 GoGo 目录和原库保持可用。新旧系统的 Mem0 / 百炼数据不会自动同步。不要删除 Docker volumes 或 `.local-data`，除非明确不再需要其中的数据。
