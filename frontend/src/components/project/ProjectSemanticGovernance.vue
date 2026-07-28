<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="semantic-governance" v-loading="loading">
    <div class="governance-heading">
      <div>
        <span class="eyebrow">Semantic Governance</span>
        <h2>语义版本与知识更新</h2>
        <p>
          问数始终读取当前 Active Semantic Version；资料更新在后台形成
          ChangeSet，通过验证后再切换版本。
        </p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="error"
      type="warning"
      show-icon
      :closable="false"
      :title="error"
    />

    <div v-if="readiness" class="status-grid">
      <div class="status-card">
        <span>问数状态</span>
        <strong>{{ readiness.queryReady ? "可以问数" : "暂不可用" }}</strong>
        <el-tag
          :type="readiness.queryReady ? 'success' : 'danger'"
          effect="plain"
        >
          {{ readiness.queryReady ? "Query Ready" : "Query Blocked" }}
        </el-tag>
      </div>
      <div class="status-card">
        <span>当前语义版本</span>
        <strong>v{{ activeVersionLabel }}</strong>
        <code>{{ shortHash(readiness.activeVersion?.semanticStateHash) }}</code>
      </div>
      <div class="status-card">
        <span>知识更新</span>
        <strong>{{
          readiness.knowledgeUpdateInProgress ? "处理中" : "空闲"
        }}</strong>
        <el-tag
          :type="readiness.knowledgeUpdateInProgress ? 'warning' : 'info'"
          effect="plain"
        >
          {{ readiness.knowledgeUpdateCount }} 个 ChangeSet
        </el-tag>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="governance-tabs">
      <el-tab-pane label="Semantic Versions" name="versions">
        <el-table :data="versions" empty-text="暂无 Semantic Version">
          <el-table-column label="版本" min-width="150">
            <template #default="{ row }">
              <div class="version-cell">
                <strong>v{{ row.versionNumber }}</strong>
                <el-tag
                  v-if="row.id === timeline?.activeVersionId"
                  size="small"
                  type="success"
                >
                  Active
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="versionLevel" label="级别" width="100" />
          <el-table-column prop="versionCause" label="原因" min-width="180" />
          <el-table-column prop="status" label="状态" width="120" />
          <el-table-column label="State Hash" min-width="150">
            <template #default="{ row }"
              ><code>{{
                shortHash(row.semanticStateHash || row.catalogHash)
              }}</code></template
            >
          </el-table-column>
          <el-table-column label="激活时间" min-width="175">
            <template #default="{ row }">{{
              formatTime(row.activatedTime || row.publishedTime)
            }}</template>
          </el-table-column>
          <el-table-column
            v-if="canManage"
            label="操作"
            width="100"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                v-if="
                  row.id !== timeline?.activeVersionId &&
                  row.status === 'PUBLISHED'
                "
                link
                type="warning"
                @click="rollback(row)"
              >
                回滚到此版本
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="subsection-heading">
          <h3>Activation Events</h3>
          <span>只改变 Active 指针，不通过回滚制造新版本。</span>
        </div>
        <el-timeline v-if="timeline?.activationEvents?.length">
          <el-timeline-item
            v-for="event in timeline.activationEvents"
            :key="String(event.id)"
            :timestamp="formatTime(String(event.create_time || ''))"
          >
            <strong>{{ event.event_type }}</strong>
            <span class="timeline-copy">
              {{ event.from_version_id || "-" }} →
              {{ event.to_version_id || "-" }}
              <template v-if="event.reason"> · {{ event.reason }}</template>
            </span>
          </el-timeline-item>
        </el-timeline>
      </el-tab-pane>

      <el-tab-pane label="Corpus Revisions" name="corpus">
        <el-table :data="corpus" empty-text="还没有资料修订记录">
          <el-table-column prop="revisionNo" label="Revision" width="100" />
          <el-table-column prop="sourceType" label="来源" width="120" />
          <el-table-column
            prop="sourceRef"
            label="资料"
            min-width="220"
            show-overflow-tooltip
          />
          <el-table-column label="Semantic Diff" width="130">
            <template #default="{ row }">
              <el-tag
                :type="row.semanticDiffDetected ? 'warning' : 'info'"
                effect="plain"
              >
                {{ row.semanticDiffDetected ? "有语义变化" : "无语义变化" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="ChangeSet" min-width="180">
            <template #default="{ row }">
              <el-button
                v-if="row.semanticChangeSetId"
                link
                @click="openChangeSet(row.semanticChangeSetId)"
              >
                {{ shortId(row.semanticChangeSetId) }}
              </el-button>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" min-width="175">
            <template #default="{ row }">{{
              formatTime(row.createTime)
            }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="ChangeSets" name="changesets">
        <el-table :data="changeSets" empty-text="暂无 Semantic ChangeSet">
          <el-table-column label="ChangeSet" min-width="160">
            <template #default="{ row }">
              <el-button link @click="openChangeSet(row.changeSetId)">{{
                shortId(row.changeSetId)
              }}</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="originType" label="来源" width="110" />
          <el-table-column
            prop="targetVersionLevel"
            label="版本级别"
            width="110"
          />
          <el-table-column prop="rootCause" label="根因" min-width="150" />
          <el-table-column prop="riskLevel" label="风险" width="90" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="changeSetTag(row.status)" effect="plain">{{
                row.status
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="affectedAssetCount" label="资产" width="80" />
          <el-table-column label="目标版本" width="100">
            <template #default="{ row }">{{
              row.materializedVersionId || "-"
            }}</template>
          </el-table-column>
          <el-table-column
            v-if="canManage"
            label="操作"
            width="100"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                v-if="
                  row.targetVersionLevel === 'MAJOR' && row.status === 'READY'
                "
                link
                type="primary"
                @click="promote(row)"
              >
                Promote
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="detailVisible" title="Semantic ChangeSet" size="680px">
      <div v-loading="detailLoading" class="detail-drawer">
        <el-descriptions v-if="detail" :column="1" border>
          <el-descriptions-item label="ChangeSet">{{
            detail.changeSet.changeSetId
          }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{
            detail.changeSet.status
          }}</el-descriptions-item>
          <el-descriptions-item label="来源">
            {{ detail.changeSet.originType }} ·
            {{ detail.changeSet.originRef || "-" }}
          </el-descriptions-item>
          <el-descriptions-item label="版本变化">{{
            detail.changeSet.targetVersionLevel
          }}</el-descriptions-item>
          <el-descriptions-item label="Base Version ID">{{
            detail.changeSet.baseSemanticVersionId
          }}</el-descriptions-item>
          <el-descriptions-item label="Materialized Version ID">
            {{ detail.changeSet.materializedVersionId || "-" }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="subsection-heading"><h3>Change Items</h3></div>
        <el-table v-if="detail" :data="detail.items" size="small">
          <el-table-column prop="operation" label="操作" width="90" />
          <el-table-column prop="assetType" label="资产类型" width="110" />
          <el-table-column prop="assetKey" label="资产" min-width="160" />
          <el-table-column label="Patch" min-width="220">
            <template #default="{ row }"
              ><code class="json-code">{{ row.patchJson }}</code></template
            >
          </el-table-column>
        </el-table>

        <div class="subsection-heading"><h3>Replay Evidence</h3></div>
        <el-table
          v-if="detail"
          :data="detail.replayResults"
          size="small"
          empty-text="尚无 replay 结果"
        >
          <el-table-column prop="case_id" label="Case" min-width="150" />
          <el-table-column prop="replay_level" label="Level" width="120" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column
            prop="error_message"
            label="错误"
            min-width="220"
            show-overflow-tooltip
          />
        </el-table>
      </div>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  semEvoSQLService,
  type CorpusRevision,
  type ProjectSemanticReadiness,
  type SemanticChangeSet,
  type SemanticChangeSetDetail,
  type SemanticProjectVersion,
  type SemanticVersionTimeline,
} from "@/services/semevosql";

const props = defineProps<{ projectId: number; canManage?: boolean }>();
const emit = defineEmits<{ changed: [] }>();

const loading = ref(false);
const error = ref("");
const activeTab = ref("versions");
const readiness = ref<ProjectSemanticReadiness>();
const timeline = ref<SemanticVersionTimeline>();
const corpus = ref<CorpusRevision[]>([]);
const changeSets = ref<SemanticChangeSet[]>([]);
const detail = ref<SemanticChangeSetDetail>();
const detailVisible = ref(false);
const detailLoading = ref(false);

const versions = computed<SemanticProjectVersion[]>(
  () => timeline.value?.versions || [],
);
const activeVersionLabel = computed(() => {
  const version = readiness.value?.activeVersion?.version;
  return version ? `${version.major}.${version.minor}.${version.patch}` : "-";
});
const canManage = computed(() => Boolean(props.canManage));
const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof Error ? cause.message : fallback;

const load = async () => {
  loading.value = true;
  error.value = "";
  try {
    const [nextReadiness, nextTimeline, nextCorpus, nextChangeSets] =
      await Promise.all([
        semEvoSQLService.semanticReadiness(props.projectId),
        semEvoSQLService.semanticVersionTimeline(props.projectId),
        semEvoSQLService.corpusRevisions(props.projectId),
        semEvoSQLService.semanticChangeSets(props.projectId),
      ]);
    readiness.value = nextReadiness;
    timeline.value = nextTimeline;
    corpus.value = nextCorpus;
    changeSets.value = nextChangeSets;
  } catch (cause: unknown) {
    error.value = errorMessage(cause, "语义治理状态读取失败");
  } finally {
    loading.value = false;
  }
};

const openChangeSet = async (changeSetId: string) => {
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    detail.value = await semEvoSQLService.semanticChangeSet(changeSetId);
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause, "ChangeSet 读取失败"));
  } finally {
    detailLoading.value = false;
  }
};

