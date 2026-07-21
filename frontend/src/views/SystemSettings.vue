<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <BaseLayout>
    <section class="settings-page" v-loading="loading">
      <div class="heading">
        <div>
          <h1>系统设置</h1>
          <p>只展示 QueryWeaver 能从后端确认的运行事实；“已配置”与“已验证可用”严格区分。</p>
        </div>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-alert
        v-if="error"
        type="warning"
        show-icon
        :closable="false"
        title="系统依赖状态加载失败"
        :description="error"
      />

      <div class="service-grid">
        <el-card shadow="never">
          <div class="service-header">
            <div>
              <h2>业务理解与问数模型</h2>
              <p>用于项目初始化、业务理解和查询执行。</p>
            </div>
            <el-tag :type="statusType(readiness.chatModelReady)" effect="plain">
              {{ readiness.chatModelReady ? '已验证可用' : '尚未就绪' }}
            </el-tag>
          </div>
          <div class="fact-row">
            <span>配置</span>
            <strong>{{ readiness.chatModelConfigured ? '已有当前配置' : '未配置' }}</strong>
          </div>
          <div class="fact-row">
            <span>真实可用性验证</span>
            <strong>{{ readiness.chatModelReady ? '最近测试通过' : '尚未通过' }}</strong>
          </div>
          <div class="fact-row">
            <span>最近验证</span>
            <strong>{{ formatTime(readiness.chatModelLastValidationTime) }}</strong>
          </div>
        </el-card>

        <el-card shadow="never">
          <div class="service-header">
            <div>
              <h2>语义检索模型</h2>
              <p>用于 Semantic Retrieval 与历史案例的向量召回。</p>
            </div>
            <el-tag :type="statusType(readiness.embeddingModelReady)" effect="plain">
              {{ readiness.embeddingModelReady ? '已验证可用' : '尚未就绪' }}
            </el-tag>
          </div>
          <div class="fact-row">
            <span>配置</span>
            <strong>{{ readiness.embeddingModelConfigured ? '已有当前配置' : '未配置' }}</strong>
          </div>
          <div class="fact-row">
            <span>真实可用性验证</span>
            <strong>{{ readiness.embeddingModelReady ? '最近测试通过' : '尚未通过' }}</strong>
          </div>
          <div class="fact-row">
            <span>最近验证</span>
            <strong>{{ formatTime(readiness.embeddingModelLastValidationTime) }}</strong>
          </div>
        </el-card>

        <el-card shadow="never" class="optional-service">
          <div class="service-header">
            <div>
              <h2>语义重排模型</h2>
              <p>对混合召回候选进行 Rerank；未配置时自动使用 RRF 结果，不阻断基础问数。</p>
            </div>
            <el-tag :type="optionalStatusType(readiness.rerankModelConfigured, readiness.rerankModelReady)" effect="plain">
              {{ optionalStatusLabel(readiness.rerankModelConfigured, readiness.rerankModelReady) }}
            </el-tag>
          </div>
          <div class="fact-row">
            <span>配置</span>
            <strong>{{ readiness.rerankModelConfigured ? '已有当前配置' : '未配置（可选）' }}</strong>
          </div>
          <div class="fact-row">
            <span>真实可用性验证</span>
            <strong>{{ readiness.rerankModelReady ? '最近测试通过' : '尚未通过' }}</strong>
          </div>
          <div class="fact-row">
            <span>最近验证</span>
            <strong>{{ formatTime(readiness.rerankModelLastValidationTime) }}</strong>
          </div>
        </el-card>
      </div>

      <div class="actions">
        <el-button type="primary" @click="router.push('/admin/models')">管理与验证模型</el-button>
      </div>

      <details class="advanced">
        <summary>高级状态口径</summary>
        <p>
          “已有当前配置”只表示数据库中存在已启用配置；“已验证可用”必须由模型管理页发起一次真实模型调用并成功。
          任何模型配置修改都会使旧验证结果失效，重新变为“待验证”。
        </p>
      </details>
    </section>
  </BaseLayout>
</template>

<script setup lang="ts">
  import { onMounted, reactive, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import modelConfigService from '@/services/modelConfig';

  const router = useRouter();
  const loading = ref(false);
  const error = ref('');
  const readiness = reactive({
    chatModelConfigured: false,
    chatModelReady: false,
    chatModelLastValidationTime: undefined as string | undefined,
    embeddingModelConfigured: false,
    embeddingModelReady: false,
    embeddingModelLastValidationTime: undefined as string | undefined,
    rerankModelConfigured: false,
    rerankModelReady: false,
    rerankModelLastValidationTime: undefined as string | undefined,
    ready: false,
  });

  const load = async () => {
    loading.value = true;
    error.value = '';
    try {
      Object.assign(readiness, await modelConfigService.checkReady());
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '系统依赖状态加载失败';
    } finally {
      loading.value = false;
    }
  };

  const statusType = (ready: boolean) => (ready ? 'success' : 'warning');
  const optionalStatusType = (configured: boolean, ready: boolean) =>
    !configured ? 'info' : ready ? 'success' : 'warning';
  const optionalStatusLabel = (configured: boolean, ready: boolean) =>
    !configured ? '未配置（可选）' : ready ? '已验证可用' : '尚未就绪';
  const formatTime = (value?: string) =>
    value ? new Date(value.replace(' ', 'T')).toLocaleString('zh-CN') : '尚无验证记录';

  onMounted(load);
</script>

<style scoped>
  .settings-page {
    max-width: 1180px;
    margin: 0 auto;
    padding: 30px;
  }
  .heading,
  .service-header,
  .fact-row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 18px;
  }
  .heading h1 {
    margin: 0 0 6px;
    color: #0f172a;
    font-size: 30px;
  }
  .heading p,
  .service-header p,
  .advanced p {
    margin: 0;
    color: #64748b;
    line-height: 1.65;
  }
  .service-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 18px;
    margin: 24px 0;
  }
  .service-header {
    margin-bottom: 18px;
  }
  .service-header h2 {
    margin: 0 0 6px;
    color: #0f172a;
    font-size: 18px;
  }
  .fact-row {
    align-items: center;
    padding: 10px 0;
    border-top: 1px solid #eef2f7;
  }
  .fact-row span {
    color: #64748b;
  }
  .fact-row strong {
    color: #0f172a;
    text-align: right;
  }
  .optional-service {
    grid-column: 1 / -1;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
  }
  .advanced {
    margin-top: 22px;
    padding: 16px 18px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #f8fafc;
  }
  .advanced summary {
    cursor: pointer;
    color: #334155;
    font-weight: 650;
  }
  .advanced p {
    margin-top: 12px;
  }
  @media (max-width: 760px) {
    .settings-page {
      padding: 18px 10px;
    }
    .heading {
      flex-direction: column;
    }
    .service-grid {
      grid-template-columns: 1fr;
    }
    .optional-service {
      grid-column: auto;
    }
  }
</style>
