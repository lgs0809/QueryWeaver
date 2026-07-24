<div align="center">

# QueryWeaver

**A self-evolving NL2SQL platform**

Natural Language → Semantic Blueprint → Verified SQL → Durable Execution → Continuous Learning

[![GitHub Actions](https://img.shields.io/badge/-E9F3FF?style=flat-square&logo=githubactions&logoColor=2088FF)](https://github.com/lgs0809/QueryWeaver/actions/workflows/ci.yml)[![CI](https://img.shields.io/github/actions/workflow/status/lgs0809/QueryWeaver/ci.yml?branch=main&style=flat-square&label=CI&labelColor=2D333B)](https://github.com/lgs0809/QueryWeaver/actions/workflows/ci.yml)
[![GitHub](https://img.shields.io/badge/-E8E8E8?style=flat-square&logo=github&logoColor=181717)](https://github.com/lgs0809/QueryWeaver/tags)[![Release](https://img.shields.io/github/v/tag/lgs0809/QueryWeaver?sort=semver&style=flat-square&label=release&color=5F70E1&labelColor=2D333B)](https://github.com/lgs0809/QueryWeaver/tags)
![OpenJDK](https://img.shields.io/badge/-FDF3E6?style=flat-square&logo=openjdk&logoColor=ED8B00)![Java 17](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&labelColor=2D333B)
![Spring Boot](https://img.shields.io/badge/-F0F7EC?style=flat-square&logo=springboot&logoColor=6DB33F)![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?style=flat-square&labelColor=2D333B)
![Vue](https://img.shields.io/badge/-EDF9F4?style=flat-square&logo=vuedotjs&logoColor=4FC08D)![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D?style=flat-square&labelColor=2D333B)
![PostgreSQL](https://img.shields.io/badge/-ECF0FC?style=flat-square&logo=postgresql&logoColor=4169E1)![PostgreSQL + pgvector](https://img.shields.io/badge/PostgreSQL-pgvector-4169E1?style=flat-square&labelColor=2D333B)
[![Apache](https://img.shields.io/badge/-FBE9EA?style=flat-square&logo=apache&logoColor=D22128)![License](https://img.shields.io/badge/License-Apache--2.0-D22128?style=flat-square&labelColor=2D333B)](LICENSE)

</div>

QueryWeaver turns business questions into verified, read-only SQL. Instead of letting an LLM directly generate and execute arbitrary SQL, it places a governed semantic layer, deterministic compilation, and query preflight between the model and your database.

## Quick start

Requirements: Docker with Compose v2.

```bash
git clone https://github.com/lgs0809/QueryWeaver.git
cd QueryWeaver
./scripts/init-deployment-env.sh
./scripts/start-queryweaver.sh
```

Open **http://127.0.0.1:23000/queryweaver/**, then:

1. configure model providers in the Web Console;
2. connect a read-only business database;
3. create a project and publish its semantic model;
4. ask questions in natural language.

## Highlights

- **Semantic-first NL2SQL** — business metrics, dimensions, relationships, time semantics, aliases, rules, and evidence live in a versioned Semantic Catalog.
- **Verified SQL** — the model produces a Semantic Blueprint; QueryWeaver compiles it, applies SQL policy checks, runs preflight validation, and only then executes against a read-only data source.
- **Hybrid retrieval** — Exact, BM25, and Vector retrieval are fused with RRF and refined by Rerank before planning.
- **Durable execution** — query runs, checkpoints, clarification, evidence, review, and recovery survive browser or network interruptions.
- **Continuous learning** — validated corrections and successful query cases can be replayed and published into later semantic versions.
- **Agent-ready** — published projects can expose governed query capabilities through Streamable HTTP MCP.

## How it works

```text
Natural-language question
          │
          ▼
 Exact + BM25 + Vector → RRF → Rerank
          │
          ▼
  Semantic Blueprint
          │
          ▼
 Compiler + Query Preflight
          │
          ▼
     Verified SQL
          │
          ▼
 Read-only data source
          │
          ▼
 Review → Evidence → Learning
```

The model reasons over semantic context; QueryWeaver keeps SQL generation, validation, execution, and learning inside explicit product boundaries.

## MCP

A published project can expose a project-scoped MCP endpoint for semantic search, semantic context, plan validation, governed execution, and result retrieval. External agents keep control of their own reasoning while QueryWeaver owns semantic governance and SQL safety.

## Development

Backend development requires JDK 17. Frontend development requires Node.js 22.12+ and npm.

```bash
./mvnw -pl backend -am verify
```

```bash
cd frontend
npm ci
npm run build
```

CI also verifies release hygiene and portable Docker/Compose startup.

## License

QueryWeaver is released under the [Apache License 2.0](LICENSE).
