<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="trajectory" v-loading="loading">
    <div class="toolbar">
      <div>
        <h2>Query Pattern 与执行轨迹</h2>
        <p>仅在相同 Project Version 与 Execution Snapshot 下比较路径；当前全部为只读观察。</p>
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
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="Pattern、Pareto 排名和 Detour 信号不会直接改写运行路径；只有经过 Shadow 与人工批准的 Preferred Plan 才可能成为起始提示。"
    />

    <div class="summary-grid">
      <div class="metric">
        <strong>{{ patterns.length }}</strong>
        <span>Query Pattern</span>
      </div>
      <div class="metric">
        <strong>{{ detours.length }}</strong>
        <span>Detour Signal</span>
      </div>
      <div class="metric">
        <strong>{{ semanticDetours }}</strong>
        <span>语义根因</span>
      </div>
      <div class="metric">
        <strong>{{ runtimeDetours }}</strong>
        <span>运行根因</span>
      </div>
    </div>

    <el-table :data="patterns" empty-text="暂无已分析轨迹" @row-click="openPattern">
      <el-table-column label="Pattern" min-width="260">
        <template #default="scope">
          <strong>{{ scope.row.intent_type }}</strong>
          <div class="subtle">
            {{ shortHash(scope.row.shape_hash) }} · {{ scope.row.ambiguity_level }} 歧义
          </div>
        </template>
      </el-table-column>
      <el-table-column label="样本" width="140">
        <template #default="scope">
          {{ scope.row.success_count }} / {{ scope.row.episode_count }} 成功
        </template>
      </el-table-column>
      <el-table-column label="风险" width="110">
        <template #default="scope">
          <el-tag :type="riskType(scope.row.risk_level)">{{ scope.row.risk_level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="快照" min-width="180">
        <template #default="scope">
          <code>{{ shortHash(scope.row.execution_compatibility_hash) }}</code>
        </template>
      </el-table-column>
      <el-table-column label="最后出现" width="180">
        <template #default="scope">{{ formatTime(scope.row.last_seen_time) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="scope">
          <el-button link type="primary" @click.stop="openPattern(scope.row)">查看路径</el-button>
          <el-button link @click.stop="recompute(scope.row.id)">重算</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawerVisible" title="Pattern 路径画像" size="76%">
      <template v-if="detail">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="意图">{{ detail.intent_type }}</el-descriptions-item>
          <el-descriptions-item label="样本">{{ detail.episode_count }}</el-descriptions-item>
          <el-descriptions-item label="成功">{{ detail.success_count }}</el-descriptions-item>
          <el-descriptions-item label="Shape Hash">
            <code>{{ shortHash(detail.shape_hash) }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Catalog Hash">
            <code>{{ shortHash(detail.catalog_hash) }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
        </el-descriptions>
        <h3>Pareto 路径</h3>
        <el-table :data="detail.profiles" empty-text="暂无路径画像">
          <el-table-column label="签名" width="130">
            <template #default="scope">
              <code>{{ shortHash(scope.row.path_signature) }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="sample_count" label="样本" width="80" />
          <el-table-column label="正确/安全" width="150">
            <template #default="scope">
              {{ percent(scope.row.correctness_rate) }} / {{ percent(scope.row.safety_rate) }}
            </template>
          </el-table-column>
          <el-table-column label="覆盖/新鲜/稳定" min-width="210">
            <template #default="scope">
              {{ percent(scope.row.coverage_rate) }} / {{ percent(scope.row.freshness_rate) }} /
              {{ percent(scope.row.stability_rate) }}
            </template>
          </el-table-column>
          <el-table-column label="成本" min-width="190">
            <template #default="scope">
              {{ Math.round(scope.row.avg_latency_ms) }} ms ·
              {{ Math.round(scope.row.avg_token_count) }} token · retry
              {{ Number(scope.row.avg_retry_count).toFixed(1) }}
            </template>
          </el-table-column>
          <el-table-column label="Pareto" width="100">
            <template #default="scope">
              <el-tag :type="isDominated(scope.row.dominated) ? 'info' : 'success'">
                {{ isDominated(scope.row.dominated) ? `被支配 #${scope.row.pareto_rank}` : '前沿' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <h3>最近路径</h3>
        <el-table :data="paths" empty-text="暂无路径">
          <el-table-column label="状态" width="110">
            <template #default="scope">
              <el-tag :type="scope.row.status === 'SUCCEEDED' ? 'success' : 'danger'">
                {{ scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="质量" min-width="210">
            <template #default="scope">
              正确 {{ percent(scope.row.correctness_score) }} · 安全
              {{ percent(scope.row.safety_score) }} · 稳定 {{ percent(scope.row.stability_score) }}
            </template>
          </el-table-column>
          <el-table-column label="成本" min-width="190">
            <template #default="scope">
              {{ scope.row.latency_ms || 0 }} ms · {{ scope.row.token_count || 0 }} token · retry
              {{ scope.row.retry_count }}
            </template>
          </el-table-column>
          <el-table-column label="节点" min-width="260">
            <template #default="scope">
              <code>{{ compactJson(scope.row.node_sequence_json) }}</code>
            </template>
          </el-table-column>
        </el-table>
        <h3>绕路信号</h3>
        <el-table :data="detail.detours" empty-text="暂无 Detour">
          <el-table-column prop="signal_type" label="信号" min-width="190" />
          <el-table-column prop="root_cause" label="根因" min-width="180" />
          <el-table-column label="置信度" width="120">
            <template #default="scope">{{ percent(scope.row.confidence) }}</template>
          </el-table-column>
          <el-table-column label="复现" width="130">
            <template #default="scope">
              {{ scope.row.occurrence_count }} 次 / {{ percent(scope.row.recurrence_rate) }}
            </template>
          </el-table-column>
          <el-table-column label="证据" min-width="300">
            <template #default="scope">
              <code>{{ compactJson(scope.row.evidence_json) }}</code>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import {
    semEvoSQLService,
    type DetourSignal,
    type QueryPattern,
    type QueryPatternDetail,
    type SemanticProjectVersion,
    type TrajectoryPath,
  } from '@/services/semevosql';

  const props = defineProps<{
    projectId: number;
    versions: SemanticProjectVersion[];
    activeVersionId?: number;
  }>();
  const selectedVersionId = ref<number>();
  const patterns = ref<QueryPattern[]>([]);
  const detours = ref<DetourSignal[]>([]);
  const detail = ref<QueryPatternDetail>();
  const paths = ref<TrajectoryPath[]>([]);
  const loading = ref(false);
  const drawerVisible = ref(false);
  const semanticDetours = computed(
    () => detours.value.filter(item => item.root_cause === 'SEMANTIC_EVOLUTION').length,
  );
  const runtimeDetours = computed(
    () =>
      detours.value.filter(item =>
        ['RUNTIME_OPTIMIZATION', 'PLANNER_DEFECT'].includes(item.root_cause),
      ).length,
  );

  const preferredVersion = () =>
    props.activeVersionId ||
    props.versions.find(item => item.status === 'PUBLISHED')?.id ||
    props.versions[0]?.id;
  const load = async () => {
    if (!props.projectId) return;
    loading.value = true;
    try {
      [patterns.value, detours.value] = await Promise.all([
        semEvoSQLService.trajectoryPatterns(props.projectId, selectedVersionId.value),
        semEvoSQLService.detourSignals(props.projectId),
      ]);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '轨迹加载失败');
    } finally {
      loading.value = false;
    }
  };
  const openPattern = async (pattern: QueryPattern) => {
    drawerVisible.value = true;
    try {
      [detail.value, paths.value] = await Promise.all([
        semEvoSQLService.trajectoryPattern(pattern.id),
        semEvoSQLService.trajectoryPaths(pattern.id),
      ]);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '路径详情加载失败');
    }
  };
  const recompute = async (patternId: string) => {
    try {
      await semEvoSQLService.recomputeTrajectoryPattern(patternId);
      ElMessage.success('Pattern 路径画像已重算');
      await load();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '重算失败');
    }
  };
  const shortHash = (value?: string) => (value ? `${value.slice(0, 10)}…${value.slice(-6)}` : '-');
  const formatTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');
  const percent = (value?: number) => `${(Number(value || 0) * 100).toFixed(0)}%`;
  const riskType = (value: string) =>
    value === 'HIGH' ? 'danger' : value === 'MEDIUM' ? 'warning' : 'success';
  const isDominated = (value: boolean | number) => value === true || value === 1;
  const compactJson = (value?: string) => {
    if (!value) return '-';
    try {
      return JSON.stringify(JSON.parse(value));
    } catch {
      return value;
    }
  };
  watch(
    () => [props.activeVersionId, props.versions.length],
    () => {
      if (!selectedVersionId.value) selectedVersionId.value = preferredVersion();
      void load();
    },
  );
  onMounted(() => {
    selectedVersionId.value = preferredVersion();
    void load();
  });
</script>

<style scoped>
  .trajectory {
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
  .toolbar p {
    margin: 0;
    color: #64748b;
  }
  .filters {
    display: flex;
    gap: 10px;
  }
  .filters .el-select {
    width: 220px;
  }
  .summary-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
  }
  .metric {
    padding: 16px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #fff;
  }
  .metric strong {
    display: block;
    font-size: 26px;
    color: #0f172a;
  }
  .metric span,
  .subtle {
    color: #64748b;
    font-size: 12px;
  }
  h3 {
    margin: 24px 0 10px;
  }
  code {
    white-space: normal;
    word-break: break-all;
    font-size: 12px;
  }
  @media (max-width: 900px) {
    .toolbar {
      flex-direction: column;
    }
    .summary-grid {
      grid-template-columns: repeat(2, 1fr);
    }
  }
</style>
