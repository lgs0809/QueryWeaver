<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <el-drawer
    :model-value="modelValue"
    title="运行详情"
    size="520px"
    @close="emit('update:modelValue', false)"
  >
    <div v-loading="loading" class="drawer-body">
      <el-descriptions v-if="run" :column="1" border>
        <el-descriptions-item label="运行 ID">{{ run.runId }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ run.status }}</el-descriptions-item>
        <el-descriptions-item v-if="run.episodeId" label="Episode ID">{{ run.episodeId }}</el-descriptions-item>
        <el-descriptions-item label="Semantic Version ID">
          {{ run.projectVersionId || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="当前节点">{{ run.currentNode || '-' }}</el-descriptions-item>
        <el-descriptions-item label="事件序号">{{ run.lastEventSequence }}</el-descriptions-item>
        <el-descriptions-item v-if="run.errorCode" label="错误码">
          {{ run.errorCode }}
        </el-descriptions-item>
        <el-descriptions-item v-if="run.errorMessage" label="错误信息">
          {{ run.errorMessage }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="transportHint"
        class="transport-alert"
        type="info"
        :closable="false"
        show-icon
        :title="transportHint"
      />

      <el-card v-if="run?.episodeId" shadow="never" class="episode-card" v-loading="diagnosisLoading">
        <template #header>
          <div class="event-title episode-title">
            <strong>Episode Diagnosis</strong>
            <el-button link :loading="diagnosisLoading" @click="loadDiagnosis">刷新</el-button>
          </div>
        </template>
        <el-alert v-if="diagnosisError" type="warning" :closable="false" :title="diagnosisError" />
        <template v-if="diagnosis">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="Outcome">
              {{ field(diagnosis.episode, 'outcome') || field(diagnosis.episode, 'status') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Base Semantic Version">
              {{ field(diagnosis.episode, 'base_semantic_version_id') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Accepted Attempt">
              {{ field(diagnosis.episode, 'accepted_attempt_id') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="Result Semantic Version">
              {{ field(diagnosis.episode, 'result_semantic_version_id') || '-' }}
            </el-descriptions-item>
          </el-descriptions>
          <div class="episode-counts">
            <el-tag effect="plain">Attempts {{ diagnosis.attempts.length }}</el-tag>
            <el-tag effect="plain">Signals {{ diagnosis.signals.length }}</el-tag>
            <el-tag effect="plain">Query Cases {{ diagnosis.queryCases.length }}</el-tag>
            <el-tag effect="plain">ChangeSets {{ diagnosis.changeSets.length }}</el-tag>
          </div>
          <el-collapse>
            <el-collapse-item title="Attempts" name="attempts">
              <el-table :data="diagnosis.attempts" size="small" empty-text="暂无 Attempt">
                <el-table-column label="#" width="58">
                  <template #default="{ row }">{{ field(row, 'attempt_no') || '-' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="110">
                  <template #default="{ row }">{{ field(row, 'status') || '-' }}</template>
                </el-table-column>
                <el-table-column label="Semantic Version" min-width="140">
                  <template #default="{ row }">{{ field(row, 'semantic_version_id') || '-' }}</template>
                </el-table-column>
                <el-table-column label="错误" min-width="160" show-overflow-tooltip>
                  <template #default="{ row }">{{ field(row, 'error_type') || '-' }}</template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
            <el-collapse-item title="Evolution Signals" name="signals">
              <el-table :data="diagnosis.signals" size="small" empty-text="暂无 Evolution Signal">
                <el-table-column label="Signal" min-width="150">
                  <template #default="{ row }">{{ field(row, 'signal_type') || '-' }}</template>
                </el-table-column>
                <el-table-column label="Root Cause" min-width="150">
                  <template #default="{ row }">{{ field(row, 'root_cause') || '-' }}</template>
                </el-table-column>
                <el-table-column label="Confidence" width="110">
                  <template #default="{ row }">{{ field(row, 'confidence') || '-' }}</template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
            <el-collapse-item title="Semantic ChangeSets" name="changesets">
              <el-table :data="diagnosis.changeSets" size="small" empty-text="本 Episode 未触发语义变更">
                <el-table-column label="ChangeSet" min-width="150">
                  <template #default="{ row }">{{ shortId(field(row, 'id')) }}</template>
                </el-table-column>
                <el-table-column label="Level" width="90">
                  <template #default="{ row }">{{ field(row, 'target_version_level') || '-' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="110">
                  <template #default="{ row }">{{ field(row, 'status') || '-' }}</template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </template>
      </el-card>

      <div class="event-title">
        <strong>原始执行事件</strong>
        <span>{{ events.length }} 条</span>
      </div>
      <el-timeline v-if="events.length">
        <el-timeline-item
          v-for="event in events"
          :key="`${event.runId}-${event.sequence}`"
          :timestamp="formatTime(event.createTime)"
        >
          <div class="event-row">
            <strong>{{ event.eventType }}</strong>
            <code>#{{ event.sequence }}</code>
          </div>
          <p>{{ event.payloadSummary || event.nodeName || '事件已持久化' }}</p>
          <details v-if="event.payload">
            <summary>查看原始 payload</summary>
            <pre>{{ event.payload }}</pre>
          </details>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else-if="!loading" :image-size="60" description="没有可展示的运行事件" />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
  import { ref, watch } from 'vue';
  import {
    semEvoSQLService,
    type EpisodeDiagnosis,
    type QueryRun,
    type RunEvent,
  } from '@/services/semevosql';

  const props = defineProps<{
    modelValue: boolean;
    loading?: boolean;
    run?: QueryRun;
    events: RunEvent[];
    transportHint?: string;
  }>();
  const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

  const diagnosis = ref<EpisodeDiagnosis>();
  const diagnosisLoading = ref(false);
  const diagnosisError = ref('');

  const loadDiagnosis = async () => {
    const episodeId = props.run?.episodeId;
    if (!episodeId) {
      diagnosis.value = undefined;
      return;
    }
    diagnosisLoading.value = true;
    diagnosisError.value = '';
    try {
      diagnosis.value = await semEvoSQLService.episodeDiagnosis(episodeId);
    } catch (cause: unknown) {
      diagnosis.value = undefined;
      diagnosisError.value = cause instanceof Error ? cause.message : 'Episode Diagnosis 读取失败';
    } finally {
      diagnosisLoading.value = false;
    }
  };

  const field = (row: Record<string, unknown>, key: string) => {
    const value = row?.[key];
    return value == null ? '' : String(value);
  };
  const shortId = (value?: string) => (value ? `${value.slice(0, 8)}…` : '-');
  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');

  watch(
    () => [props.modelValue, props.run?.episodeId] as const,
    ([visible, episodeId], previous) => {
      if (visible && episodeId && (!previous || previous[0] !== visible || previous[1] !== episodeId)) {
        void loadDiagnosis();
      }
      if (!visible) diagnosisError.value = '';
    },
    { immediate: true },
  );
</script>

<style scoped>
  .drawer-body {
    min-height: 220px;
  }
  .transport-alert {
    margin-top: 16px;
  }
  .episode-card {
    margin-top: 18px;
  }
  .episode-title {
    margin: 0;
  }
  .episode-counts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin: 14px 0;
  }
  .event-title,
  .event-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .event-title {
    margin: 24px 0 18px;
  }
  .event-title span,
  .event-row code {
    color: #94a3b8;
    font-size: 12px;
  }
  .event-row strong {
    color: #334155;
  }
  p {
    margin: 5px 0 0;
    color: #64748b;
    line-height: 1.6;
  }
  details {
    margin-top: 8px;
    color: #64748b;
    font-size: 12px;
  }
  pre {
    overflow-x: auto;
    padding: 10px;
    border-radius: 8px;
    background: #0f172a;
    color: #e2e8f0;
    white-space: pre-wrap;
    word-break: break-word;
  }
</style>
