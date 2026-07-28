# SemEvoSQL Semantic Evolution / Episode / MCP 验收记录

日期：2026-08-18

## 1. 结论

本轮核心重构已完成并通过代码级、数据库级、运行态和浏览器验收。SemEvoSQL 已按 roadmap 收口为：

- `Corpus Revision` 表示知识资产变化；
- `Semantic Version` 表示稳定业务语义快照；
- `Episode` 表示一次完整业务求解经历；
- `SemanticChangeSet` 是唯一可变语义工作区；
- PATCH / MINOR 自动验证后生效，MAJOR 仅人工 Promote；
- MCP Deployment 绑定 Project，不绑定固定 Semantic Version；
- Episode 创建时 pin Active Semantic Version；
- Retrieval 保持 `Exact + BM25 + Vector -> RRF -> Rerank`，PATCH 走 affected-asset incremental index。

发布门禁中，产品自身可控部分均已通过。唯一未形成“最终 SQL 成功返回”的真实 MCP data-plane smoke，是当前本机存量外部 Chat Provider 返回 `403 Forbidden`；该故障已通过产品自身模型连接测试复现，属于外部模型连接阻断，不是 MCP / Episode / Durable Run / Retrieval 状态机错误。

## 2. Semantic Evolution 运行态证据

### PATCH

在隔离 acceptance stack 中完成正式 runtime correction -> Patch -> ChangeSet -> Replay -> Release：

- 正式 definition-correction API 可创建 proposal-only candidate；
- `UPDATE_RULE` Patch 通过 source fingerprint / preflight；
- high-risk operation 会把 candidate / ChangeSet 风险下限自动抬升为 `HIGH`，不能被低风险候选降级；
- Replay 使用 source Semantic Version + ChangeSet preview，不再依赖旧 Draft Version；
- 10/10 replay PASS 后 `/ready` 物化新 Semantic Version；
- `1.1.0 -> 1.1.1` 自动 Active；
- version level=`PATCH`，cause=`EPISODE_LEARNING`；
- Retrieval Generation=`INCREMENTAL`，affected asset `1/1 READY`；
- 新版本 Rule 已包含用户确认后的定义。

期间修复了两个真实兼容缺口：

1. ChangeSet 模式 `target_draft_version_id=null` 时 Replay Coordinator 仍使用旧 Draft 字段导致 NPE；
2. Replay summary 合法包含 `targetVersionId=null` 时 `Map.copyOf()` 不允许 null 导致 NPE。

两处都增加了回归覆盖。

### MINOR / MAJOR

同一 acceptance 方案已验证：

- Corpus 有 Semantic Diff 时自动产生 MINOR；
- 无 Semantic Diff 时只推进 Corpus Revision；
- MAJOR 只由人工 `Promote Business Baseline` 触发；
- MAJOR 不要求伪造 Catalog Hash 变化。

### Rollback

真实执行 Active rollback：

- Active pointer 从新版本切回旧版本；
- 不创建新的 Semantic Version；
- MCP deployment id / endpoint / status / update time 不变化；
- 随后可把 Active 恢复到最新已发布版本。

## 3. Episode 与版本 pinning

运行态已验证：

- Active 从 `1.1.0` 切到 `1.1.1` 后，旧 Episode 仍固定在旧 Semantic Version / state hash；
- 新 Episode 自动绑定新的 Active Semantic Version；
- 相同 MCP `requestId` 重试返回同一个 Episode / Run，不重复 admission；
- backend 重启后 RESERVED / RUNNING handle 可继续由 durable runtime 恢复；
- Run 最终失败时会持久化 `FAILED + RUN_FAILED`，不会永久伪装成 RUNNING。

## 4. MCP 验收

### 已通过

真实 `/mcp` JSON-RPC 已验证：

