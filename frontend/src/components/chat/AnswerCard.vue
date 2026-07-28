<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <article class="answer-card">
    <div class="answer-heading">
      <div>
        <span class="answer-label">答案</span>
        <span class="answer-time">{{ formattedTime }}</span>
      </div>
      <el-tag v-if="statusLabel" :type="statusType" size="small" effect="plain">
        {{ statusLabel }}
      </el-tag>
    </div>

    <section class="answer-section answer-summary">
      <h3>答案摘要</h3>
      <div class="answer-content">{{ content }}</div>
    </section>

    <section v-if="artifactId" class="answer-section result-section">
      <div class="section-heading">
        <h3>结果数据</h3>
        <span v-if="artifact">{{ artifact.rowCount }} 行</span>
      </div>
      <div v-loading="artifactLoading" class="result-content">
        <el-alert
          v-if="artifactError"
          type="warning"
          :closable="false"
          show-icon
          title="结果数据暂时无法加载，答案与依据仍可查看。"
        />
        <el-table
          v-else-if="artifactRows.length"
          :data="artifactRows"
          max-height="360"
          border
          size="small"
        >
          <el-table-column
            v-for="column in artifactColumns"
            :key="column"
            :prop="column"
            :label="column"
            min-width="140"
            show-overflow-tooltip
          />
        </el-table>
        <el-empty
          v-else-if="artifact && !artifactLoading"
          :image-size="54"
          description="查询结果为空"
        />
      </div>
    </section>

    <section v-if="explanation" class="answer-section trust-summary">
      <div v-if="businessDefinitionText" class="trust-item">
        <span>业务口径</span>
        <strong>{{ businessDefinitionText }}</strong>
      </div>
      <div v-if="timeText" class="trust-item">
        <span>时间口径</span>
        <strong>{{ timeText }}</strong>
      </div>
      <div v-if="sourceText" class="trust-item">
        <span>数据来源</span>
        <strong>{{ sourceText }}</strong>
      </div>
    </section>

    <div class="answer-actions">
      <div v-if="showFeedbackActions" class="feedback-actions">
        <el-button type="success" plain :loading="feedbackLoading" @click="emit('trust')">
          结果正确
        </el-button>
        <el-button type="danger" plain @click="emit('correct')">这里理解错了</el-button>
      </div>
      <div class="detail-actions">
        <el-button v-if="explanation" link type="primary" @click="evidenceOpen = !evidenceOpen">
          {{ evidenceOpen ? '收起依据' : '查看依据' }}
        </el-button>
        <el-button v-if="runId" link type="primary" @click="emit('diagnosis')">查询诊断</el-button>
        <el-button v-if="runId" link @click="emit('run-details')">运行详情</el-button>
      </div>
    </div>

    <slot name="feedback" />
    <slot name="learning" />

    <el-collapse-transition>
      <section v-if="evidenceOpen && explanation" class="evidence-panel">
        <div class="evidence-section">
          <strong>问题理解</strong>
          <p>{{ explanation.understoodQuery || '-' }}</p>
        </div>
        <div v-if="explanation.semanticBindings?.length" class="evidence-section">
          <strong>业务含义</strong>
          <ul>
            <li v-for="(binding, index) in explanation.semanticBindings" :key="`binding-${index}`">
              {{ binding.displayPhrase || binding.normalizedPhrase || '-' }} →
              {{ binding.businessLabel || binding.assetKey || '-' }}
              <span v-if="binding.source">
                （{{ bindingSourceLabel(String(binding.source)) }}）
              </span>
            </li>
          </ul>
        </div>
        <div v-if="explanation.businessDefinitions?.length" class="evidence-section">
          <strong>业务定义</strong>
          <ul>
            <li
              v-for="(definition, index) in explanation.businessDefinitions"
              :key="`definition-${index}`"
            >
              {{ definition.name || definition.code || '-' }}
              <span v-if="definition.expression">：{{ definition.expression }}</span>
            </li>
          </ul>
        </div>
        <div v-if="explanation.filters?.length" class="evidence-section">
          <strong>过滤条件</strong>
          <ul>
            <li v-for="(filter, index) in explanation.filters" :key="`filter-${index}`">
              {{ compactObject(filter) }}
            </li>
          </ul>
        </div>
        <div v-if="explanation.models?.length" class="evidence-section">
          <strong>数据来源</strong>
          <ul>
            <li v-for="(model, index) in explanation.models" :key="`model-${index}`">
              {{ model.name || model.code || '-' }} → {{ model.table || '-' }}
            </li>
          </ul>
        </div>
        <div v-if="explanation.relationships?.length" class="evidence-section">
          <strong>表关系</strong>
          <ul>
            <li
              v-for="(relationship, index) in explanation.relationships"
              :key="`relationship-${index}`"
            >
              {{ relationship.from || '-' }} → {{ relationship.to || '-' }} ·
              {{ relationship.joinType || '-' }} · {{ relationship.condition || '-' }}
            </li>
          </ul>
        </div>
        <el-collapse v-if="explanation.sqlExecutions?.length" class="sql-collapse">
          <el-collapse-item title="SQL（高级）" name="sql">
            <pre v-for="(execution, index) in explanation.sqlExecutions" :key="`sql-${index}`">{{
              execution.sql || '该执行未记录 SQL 文本'
            }}</pre>
          </el-collapse-item>
        </el-collapse>
      </section>
    </el-collapse-transition>
  </article>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue';
  import type { QueryExecutionExplanation, ResultArtifact } from '@/services/semevosql';

  const props = defineProps<{
    content: string;
    createTime?: string;
    status?: string;
    runId?: string;
    explanation?: QueryExecutionExplanation;
    artifactId?: string;
    artifact?: ResultArtifact;
    artifactColumns: string[];
    artifactRows: Array<Record<string, unknown>>;
    artifactLoading?: boolean;
    artifactError?: string;
    showFeedbackActions?: boolean;
    feedbackLoading?: boolean;
  }>();
  const emit = defineEmits<{
    trust: [];
    correct: [];
    diagnosis: [];
    'run-details': [];
  }>();

  const evidenceOpen = ref(false);
  const formattedTime = computed(() =>
    props.createTime ? new Date(props.createTime).toLocaleString('zh-CN') : '',
  );
  const statusLabel = computed(() => {
    if (props.status === 'COMPLETED') return '查询完成';
    if (props.status === 'FAILED') return '查询未完成';
    if (props.status === 'PENDING') return '处理中';
    return '';
  });
  const statusType = computed(() => {
    if (props.status === 'COMPLETED') return 'success';
    if (props.status === 'FAILED') return 'danger';
    return 'info';
  });

  const businessDefinitionText = computed(() => {
    const definitions = (props.explanation?.businessDefinitions || [])
      .slice(0, 3)
      .map(item => {
        const name = String(item.name || item.businessName || item.code || '').trim();
        const definition = String(
          item.description || item.definition || item.expression || '',
        ).trim();
        if (!name) return definition;
        return definition && definition !== name ? `${name}：${definition}` : name;
      })
      .filter(Boolean);
    if (definitions.length) return definitions.join('；');
    return (props.explanation?.semanticBindings || [])
      .slice(0, 4)
      .map(item => String(item.businessLabel || item.assetKey || ''))
      .filter(Boolean)
      .join('、');
  });
  const timeText = computed(() => {
    const time = props.explanation?.time || {};
    const range = [time.startInclusive, time.endExclusive].filter(Boolean).join(' ～ ');
    const businessField = time.businessName || time.businessLabel || time.displayName;
    return [time.relativeExpression, range, businessField ? `按${businessField}统计` : '']
      .filter(Boolean)
      .join('；');
  });
  const sourceText = computed(() =>
    (props.explanation?.models || [])
      .slice(0, 5)
      .map(item => String(item.name || item.code || item.table || ''))
      .filter(Boolean)
      .join('、'),
  );

  const bindingSourceLabel = (source: string) => {
    if (source === 'USER') return '你的偏好';
    if (source === 'PROJECT') return '项目默认';
    if (source === 'MANUAL' || source === 'CLARIFICATION') return '本次确认';
    return '业务模型';
  };

  const compactObject = (value: Record<string, unknown>) =>
    Object.entries(value)
      .filter(([, item]) => item != null && item !== '')
      .slice(0, 5)
      .map(([key, item]) => `${key}: ${String(item)}`)
      .join('；');
