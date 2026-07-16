<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="query-examples" v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>已验证案例</h2>
        <p>沉淀经过真实执行和治理确认的历史问法，用于帮助后续查询更稳定地复用已验证业务理解。</p>
      </div>
      <div class="filters">
        <el-select v-model="selectedVersionId" placeholder="选择版本" @change="load">
          <el-option
            v-for="version in versions"
            :key="version.id"
            :label="`${version.versionNumber} · ${version.status}`"
            :value="version.id"
          />
        </el-select>
        <el-select v-model="status" @change="load">
          <el-option label="全部状态" value="" />
          <el-option label="待确认" value="CANDIDATE" />
          <el-option label="可复用" value="APPROVED" />
          <el-option label="已暂停复用" value="QUARANTINED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已过期" value="STALE" />
        </el-select>
        <el-select v-model="rebindStatus" @change="load">
          <el-option label="全部版本适配状态" value="" />
          <el-option
            v-for="item in rebindStatuses"
            :key="item"
            :label="rebindLabel(item)"
            :value="item"
          />
        </el-select>
        <el-button @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="系统只复用与当前业务模型和安全规则相容、并且已经通过治理确认的案例；每次查询仍会重新生成、校验并执行。"
    />

    <div v-if="indexReadiness" class="index-readiness">
      <div>
        <div class="index-summary">
          <el-tag :type="indexStatusType(indexReadiness.status)">
            {{ indexStatusLabel(indexReadiness.status) }}
          </el-tag>
          <span>
            向量覆盖 {{ indexReadiness.vectorCount }} / {{ indexReadiness.approvedCaseCount }}
            <template v-if="indexReadiness.dimension"> · {{ indexReadiness.dimension }} 维</template>
          </span>
        </div>
        <div class="subtle">{{ indexReadiness.detail }}</div>
      </div>
      <el-button
        v-if="props.canReindex"
        type="primary"
        plain
        :loading="reindexing"
        @click="reindexCurrentProject"
      >
        重建本项目向量索引
      </el-button>
    </div>

    <el-table :data="examples" class="example-table" empty-text="暂无已验证案例">
      <el-table-column label="案例" min-width="290">
        <template #default="scope">
          <strong>{{ scope.row.normalized_question }}</strong>
          <div
            v-if="
              scope.row.original_question &&
              scope.row.original_question !== scope.row.normalized_question
            "
            class="subtle"
          >
            原问句：{{ scope.row.original_question }}
          </div>
          <div class="subtle">
            {{ scope.row.intent_type || 'UNKNOWN_INTENT' }}
            · {{ scope.row.conversation_independent ? '独立案例' : '上下文绑定案例' }}
          </div>
          <el-tag v-if="!scope.row.typed_ir_json" size="small" type="danger" class="inline-tag">
            历史案例：需人工验证
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="复用状态" min-width="220">
        <template #default="scope">
          <el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
          <el-tag class="inline-tag" :type="rebindType(scope.row.rebind_status)" effect="plain">
            {{ rebindLabel(scope.row.rebind_status) }}
          </el-tag>
          <div
            v-if="['NEEDS_REVIEW', 'INVALID', 'SUPERSEDED'].includes(scope.row.rebind_status)"
            class="danger-note"
          >
            当前不会自动复用这个历史案例
          </div>
          <div v-if="scope.row.status === 'QUARANTINED'" class="danger-note">
            {{ scope.row.quarantine_reason || '该案例因复用质量问题已暂停使用' }}
            <div class="subtle">{{ formatTime(scope.row.quarantine_time) }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="版本适配" min-width="210">
        <template #default="scope">
          <div>业务模型 v{{ versionNumber(scope.row.project_version_id) }}</div>
          <div v-if="scope.row.source_example_id" class="subtle">适配自历史案例</div>
          <div v-else class="subtle">当前版本原生案例</div>
        </template>
      </el-table-column>
      <el-table-column label="质量与使用" min-width="190">
        <template #default="scope">
          <div>{{ scope.row.quality_summary || '暂无摘要' }}</div>
          <div class="subtle">
            命中 {{ scope.row.recall_count || 0 }} · 采用 {{ scope.row.adopted_count || 0 }} · 失败
            {{ scope.row.failed_after_recall_count || 0 }} · 连续问题
            {{ scope.row.consecutive_recall_issue_count || 0 }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="最近更新" width="180">
        <template #default="scope">
          {{ formatTime(scope.row.update_time || scope.row.create_time) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="210" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="openDetail(scope.row)">详情与血缘</el-button>
          <template v-if="canGovern && scope.row.status === 'QUARANTINED'">
            <el-button
              link
              type="success"
              :loading="governingId === scope.row.id"
              @click="governQuarantine(scope.row, 'RESTORE')"
            >
              恢复
            </el-button>
            <el-button
              link
              type="danger"
              :loading="governingId === scope.row.id"
              @click="governQuarantine(scope.row, 'REJECT')"
            >
              拒绝隔离案例
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawerVisible" title="结构化 Query Case 详情" size="76%">
      <template v-if="selected">
        <el-alert
          v-if="!selected.typed_ir_json"
          type="error"
          :closable="false"
          show-icon
          title="该旧案例缺少可重放的结构化查询表示，只能作为历史证据，不能自动回归或参与在线复用。"
        />
        <el-alert
          v-else-if="['NEEDS_REVIEW', 'INVALID', 'SUPERSEDED'].includes(selected.rebind_status)"
          type="warning"
          :closable="false"
          show-icon
          :title="`${rebindLabel(selected.rebind_status)}：该案例不会参与在线召回。`"
        />

        <el-descriptions :column="2" border>
          <el-descriptions-item label="Case ID">
            <code>{{ selected.id }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Status">
            <el-tag :type="statusType(selected.status)">{{ statusLabel(selected.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Catalog Hash">
            <code>{{ selected.catalog_hash }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Lineage Hash">
            <code>{{ selected.evidence_lineage_hash || '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Original Question" :span="2">
            {{ selected.original_question || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="Normalized Question" :span="2">
            {{ selected.normalized_question }}
          </el-descriptions-item>
          <el-descriptions-item label="Intent Type">
            {{ selected.intent_type || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="Conversation Independent">
            {{ truth(selected.conversation_independent) ? 'true' : 'false' }}
          </el-descriptions-item>
          <el-descriptions-item label="Context Hash">
            <code>{{ selected.context_hash || '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Result Schema Hash">
            <code>{{ selected.result_schema_hash || '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Canonical Shape Hash" :span="2">
            <code>{{ selected.canonical_shape_hash || '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Rebind Status">
            {{ rebindLabel(selected.rebind_status) }}
          </el-descriptions-item>
          <el-descriptions-item label="Source Example">
            {{ selected.source_example_id || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="Recall / Failure / Consecutive">
            {{ selected.recall_count || 0 }} / {{ selected.failed_after_recall_count || 0 }} /
            {{ selected.consecutive_recall_issue_count || 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="Quarantine Time">
            {{ formatTime(selected.quarantine_time) }}
          </el-descriptions-item>
          <el-descriptions-item label="Quarantine Reason" :span="2">
            {{ selected.quarantine_reason || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3>Root Evidence 与派生血缘</h3>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="Root Evidence IDs">
            <code>{{ formatIds(selected.root_evidence_ids) }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Derived From Case IDs">
            <code>{{ formatIds(selected.derived_from_case_ids) }}</code>
          </el-descriptions-item>
        </el-descriptions>

        <h3>Resolved Time Range</h3>
        <pre>{{ pretty(selected.resolved_time_range_json) }}</pre>
        <h3>Semantic Query Plan</h3>
        <pre>{{ pretty(selected.typed_ir_json) }}</pre>
        <h3>Resolution</h3>
        <pre>{{ pretty(selected.resolution_json) }}</pre>
        <h3>Quality Proof</h3>
        <pre>{{ pretty(selected.quality_proof_json) }}</pre>

        <h3>当前版本重新编译 SQL</h3>
        <p class="section-note">
          该 SQL 仅是当前案例的验证产物；在线命中时仍会使用 IR Hint 重新编译并经过 Guard。
        </p>
        <pre>{{ selected.sql_text || '-' }}</pre>
        <template v-if="selected.historical_sql_text">
          <h3>旧版本 SQL 基线</h3>
          <pre>{{ selected.historical_sql_text }}</pre>
        </template>

        <h3>资产引用与 Fingerprint</h3>
        <el-table :data="selected.assetReferences || []" empty-text="暂无资产引用">
          <el-table-column prop="asset_type" label="类型" width="150" />
          <el-table-column prop="asset_key" label="Asset Key" min-width="240" />
          <el-table-column prop="asset_fingerprint" label="Fingerprint" min-width="300">
            <template #default="scope">
              <code>{{ scope.row.asset_fingerprint || '-' }}</code>
            </template>
          </el-table-column>
        </el-table>

        <h3>跨版本 Rebind 血缘</h3>
        <div class="lineage">
          <div class="lineage-node">
            <strong>{{ selected.source_example_id ? '来源 Case' : '当前 Case' }}</strong>
            <code>{{ selected.source_example_id || selected.id }}</code>
          </div>
          <span>→</span>
          <div class="lineage-node">
            <strong>当前 Case</strong>
            <code>{{ selected.id }}</code>
            <small>{{ rebindLabel(selected.rebind_status) }}</small>
          </div>
        </div>
        <el-table :data="selected.rebinds || []" empty-text="暂无下游 Rebind">
          <el-table-column prop="target_version_id" label="目标版本" width="120" />
          <el-table-column prop="target_example_id" label="目标 Case" min-width="220" />
          <el-table-column prop="status" label="状态" width="170" />
          <el-table-column prop="reason" label="原因 / 不兼容说明" min-width="300" />
        </el-table>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
  import { onMounted, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    queryWeaverService,
    type QueryCaseIndexReadiness,
    type SemanticProjectVersion,
    type ValidatedQueryExample,
  } from '@/services/queryweaver';

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    activeVersionId?: number;
    canGovern?: boolean;
    canReindex?: boolean;
  }>();
  const canGovern = props.canGovern !== false;

  const selectedVersionId = ref<number>();
  const status = ref('');
  const rebindStatus = ref('');
  const examples = ref<ValidatedQueryExample[]>([]);
  const selected = ref<ValidatedQueryExample>();
  const indexReadiness = ref<QueryCaseIndexReadiness>();
  const loading = ref(false);
  const reindexing = ref(false);
  const governingId = ref('');
  const drawerVisible = ref(false);
  const rebindStatuses = [
    'VALID',
    'NEEDS_REBIND',
    'REBOUND_PENDING_REPLAY',
    'REBOUND',
    'NEEDS_REVIEW',
    'INVALID',
    'SUPERSEDED',
  ];

  const preferredVersion = () =>
    props.activeVersionId ||
    props.versions.find(item => item.status === 'PUBLISHED')?.id ||
    props.versions[0]?.id;

  const load = async () => {
    if (!props.projectId) return;
    loading.value = true;
    try {
      examples.value = await queryWeaverService.queryExamples(
        props.projectId,
        selectedVersionId.value,
        status.value,
        rebindStatus.value,
      );
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : 'Query Case 加载失败');
    } finally {
      loading.value = false;
    }
  };

  const loadIndexReadiness = async () => {
    if (!props.projectId) return;
    try {
      indexReadiness.value = await queryWeaverService.queryCaseIndexReadiness(props.projectId);
    } catch (error) {
      indexReadiness.value = undefined;
      ElMessage.warning(error instanceof Error ? error.message : 'Query Case 索引状态加载失败');
    }
  };

  const refreshAll = async () => {
    await Promise.all([load(), loadIndexReadiness()]);
  };

  const reindexCurrentProject = async () => {
    try {
      await ElMessageBox.confirm(
        '将使用当前 Embedding 模型重建本项目已批准 Query Case 的向量索引。重建期间 Exact/BM25 召回仍可使用。',
        '重建 Query Case 向量索引',
        { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' },
      );
      reindexing.value = true;
      const result = await queryWeaverService.reindexQueryCaseIndex(props.projectId);
      ElMessage.success(`Query Case 向量索引已重建：${result.indexedEmbeddings} 条`);
      await loadIndexReadiness();
    } catch (error) {
      if (error instanceof Error && error.message !== 'cancel') ElMessage.error(error.message);
    } finally {
      reindexing.value = false;
    }
  };

  const openDetail = async (example: ValidatedQueryExample) => {
    drawerVisible.value = true;
    try {
      selected.value = await queryWeaverService.queryExample(props.projectId, example.id);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : 'Query Case 详情加载失败');
    }
  };

  const governQuarantine = async (example: ValidatedQueryExample, action: 'RESTORE' | 'REJECT') => {
    try {
      const response = await ElMessageBox.prompt(
        action === 'RESTORE'
          ? '说明独立 Replay、人工复核或其他足以恢复该案例的证据。'
          : '说明为什么该隔离案例应被永久拒绝。',
        action === 'RESTORE' ? '恢复隔离 Query Case' : '拒绝隔离 Query Case',
        {
          inputType: 'textarea',
          inputValidator: value => Boolean(value.trim()) || '必须填写治理原因',
        },
      );
      governingId.value = example.id;
      if (action === 'RESTORE') {
        await queryWeaverService.restoreQuarantinedQueryExample(
          props.projectId,
          example.id,
          response.value,
        );
        ElMessage.success('Query Case 已从隔离状态恢复');
      } else {
        await queryWeaverService.rejectQuarantinedQueryExample(
          props.projectId,
          example.id,
          response.value,
        );
        ElMessage.success('隔离 Query Case 已拒绝');
      }
      await load();
    } catch (error) {
      if (error instanceof Error && error.message !== 'cancel') ElMessage.error(error.message);
    } finally {
      governingId.value = '';
    }
  };

  const statusType = (value: string) => {
    if (value === 'APPROVED') return 'success';
    if (['QUARANTINED', 'REJECTED'].includes(value)) return 'danger';
    if (value === 'STALE') return 'info';
    return 'warning';
  };
  const rebindType = (value: string) => {
    if (['VALID', 'REBOUND'].includes(value)) return 'success';
    if (['NEEDS_REVIEW', 'INVALID'].includes(value)) return 'danger';
    if (value === 'SUPERSEDED') return 'info';
    return 'warning';
  };
  const statusLabels: Record<string, string> = {
    CANDIDATE: '候选（不参与召回）',
    APPROVED: '可召回',
    QUARANTINED: '已隔离',
    REJECTED: '已拒绝',
    STALE: '已过期',
  };
  const rebindLabels: Record<string, string> = {
    VALID: '当前版本可用',
    NEEDS_REBIND: '等待版本适配',
    REBOUND_PENDING_REPLAY: '已适配，等待回归验证',
    REBOUND: '版本适配已验证',
    NEEDS_REVIEW: '需要人工复核',
    INVALID: '无效',
    SUPERSEDED: '已被替代',
  };
  const statusLabel = (value: string) => statusLabels[value] || value;
  const rebindLabel = (value: string) => rebindLabels[value] || value;
  const indexStatusLabel = (value: QueryCaseIndexReadiness['status']) =>
    ({
      INDEX_READY: '向量索引就绪',
      PARTIAL: '向量索引部分就绪',
      REINDEX_REQUIRED: '需要重建向量索引',
      LEXICAL_ONLY: '当前仅 Exact / BM25',
    })[value];
  const indexStatusType = (value: QueryCaseIndexReadiness['status']) => {
    if (value === 'INDEX_READY') return 'success';
    if (value === 'PARTIAL' || value === 'REINDEX_REQUIRED') return 'warning';
    return 'info';
  };
  const truth = (value: boolean | number) => value === true || value === 1;
  const versionNumber = (versionId: number) =>
    props.versions.find(item => item.id === versionId)?.versionNumber || String(versionId);
  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');
  const pretty = (value?: string) => {
    try {
      return JSON.stringify(JSON.parse(value || '{}'), null, 2);
    } catch {
      return value || '-';
    }
  };
  const formatIds = (value?: string) => {
    try {
      const parsed = JSON.parse(value || '[]');
      return Array.isArray(parsed) && parsed.length ? parsed.join(', ') : '-';
    } catch {
      return value || '-';
    }
  };

  watch(
    () => [props.activeVersionId, props.versions.length],
    () => {
      if (!selectedVersionId.value) selectedVersionId.value = preferredVersion();
      void refreshAll();
    },
  );
  onMounted(() => {
    selectedVersionId.value = preferredVersion();
    void refreshAll();
  });
</script>

<style scoped>
  .query-examples {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .toolbar {
    display: flex;
    justify-content: space-between;
    gap: 20px;
    align-items: flex-start;
  }
  .toolbar h2 {
    margin: 0 0 6px;
    color: #0f172a;
  }
  .toolbar p,
  .section-note {
    margin: 0;
    color: #64748b;
  }
  .filters {
    display: flex;
    gap: 10px;
    min-width: 720px;
    justify-content: flex-end;
  }
  .filters .el-select {
    width: 190px;
  }
  .index-readiness {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 14px 16px;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    background: #f8fafc;
  }
  .index-summary {
    display: flex;
    align-items: center;
    gap: 10px;
    color: #334155;
  }
  .subtle {
    margin-top: 5px;
    color: #94a3b8;
    font-size: 12px;
  }
  .inline-tag {
    margin: 6px 0 0 6px;
  }
  .danger-note {
    margin-top: 8px;
    color: #dc2626;
    font-size: 12px;
    font-weight: 600;
  }
  pre {
    max-height: 320px;
    margin: 8px 0 0;
    padding: 12px;
    overflow: auto;
    border-radius: 8px;
    background: #f8fafc;
    white-space: pre-wrap;
    word-break: break-word;
  }
  code {
    white-space: normal;
    word-break: break-all;
    font-size: 12px;
  }
  h3 {
    margin: 24px 0 10px;
  }
  .lineage {
    display: flex;
    align-items: center;
    gap: 16px;
    margin: 12px 0;
  }
  .lineage-node {
    display: grid;
    gap: 6px;
    min-width: 220px;
    padding: 12px;
    border: 1px solid #dbeafe;
    border-radius: 10px;
    background: #eff6ff;
  }
  .lineage-node small {
    color: #64748b;
  }
  @media (max-width: 1100px) {
    .toolbar {
      flex-direction: column;
    }
    .filters {
      min-width: 0;
      width: 100%;
      flex-wrap: wrap;
      justify-content: flex-start;
    }
  }
</style>