- initialize 成功；
- public tool surface 收口为 `query` + `query_status`；
- deployment 只绑定 Project；
- PATCH / MINOR / MAJOR / rollback 不要求 redeploy；
- deployment 管理面测试在版本切换后仍 `ok=true / running=true / bindingCurrent=true`；
- requestId 幂等；
- Episode / Run / Attempt 持久化；
- restart recovery；
- 失败状态可通过 handle / query_status 恢复；
- 普通 MCP surface 不暴露 Semantic Catalog mutation tools。

同时修正了管理 UI 中残留的旧 5-tool BYO-Agent 使用说明，使其与真实 2-tool Episode API 一致。

### 外部阻断

真实 data-plane query 已经过：

`MCP auth -> query admission -> Episode -> Run -> Request Analysis -> Semantic Retrieval -> Rerank -> Semantic Blueprint`

本轮还修复了 CPU Rerank 性能问题：

- 原逻辑把小 Catalog 的全部 19 个候选送入 2B Cross-Encoder，稳定触发 60s read timeout；
- 现改为全量 Exact/BM25/Vector + RRF，Cross-Encoder 只精排 RRF Top-4，再用 RRF tail 补足 caller limit；
- Top-4 后真实 Rerank 在约 34 秒完成，不再触发 60s timeout；
- 随后 Run 才在 Semantic Blueprint 的 Chat 调用阶段被外部 Provider `403 Forbidden` 拒绝。

产品自身 `/api/model-config/test` 对当前 active Chat connection 同样失败，因此该项记录为：

`BLOCKED_EXTERNAL: current Chat provider/credential rejects the request`

未为了“验收变绿”伪造 LLM 输出或放宽业务状态机。

## 5. Migration / Fresh Bootstrap

### 现有数据库升级

真实旧 metadata DB 在当前代码启动时发现 `qw_external_query_handle.conversation_id` 缺失。没有修改已执行的 V29，而是新增 forward-only：

`V30__external_query_handle_conversation.sql`

原有 DB 通过 Flyway 原地升级到 V30，现有数据保留，backend / worker / frontend 恢复 healthy。

### Fresh DB

使用全新 Compose project、network、metadata volume 和 uploads volume 启动：

- backend healthy；
- execution-worker healthy；
- frontend healthy；
- Flyway V1 -> V30 全部成功；
- V30 `conversation_id` 列存在；
- backend `/actuator/health` = `UP`；
- Web Console HTTP 200，title=`SemEvoSQL`。

验收后隔离 stack / volumes / network 已删除，未清理原开发环境。

## 6. Browser Acceptance

新增零第三方依赖的 `npm run browser:acceptance`：

- 直接启动本机 Chrome/Chromium headless；
- 通过 Chrome DevTools Protocol 导航真实 SPA；
- 等待页面 load + 异步渲染后读取实际 DOM；
- 显式关闭浏览器进程；
- 可通过 `SEMEVOSQL_WEB_URL` 指定部署；
- 可通过 `SEMEVOSQL_ACCEPTANCE_PROJECT_ID` 打开项目级验收。

当前开发环境真实 Google Chrome 验收：

1. `/projects` PASS；
2. `/admin/models` PASS；
3. `/projects/12` PASS；
4. `/projects/12?section=release` PASS；
5. `/chat?projectId=12` PASS。

结果：`5/5 PASS`。

## 7. 最终自动化门禁

已执行并通过：

- Maven `verify`：89 tests，0 failures，0 errors；
- Checkstyle：0 violations；
- Spotless：clean；
- frontend `npm run verify`：ESLint + vue-tsc + knip + production build PASS；
- `npm audit --audit-level=high`：0 vulnerabilities；
- release hygiene：PASS；
- fresh Compose bootstrap：PASS；
- browser acceptance：5/5 PASS。

## 8. 发布状态

代码层面可以进入分批 commit 收口。正式对外发布前仍建议重新配置并验证一个可用的 Chat connection，然后再补一条“真实 MCP query 最终返回 SQL/result”的外部依赖验收证据；除此之外，本轮 SemEvoSQL 领域模型、自动演进、Episode、MCP、Retrieval、Migration、Web 和发布卫生已完成收口。
