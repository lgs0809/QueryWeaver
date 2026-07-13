<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <section class="welcome-card">
    <template v-if="!hasProject">
      <div class="welcome-icon"><i class="bi bi-database-add"></i></div>
      <h2>先创建第一个数据项目</h2>
      <p>连接业务数据库后，QueryWeaver 会自动理解表结构，并只追问无法安全推断的关键业务规则。</p>
      <el-button type="primary" size="large" @click="emit('create-project')">
        创建数据项目
      </el-button>
    </template>

    <template v-else>
      <div class="welcome-icon"><i class="bi bi-stars"></i></div>
      <h2>向「{{ projectName }}」提问</h2>
      <p>
        直接用业务语言提问。QueryWeaver
        会基于当前已发布的业务模型理解口径、选择数据并返回可追溯结果。
      </p>

      <div class="project-facts">
        <div>
          <span>业务模型</span>
          <strong>{{ versionNumber ? `v${versionNumber}` : '尚未发布' }}</strong>
        </div>
        <div>
          <span>项目状态</span>
          <strong>{{ projectStatusLabel }}</strong>
        </div>
        <div>
          <span>开始方式</span>
          <strong>{{ canStart ? '新建会话即可提问' : '先完成当前准备步骤' }}</strong>
        </div>
      </div>

      <div v-if="!canStart" class="readiness-blocker">
        <div>
          <span>当前下一步</span>
          <strong>{{ nextAction?.label || '继续准备项目' }}</strong>
          <p>
            {{
              nextAction?.description || '项目需要完成业务理解、验证和发布后才能创建新的问数会话。'
            }}
          </p>
        </div>
        <el-button type="primary" plain @click="emit('manage-project')">继续准备项目</el-button>
      </div>

      <div v-if="canStart" class="examples">
        <span>你可以这样问：</span>
        <button
          v-for="example in examples"
          :key="example"
          type="button"
          @click="emit('use-example', example)"
        >
          {{ example }}
        </button>
      </div>

      <el-button
        v-if="canStart && !hasConversation"
        type="primary"
        size="large"
        @click="emit('create-conversation')"
      >
        开始第一次问数
      </el-button>
    </template>
  </section>
</template>

<script setup lang="ts">
  import { computed } from 'vue';

  const props = defineProps<{
    hasProject: boolean;
    projectName: string;
    versionNumber?: string;
    projectStatus?: string;
    queryReady?: boolean;
    hasConversation: boolean;
    suggestedQuestions?: string[];
    nextAction?: {
      label: string;
      description: string;
      target: string;
    };
  }>();
  const emit = defineEmits<{
    'create-project': [];
    'create-conversation': [];
    'manage-project': [];
    'use-example': [value: string];
  }>();

  const canStart = computed(() => Boolean(props.versionNumber) && props.queryReady !== false);
  const projectStatusLabel = computed(() => {
    if (props.projectStatus === 'READY') return '可以问数';
    if (props.projectStatus === 'INITIALIZING') return '正在理解业务';
    return props.projectStatus || '待准备';
  });
  const examples = computed(() =>
    props.suggestedQuestions?.length
      ? props.suggestedQuestions
      : [
          '上个月各区域核心指标表现如何？',
          '哪些业务维度变化最大？',
          '本周数据和上周相比有什么明显差异？',
        ],
  );
</script>

<style scoped>
  .welcome-card {
    max-width: 860px;
    margin: 10vh auto 0;
    text-align: center;
  }
  .welcome-icon {
    color: #2563eb;
    font-size: 40px;
  }
  h2 {
    margin: 12px 0 8px;
    color: #0f172a;
    font-size: 28px;
  }
  p {
    max-width: 680px;
    margin: 0 auto;
    color: #64748b;
    line-height: 1.7;
  }
  .project-facts {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 12px;
    margin: 26px 0 22px;
    text-align: left;
  }
  .project-facts > div {
    display: grid;
    gap: 5px;
    padding: 14px 16px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #fff;
  }
  .project-facts span,
  .examples > span {
    color: #64748b;
    font-size: 12px;
  }
  .project-facts strong {
    color: #0f172a;
  }
  .readiness-blocker {
    display: flex;
    max-width: 680px;
    margin: 0 auto 22px;
    padding: 14px 16px;
    align-items: center;
    justify-content: space-between;
    gap: 18px;
    border: 1px solid #fed7aa;
    border-radius: 12px;
    background: #fffaf5;
    text-align: left;
  }
  .readiness-blocker > div {
    display: grid;
    gap: 4px;
  }
  .readiness-blocker span {
    color: #9a3412;
    font-size: 11px;
    font-weight: 650;
  }
  .readiness-blocker strong {
    color: #7c2d12;
  }
  .readiness-blocker p {
    margin: 0;
    color: #9a3412;
    font-size: 12px;
    line-height: 1.5;
  }
  .examples {
    display: grid;
    gap: 8px;
    max-width: 680px;
    margin: 0 auto 22px;
    text-align: left;
  }
  .examples button {
    padding: 12px 14px;
    border: 1px solid #dbeafe;
    border-radius: 10px;
    background: #f8fbff;
    color: #1e3a8a;
    text-align: left;
    cursor: pointer;
    transition: 0.18s ease;
  }
  .examples button:hover {
    border-color: #93c5fd;
    background: #eff6ff;
  }
  @media (max-width: 760px) {
    .project-facts {
      grid-template-columns: 1fr;
    }
    .readiness-blocker {
      align-items: stretch;
      flex-direction: column;
    }
  }
</style>
