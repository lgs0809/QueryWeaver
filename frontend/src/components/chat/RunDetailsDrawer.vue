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
        <el-descriptions-item label="业务模型版本 ID">
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
  import type { QueryRun, RunEvent } from '@/services/queryweaver';

  defineProps<{
    modelValue: boolean;
    loading?: boolean;
    run?: QueryRun;
    events: RunEvent[];
    transportHint?: string;
  }>();
  const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');
</script>

<style scoped>
  .drawer-body {
    min-height: 220px;
  }
  .transport-alert {
    margin-top: 16px;
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