</script>

<style scoped>
  .answer-card {
    max-width: 860px;
    margin: 0 auto 22px;
    overflow: hidden;
    border: 1px solid #dbe3ee;
    border-radius: 16px;
    background: #fff;
    box-shadow: 0 8px 24px rgb(15 23 42 / 5%);
  }
  .answer-heading,
  .section-heading,
  .answer-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .answer-heading {
    padding: 14px 18px;
    border-bottom: 1px solid #eef2f7;
    background: #fbfdff;
  }
  .answer-heading > div {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .answer-label {
    color: #0f172a;
    font-weight: 700;
  }
  .answer-time,
  .section-heading span {
    color: #94a3b8;
    font-size: 12px;
  }
  .answer-section {
    padding: 18px;
    border-bottom: 1px solid #eef2f7;
  }
  .answer-section h3,
  .section-heading h3 {
    margin: 0 0 10px;
    color: #334155;
    font-size: 13px;
    font-weight: 650;
  }
  .section-heading h3 {
    margin: 0;
  }
  .answer-content {
    color: #172033;
    white-space: pre-wrap;
    line-height: 1.78;
  }
  .result-content {
    min-height: 36px;
    margin-top: 12px;
  }
  .trust-summary {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    background: #fbfdff;
  }
  .trust-item {
    display: grid;
    align-content: start;
    gap: 4px;
    min-width: 0;
  }
  .trust-item span {
    color: #64748b;
    font-size: 11px;
  }
  .trust-item strong {
    overflow: hidden;
    color: #334155;
    font-size: 12px;
    font-weight: 600;
    text-overflow: ellipsis;
  }
  .answer-actions {
    padding: 13px 18px;
  }
  .feedback-actions,
  .detail-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
  }
  .evidence-panel {
    padding: 4px 18px 18px;
    border-top: 1px solid #eef2f7;
    background: #f8fafc;
  }
  .evidence-section {
    padding: 12px 0;
    border-bottom: 1px dashed #dbe3ee;
    color: #334155;
    line-height: 1.65;
  }
  .evidence-section strong {
    font-size: 13px;
  }
  .evidence-section p,
  .evidence-section ul {
    margin: 6px 0 0;
  }
  .evidence-section ul {
    padding-left: 20px;
  }
  .sql-collapse {
    margin-top: 8px;
    border: 0;
  }
  .sql-collapse pre {
    overflow-x: auto;
    padding: 10px;
    border-radius: 8px;
    background: #0f172a;
    color: #e2e8f0;
    white-space: pre-wrap;
    word-break: break-word;
  }
  @media (max-width: 760px) {
    .trust-summary {
      grid-template-columns: 1fr;
    }
    .answer-actions {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
