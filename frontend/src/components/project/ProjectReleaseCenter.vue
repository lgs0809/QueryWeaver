<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section v-loading="loading" class="release-center">
    <div class="section-heading">
      <div>
        <h2>发布与版本</h2>
        <p>在同一处查看业务模型版本、变更、自动回归、人工治理决定和当前正式版本。</p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="error"
      type="warning"
      show-icon
      :closable="false"
      title="发布中心加载失败"
      :description="error"
    />

    <div v-if="center" class="version-list">
      <article v-for="version in center.versions" :key="version.id" class="version-card">
        <header class="version-heading">
          <div>
            <div class="version-title">
              <strong>v{{ version.versionNumber }}</strong>
              <el-tag :type="versionStatusType(version.status)" effect="plain">
                {{ versionStatusLabel(version.status) }}
              </el-tag>
              <el-tag v-if="version.active" type="success">当前正式版本</el-tag>
            </div>
            <span>
              发布：{{ formatTime(version.publishedTime) }} ·
              {{ version.publishedBy || '历史版本未记录发布人' }}
              <template v-if="version.governanceDecidedBy">
                · 治理决定：{{ version.governanceDecidedBy }}
              </template>
            </span>
          </div>
          <div v-if="canPublish" class="version-actions">
            <el-button
              v-if="version.status === 'VALIDATED' || version.status === 'READY'"
              type="primary"
              :loading="publishingVersionId === version.id"
              @click="publish(version.id)"
            >
              发布此版本
            </el-button>
            <el-button
              v-if="version.status === 'PUBLISHED' && !version.active"
              type="primary"
              plain
              :loading="activatingVersionId === version.id"
              @click="activate(version.id)"
            >
              {{ center.activeVersionId ? '切换到此版本' : '激活此版本' }}
            </el-button>
          </div>
        </header>

        <div class="release-facts">
          <div>
            <span>自动回归</span>
            <strong>{{ replayText(version.replay) }}</strong>
            <small>自动回归结果，不等同于人工发布决定</small>
          </div>
          <div>
            <span>激活记录</span>
            <strong>{{ version.active ? '正在服务新会话' : '未激活' }}</strong>
            <small>
              {{
                version.activatedBy
                  ? `${version.activatedBy} · ${formatTime(version.activatedTime)}`
                  : '历史记录可能无操作者信息'
              }}
            </small>
          </div>
          <div>
            <span>业务模型变更</span>
            <strong>{{ version.changes.length }} 项</strong>
            <small>来自受治理的结构化变更事实，不以自然语言摘要替代</small>
          </div>
        </div>

        <section class="diff-section">
          <h3>变更摘要</h3>
          <div v-if="version.changes.length" class="diff-list">
            <div
              v-for="change in version.changes"
              :key="`${change.candidateId}-${change.operation}-${change.assetKey}`"
              class="diff-item"
            >
              <span class="diff-symbol" :class="change.kind.toLowerCase()">
                {{ diffSymbol(change.kind) }}
              </span>
              <div>
                <strong>
                  {{ changeLabel(change.assetType) }} · {{ change.businessName || change.assetKey }}
                </strong>
                <small>{{ changeOperationLabel(change.operation) }} · {{ change.assetKey }}</small>
              </div>
            </div>
          </div>
          <p v-else class="empty-copy">
            这个版本没有关联的结构化业务模型变更；可能是手工创建版本或历史发布记录。
          </p>
        </section>

        <details
          v-if="version.structuredReleaseReport || version.catalogHash"
          class="advanced-facts"
        >
          <summary>查看结构化发布依据</summary>
          <dl>
            <div>
              <dt>Catalog Hash</dt>
              <dd>{{ version.catalogHash || '-' }}</dd>
            </div>
            <div>
              <dt>Parent Version</dt>
              <dd>{{ version.parentVersionId || '-' }}</dd>
            </div>
          </dl>
          <pre v-if="version.structuredReleaseReport">{{
            prettyJson(version.structuredReleaseReport)
          }}</pre>
        </details>
      </article>

      <section v-if="center.controlledReleases.length" class="controlled-section">
        <div class="section-heading compact">
          <div>
            <h2>受控发布记录</h2>
            <p>这是灰度/受控流量发布事实，与业务模型版本 Publish 分开记录。</p>
          </div>
        </div>
        <el-table :data="center.controlledReleases" size="small">
          <el-table-column prop="releaseType" label="方式" width="130" />
          <el-table-column label="版本" min-width="170">
            <template #default="scope">
              v{{ versionNumber(scope.row.baselineVersionId) }} → v{{
                versionNumber(scope.row.candidateVersionId)
              }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="130" />
          <el-table-column prop="trafficPercent" label="流量%" width="90" />
          <el-table-column label="样本 / 失败" width="130">
            <template #default="scope">
              {{ scope.row.sampleCount }} / {{ scope.row.failureCount }}
            </template>
          </el-table-column>
          <el-table-column
            prop="rollbackReason"
            label="回滚原因"
            min-width="220"
            show-overflow-tooltip
          />
          <el-table-column label="更新时间" width="180">
            <template #default="scope">{{ formatTime(scope.row.updateTime) }}</template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
  import { onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { queryWeaverService, type ProjectReleaseCenter } from '@/services/queryweaver';

  const props = defineProps<{ projectId: number; canPublish?: boolean }>();
  const emit = defineEmits<{ changed: [] }>();
  const center = ref<ProjectReleaseCenter>();
  const loading = ref(false);
  const error = ref('');
  const publishingVersionId = ref<number>();
  const activatingVersionId = ref<number>();

  const load = async () => {
    loading.value = true;
    error.value = '';
    try {
      center.value = await queryWeaverService.projectReleaseCenter(props.projectId);
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '发布中心加载失败';
    } finally {
      loading.value = false;
    }
  };

  const publish = async (versionId: number) => {
    publishingVersionId.value = versionId;
    try {
      await queryWeaverService.publishProjectVersion(props.projectId, versionId);
      ElMessage.success('业务模型已发布；如果项目此前没有正式版本，系统会自动激活该版本。');
      await load();
      emit('changed');
    } catch (cause) {
      ElMessage.error(cause instanceof Error ? cause.message : '版本发布失败');
    } finally {
      publishingVersionId.value = undefined;
    }
  };

  const activate = async (versionId: number) => {
    try {
      await ElMessageBox.confirm(
        '旧会话仍继续使用原业务模型版本；只有新会话会使用切换后的正式版本。是否继续？',
        '切换正式业务模型',
        { type: 'warning', confirmButtonText: '确认切换', cancelButtonText: '取消' },
      );
      activatingVersionId.value = versionId;
      await queryWeaverService.activateProjectVersion(props.projectId, versionId);
      ElMessage.success('正式业务模型已切换，旧会话版本保持不变。');
      await load();
      emit('changed');
    } catch (cause) {
      if (cause === 'cancel' || cause === 'close') return;
      ElMessage.error(cause instanceof Error ? cause.message : '版本切换失败');
    } finally {
      activatingVersionId.value = undefined;
    }
  };

  const replayText = (replay: ProjectReleaseCenter['versions'][number]['replay']) => {
    if (!replay.total) return '尚无自动回归记录';
    return `${replay.passed} 通过 · ${replay.needsAttention} 需关注 · ${replay.failed} 失败`;
  };
  const diffSymbol = (kind: string) => (kind === 'ADDED' ? '+' : kind === 'REMOVED' ? '-' : '~');
  const changeLabel = (assetType: string) => {
    const labels: Record<string, string> = {
      MODEL: '业务对象',
      COLUMN: '字段',
      METRIC: '指标',
      DIMENSION: '维度',
      ENUM: '枚举',
      ENUM_VALUE: '枚举',
      RELATIONSHIP: '关系',
      GRAIN: '粒度',
      RULE: '业务规则',
      ALIAS: '统一叫法',
      PROJECT_ALIAS: '项目规则',
    };
    return labels[assetType] || assetType || '业务资产';
  };
  const changeOperationLabel = (operation: string) => {
    if (operation.startsWith('ADD')) return '新增';
    if (operation.startsWith('DELETE') || operation.startsWith('REMOVE')) return '删除';
    if (operation.startsWith('UPDATE')) return '修改';
    return operation;
  };
  const versionStatusLabel = (status: string) => {
    if (status === 'DRAFT') return '草稿';
    if (status === 'VALIDATED' || status === 'READY') return '验证通过';
    if (status === 'PUBLISHED') return '已发布';
    if (status === 'ARCHIVED') return '已归档';
    return status;
  };
  const versionStatusType = (status: string) => {
    if (status === 'PUBLISHED') return 'success';
    if (status === 'VALIDATED' || status === 'READY') return 'primary';
    if (status === 'ARCHIVED') return 'info';
    return 'warning';
  };
  const formatTime = (value?: string) =>
    value ? new Date(value).toLocaleString('zh-CN') : '未记录';
  const versionNumber = (versionId: number) =>
    center.value?.versions.find(item => item.id === versionId)?.versionNumber || String(versionId);
  const prettyJson = (value: string) => {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  };

  onMounted(load);
</script>

<style scoped>
  .release-center {
    min-height: 360px;
  }
  .section-heading,
  .version-heading,
  .version-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .section-heading,
  .version-heading {
    align-items: flex-start;
  }
  .section-heading h2 {
    margin: 0 0 5px;
    color: #0f172a;
    font-size: 18px;
  }
  .section-heading p,
  .version-heading span,
  .empty-copy {
    margin: 0;
    color: #64748b;
    line-height: 1.55;
  }
  .section-heading.compact {
    margin-bottom: 14px;
  }
  .version-list {
    display: grid;
    gap: 16px;
    margin-top: 18px;
  }
  .version-card {
    padding: 18px;
    border: 1px solid #e2e8f0;
    border-radius: 14px;
    background: #fff;
  }
  .version-title {
    justify-content: flex-start;
    margin-bottom: 5px;
  }
  .version-title strong {
    color: #0f172a;
    font-size: 20px;
  }
  .release-facts {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 10px;
    margin: 16px 0;
  }
  .release-facts > div {
    display: grid;
    gap: 4px;
    padding: 12px;
    border-radius: 10px;
    background: #f8fafc;
  }
  .release-facts span,
  .release-facts small {
    color: #64748b;
    font-size: 11px;
    line-height: 1.45;
  }
  .release-facts strong {
    color: #334155;
  }
  .diff-section {
    padding-top: 14px;
    border-top: 1px solid #eef2f7;
  }
  .diff-section h3 {
    margin: 0 0 10px;
    color: #334155;
    font-size: 13px;
  }
  .diff-list {
    display: grid;
    gap: 8px;
  }
  .diff-item {
    display: flex;
    align-items: flex-start;
    gap: 10px;
  }
  .diff-item > div {
    display: grid;
    gap: 2px;
  }
  .diff-item strong {
    color: #334155;
  }
  .diff-item small {
    color: #64748b;
  }
  .diff-symbol {
    width: 22px;
    flex: 0 0 auto;
    text-align: center;
    border-radius: 5px;
    font-weight: 800;
  }
  .diff-symbol.added {
    background: #ecfdf5;
    color: #047857;
  }
  .diff-symbol.modified {
    background: #eff6ff;
    color: #1d4ed8;
  }
  .diff-symbol.removed {
    background: #fef2f2;
    color: #b91c1c;
  }
  .advanced-facts {
    margin-top: 14px;
    color: #64748b;
    font-size: 12px;
  }
  .advanced-facts dl {
    display: grid;
    gap: 6px;
  }
  .advanced-facts dl div {
    display: grid;
    grid-template-columns: 110px 1fr;
    gap: 8px;
  }
  .advanced-facts dt {
    color: #94a3b8;
  }
  .advanced-facts dd {
    margin: 0;
    word-break: break-all;
  }
  .advanced-facts pre {
    max-height: 240px;
    overflow: auto;
    padding: 10px;
    border-radius: 8px;
    background: #0f172a;
    color: #e2e8f0;
    white-space: pre-wrap;
  }
  .controlled-section {
    margin-top: 12px;
    padding-top: 18px;
    border-top: 1px solid #e2e8f0;
  }
  @media (max-width: 800px) {
    .version-heading,
    .section-heading {
      flex-direction: column;
    }
    .release-facts {
      grid-template-columns: 1fr;
    }
  }
</style>
