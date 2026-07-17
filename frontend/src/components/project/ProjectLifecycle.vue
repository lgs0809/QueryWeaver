<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section v-if="health" class="project-lifecycle" aria-label="项目准备进度" aria-live="polite">
    <div class="lifecycle-heading">
      <div>
        <span class="eyebrow">项目进度</span>
        <strong>{{ lifecycleTitle }}</strong>
        <p>{{ lifecycleDescription }}</p>
      </div>
      <el-button
        v-if="showAction && actionTarget"
        :type="health.queryReady ? 'primary' : 'default'"
        @click="emit('action', actionTarget)"
      >
        {{ health.queryReady ? '开始问数' : primaryAction?.label }}
      </el-button>
    </div>

    <ol class="lifecycle-stages">
      <li v-for="(stage, index) in stages" :key="stage.id" :class="stage.state">
        <div class="stage-marker" aria-hidden="true">
          <i v-if="stage.state === 'done'" class="bi bi-check-lg"></i>
          <span v-else>{{ index + 1 }}</span>
        </div>
        <div class="stage-copy">
          <strong>{{ stage.label }}</strong>
          <small>{{ stage.description }}</small>
        </div>
      </li>
    </ol>
  </section>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import type { ProjectHealth } from '@/services/queryweaver';
  import {
    projectLifecycleStages,
    projectPrimaryAction,
    type ProjectHealthAction,
  } from '@/services/projectExperience';

  const props = withDefaults(
    defineProps<{
      health?: ProjectHealth;
      showAction?: boolean;
    }>(),
    { health: undefined, showAction: true },
  );

  const emit = defineEmits<{
    action: [target: ProjectHealthAction['target']];
  }>();

  const stages = computed(() => projectLifecycleStages(props.health));
  const primaryAction = computed(() => projectPrimaryAction(props.health));
  const actionTarget = computed<ProjectHealthAction['target'] | undefined>(() =>
    props.health?.queryReady ? 'chat' : primaryAction.value?.target,
  );
  const lifecycleTitle = computed(() => {
    if (props.health?.queryReady) return '项目已准备好，可以稳定进入问数';
    const current = stages.value.find(stage => stage.state === 'current');
    return current ? `当前：${current.label}` : '正在读取项目准备状态';
  });
  const lifecycleDescription = computed(() => {
    if (props.health?.queryReady) {
      const version = props.health.activeVersion?.versionNumber;
      return version
        ? `正式业务模型 v${version} 已激活，新会话将固定使用该版本。`
        : '正式业务模型已激活。';
    }
    return (
      primaryAction.value?.description ||
      'QueryWeaver 会根据当前项目事实确定下一步，不会跳过业务确认、验证或发布门禁。'
    );
  });
</script>

<style scoped>
  .project-lifecycle {
    margin-bottom: 20px;
    padding: 18px 20px;
    border: 1px solid #dbe4f0;
    border-radius: 16px;
    background: #fff;
    box-shadow: 0 8px 30px rgb(15 23 42 / 4%);
  }
  .lifecycle-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 18px;
  }
  .eyebrow {
    display: block;
    margin-bottom: 5px;
    color: #64748b;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .lifecycle-heading strong {
    display: block;
    color: #0f172a;
    font-size: 16px;
  }
  .lifecycle-heading p {
    margin: 6px 0 0;
    color: #64748b;
    font-size: 12px;
    line-height: 1.55;
  }
  .lifecycle-stages {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .lifecycle-stages li {
    position: relative;
    display: flex;
    min-width: 0;
    gap: 9px;
    padding: 10px;
    border: 1px solid transparent;
    border-radius: 11px;
    background: #f8fafc;
  }
  .lifecycle-stages li.current {
    border-color: #bfdbfe;
    background: #eff6ff;
  }
  .lifecycle-stages li.done {
    background: #f8fafc;
  }
  .stage-marker {
    display: grid;
    width: 24px;
    height: 24px;
    flex: 0 0 auto;
    place-items: center;
    border-radius: 50%;
    background: #e2e8f0;
    color: #64748b;
    font-size: 11px;
    font-weight: 700;
  }
  .done .stage-marker {
    background: #dcfce7;
    color: #15803d;
  }
  .current .stage-marker {
    background: #2563eb;
    color: #fff;
  }
  .stage-copy {
    display: grid;
    min-width: 0;
    gap: 3px;
  }
  .stage-copy strong {
    overflow: hidden;
    color: #334155;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .stage-copy small {
    display: -webkit-box;
    overflow: hidden;
    color: #94a3b8;
    font-size: 10px;
    line-height: 1.35;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
  .current .stage-copy strong {
    color: #1d4ed8;
  }
  @media (max-width: 1100px) {
    .lifecycle-stages {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }
  @media (max-width: 680px) {
    .lifecycle-heading {
      flex-direction: column;
    }
    .lifecycle-stages {
      grid-template-columns: 1fr;
    }
    .stage-copy small {
      -webkit-line-clamp: 1;
    }
  }
</style>