const promote = async (changeSet: SemanticChangeSet) => {
  try {
    await ElMessageBox.confirm(
      `将 MAJOR ChangeSet ${shortId(changeSet.changeSetId)} Promote 为新的业务基线？`,
      "Promote Semantic Version",
      {
        type: "warning",
        confirmButtonText: "Promote",
        cancelButtonText: "取消",
      },
    );
    await semEvoSQLService.promoteSemanticChangeSet(
      changeSet.changeSetId,
      "manual business baseline promotion",
    );
    ElMessage.success("新的 MAJOR Semantic Version 已激活");
    await load();
    emit("changed");
  } catch (cause: unknown) {
    if (cause === "cancel" || cause === "close") return;
    ElMessage.error(errorMessage(cause, "Promote 失败"));
  }
};

const rollback = async (version: SemanticProjectVersion) => {
  try {
    await ElMessageBox.confirm(
      `将 Active 指针回滚到 v${version.versionNumber}？不会创建新的 Semantic Version。`,
      "Rollback Semantic Version",
      {
        type: "warning",
        confirmButtonText: "确认回滚",
        cancelButtonText: "取消",
      },
    );
    await semEvoSQLService.rollbackSemanticVersion(
      props.projectId,
      version.id,
      `rollback to semantic version ${version.versionNumber}`,
    );
    ElMessage.success(`已回滚到 v${version.versionNumber}`);
    await load();
    emit("changed");
  } catch (cause: unknown) {
    if (cause === "cancel" || cause === "close") return;
    ElMessage.error(errorMessage(cause, "Rollback 失败"));
  }
};

