<div align="center">

# QueryWeaver

**A self-evolving NL2SQL platform**

Natural Language → Semantic Blueprint → Verified SQL → Durable Execution → Continuous Learning

LLMs understand business semantics. QueryWeaver compiles them into verifiable SQL, preflights execution, and learns from successful query trajectories.

[![GitHub Actions](https://img.shields.io/badge/-FFFFFF?style=flat-square&logo=githubactions&logoColor=2088FF)](https://github.com/lgs0809/QueryWeaver/actions/workflows/ci.yml)[![CI](https://img.shields.io/github/actions/workflow/status/lgs0809/QueryWeaver/ci.yml?branch=main&style=flat-square&label=CI&labelColor=2D333B)](https://github.com/lgs0809/QueryWeaver/actions/workflows/ci.yml)
[![GitHub](https://img.shields.io/badge/-FFFFFF?style=flat-square&logo=github&logoColor=181717)](https://github.com/lgs0809/QueryWeaver/tags)[![Release](https://img.shields.io/github/v/tag/lgs0809/QueryWeaver?sort=semver&style=flat-square&label=release&color=181717&labelColor=2D333B)](https://github.com/lgs0809/QueryWeaver/tags)
![OpenJDK](https://img.shields.io/badge/-FFFFFF?style=flat-square&logo=openjdk&logoColor=ED8B00)![Java 17](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&labelColor=2D333B)
![Spring Boot](https://img.shields.io/badge/-FFFFFF?style=flat-square&logo=springboot&logoColor=6DB33F)![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?style=flat-square&labelColor=2D333B)
![Vue](https://img.shields.io/badge/-FFFFFF?style=flat-square&logo=vuedotjs&logoColor=4FC08D)![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D?style=flat-square&labelColor=2D333B)
![PostgreSQL](https://img.shields.io/badge/-FFFFFF?style=flat-square&logo=postgresql&logoColor=4169E1)![PostgreSQL + pgvector](https://img.shields.io/badge/PostgreSQL-pgvector-4169E1?style=flat-square&labelColor=2D333B)
[![Apache](https://img.shields.io/badge/-FFFFFF?style=flat-square&logo=apache&logoColor=D22128)![License](https://img.shields.io/badge/License-Apache--2.0-D22128?style=flat-square&labelColor=2D333B)](LICENSE)

</div>

QueryWeaver 不是“把问题直接丢给大模型生成 SQL”的薄封装。它以 **Semantic Catalog** 为事实基础，让大模型生成结构化 **Semantic Blueprint**，再由 **Semantic Compiler + Query Preflight** 产出并验证 **Verified SQL**；运行时继续提供澄清、审批、Review/Repair、持久化恢复和语义进化能力。

它既可以作为完整的问数产品使用，也可以把某个项目的问数能力一键发布为 **Project MCP Server**，供外部 Agent 复用同一套语义、权限和执行治理。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| **Semantic-first NL2SQL** | LLM 生成结构化 `SemanticBlueprint`，再由 Semantic Compiler 与 Query Preflight 产出 Verified SQL，而不是直接信任模型 SQL |
| **Hybrid Semantic Retrieval** | Exact + BM25 + pgvector 混合召回，并通过 RRF 融合多个召回通道 |
| **Clarification & Approval** | 运行时歧义必须澄清；审批独立支持 `REQUIRE_APPROVAL` / `AUTO_EXECUTE` |
| **Review & Repair** | 执行后按 `PASS / RETRY_SQL / REPLAN / RERETRIEVE / CLARIFY / FAIL` 路由闭环修复 |
| **Durable Runtime** | QueryRun、RunEvent、Checkpoint、Lease、Idempotency 支持断线、重启和失败恢复 |
| **Semantic Evolution** | Validated Query Case、Semantic Correction、Replay 将高质量运行结果沉淀回项目语义资产 |
| **Multi-source Execution** | Query / Source / Execution 三层拆解，在统一 QueryTask DAG 中受控执行多数据源查询 |
| **Project MCP** | 将项目级问数能力发布为标准 MCP 数据面，外部 Agent 无需复制 QueryWeaver 内部逻辑 |

## 架构

```mermaid
flowchart LR
    U[User / External Agent] --> R[QueryWeaver Runtime]
    R --> C[Context & Clarification]
    C --> S[Hybrid Semantic Retrieval]
    S --> B[Semantic Blueprint]
    B --> G[Semantic Compiler]
    G --> P[Query Preflight]
    P --> Q[Verified SQL]
    Q --> D[(Business Datasources)]
    D --> V[Post-execution Review]
    V --> A[Grounded Answer]
    V --> E[Repair & Semantic Evolution]
    E --> S
    R --> M[Project MCP]
```

核心执行链路：

```text
QueryTask
  → SemanticBlueprint
  → SourceSubPlan
  → SemanticCompiler
  → QueryPreflight
  → Verified SQL
  → SQL Execution
  → Post-execution Review
  → Grounded Synthesis
```

## 快速开始

### 1. 环境要求

完整本地部署只要求：

- Docker + Docker Compose
- 一个兼容 OpenAI API 的 Chat Model
- 一个兼容 OpenAI Embeddings API 的 Embedding Model

如果要直接从源码构建后端，还需要 **JDK 17**；开发前端需要 **Node.js 22.12+ + npm**。

### 2. 准备配置

```bash
cp config/model-env.example .env.local
cp deploy/queryweaver/.env.example deploy/queryweaver/.env
```

然后填写模型地址/API Key、数据库密码和安全相关配置。真实密钥文件已被 `.gitignore` 排除，不应提交到仓库。

Embedding 可以是本机服务，也可以是外部 OpenAI-compatible Embeddings API。`QUERYWEAVER_MANAGE_EMBEDDING_MODEL=auto` 时，仅对本地 `localhost` / `host.docker.internal` 地址尝试管理本地 embedding 容器；外部地址不会依赖任何本机预构建镜像。

### 3. 一键构建并启动

```bash
QUERYWEAVER_BUILD=true ./scripts/start-queryweaver.sh
```

启动完成后：

- Web Console: `http://127.0.0.1:23000/queryweaver`
- Backend: `http://127.0.0.1:28065`
- Health Check: `http://127.0.0.1:28065/actuator/health`

## 从源码运行

### Backend

```bash
./mvnw -pl backend -am -Dmaven.test.skip=true package
java -jar backend/target/queryweaver.jar
```

QueryWeaver 使用 Maven Wrapper，因此**不要求开发机预装 Maven**。只需让 JDK 17 正确出现在当前环境中，不绑定任何特定机器的 `JAVA_HOME` 路径。

后端 Maven 坐标：

```text
cn.lgs.queryweaver:queryweaver
```

可执行产物固定为：

```text
backend/target/queryweaver.jar
```

### Frontend

```bash
cd frontend
npm ci
npm run dev
```

生产构建：

```bash
npm run build
```

## Project MCP

QueryWeaver 可以把一个已发布项目直接暴露为 MCP 数据面。外部 Agent 通过统一工具访问项目语义能力，而不是自行生成和执行不受治理的 SQL。

默认提供五个工具：

```text
search_semantics
get_semantic_context
validate_query_plan
execute_query_plan
get_query_result
```

MCP 与 Web Chat 共享同一套项目版本、语义目录、权限、执行治理和 Durable Result。

## 配置约定

QueryWeaver 自身的 Spring 配置使用独立命名空间：

```text
cn.lgs.queryweaver.*
```

运行环境变量统一使用：

```text
QUERYWEAVER_*
```

主要配置入口：

| 文件 | 用途 |
| --- | --- |
| `.env.local` | Chat / Embedding 等模型服务 |
| `deploy/queryweaver/.env` | Compose、数据库、端口和部署安全配置 |
| `backend/src/main/resources/application.yml` | 后端默认运行配置 |

## 仓库结构

```text
QueryWeaver/
├── backend/                 # Spring Boot 后端与 QueryWeaver Runtime
├── frontend/                # Vue 3 Web Console
├── config/                  # 构建与模型配置模板
│   └── checkstyle/
├── deploy/                  # Dockerfile、Compose 与初始化资源
│   └── queryweaver/
├── scripts/                 # 启动、模型准备与部署辅助脚本
├── .mvn/                    # Maven Wrapper 运行时文件
├── mvnw
├── pom.xml
└── README.md
```

`.mvn/` 与 `mvnw` 是 Maven Wrapper 的组成部分，应该随仓库提交；它们保证不同开发机和 CI 环境可以使用一致的 Maven 启动方式。

## License

Apache License 2.0