const shortHash = (value?: string) => (value ? `${value.slice(0, 10)}…` : "-");
const shortId = (value?: string) => (value ? `${value.slice(0, 8)}…` : "-");
const formatTime = (value?: string) =>
  value ? new Date(value).toLocaleString("zh-CN") : "-";
const changeSetTag = (status: string) => {
  if (status === "ACTIVE") return "success";
  if (["REJECTED", "FAILED", "STALE"].includes(status)) return "danger";
  if (["READY", "ACTIVATING"].includes(status)) return "warning";
  return "info";
};

onMounted(load);
watch(() => props.projectId, load);
</script>

<style scoped>
.semantic-governance {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.governance-heading,
.subsection-heading,
.version-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.governance-heading {
  align-items: flex-start;
}
.governance-heading h2,
.subsection-heading h3 {
  margin: 4px 0 0;
}
.governance-heading p,
.subsection-heading span,
.timeline-copy {
  color: #64748b;
}
.governance-heading p {
  margin: 8px 0 0;
  line-height: 1.65;
}
.eyebrow {
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.status-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.status-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color-page);
}
.status-card span {
  color: #64748b;
  font-size: 12px;
}
.status-card strong {
  color: #0f172a;
  font-size: 20px;
}
.status-card code,
.json-code {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: #475569;
  font-size: 12px;
}
.governance-tabs {
  min-width: 0;
}
.subsection-heading {
  margin: 24px 0 14px;
  justify-content: flex-start;
}
.detail-drawer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
@media (max-width: 900px) {
  .status-grid {
    grid-template-columns: 1fr;
  }
}
</style>
