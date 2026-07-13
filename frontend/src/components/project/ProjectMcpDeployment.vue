<template>
  <div class="mcp-panel" v-loading="loading">
    <div class="mcp-heading">
      <div>
        <h2>外部 Agent 接入</h2>
        <p>将当前 Project 已发布的问数能力作为 Remote MCP Server 提供给外部 Agent。</p>
      </div>
      <el-tag v-if="deployment" :type="statusType" effect="plain">{{ deployment.status }}</el-tag>
    </div>

    <el-alert
      v-if="!queryReady"
      type="warning"
      show-icon
      :closable="false"
      title="Project 必须处于 READY 且存在 PUBLISHED 版本后才能部署。"
    />

    <el-empty
      v-if="!deployment || deployment.status === 'REVOKED'"
      description="尚未部署 MCP Server"
    >
      <el-button
        type="primary"
        :disabled="!canManage || !queryReady"
        :loading="submitting"
        @click="deploy"
      >
        部署 MCP Server
      </el-button>
    </el-empty>

    <template v-else>
      <el-descriptions :column="1" border class="deployment-details">
        <el-descriptions-item label="Endpoint">
          <div class="copy-row">
            <code>{{ deployment.endpoint }}</code>
            <el-button link @click="copy(deployment.endpoint)">复制</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="绑定版本">
          {{ deployment.projectVersionId }}
        </el-descriptions-item>
        <el-descriptions-item label="Service Principal">
          {{ deployment.principalId }}
        </el-descriptions-item>
        <el-descriptions-item label="最近使用">
          {{ deployment.lastUsedTime || '尚未调用' }}
        </el-descriptions-item>
        <el-descriptions-item label="重启恢复">
          {{ deployment.lastRecoveredTime || '无需恢复' }}
        </el-descriptions-item>
      </el-descriptions>

      <section v-if="operations" class="operations-panel">
        <div class="operations-heading">
          <div>
            <strong>运行与凭据状态</strong>
            <span>只展示持久化运行事实与审计记录，不回显 Credential。</span>
          </div>
          <el-button link :loading="testing" @click="loadOperations">刷新</el-button>
        </div>
        <div class="operations-grid">
          <div>
            <span>Credential 到期</span>
            <strong>
              {{
                operations.credentialExpiresAt
                  ? formatTime(operations.credentialExpiresAt)
                  : '不过期'
              }}
            </strong>
          </div>
          <div>
            <span>累计问数</span>
            <strong>{{ operations.totalQueries }}</strong>
          </div>
          <div>
            <span>失败问数</span>
            <strong>{{ operations.failedQueries }}</strong>
          </div>
          <div>
            <span>处理中</span>
            <strong>{{ operations.pendingQueries }}</strong>
          </div>
          <div>
            <span>审计事件</span>
            <strong>{{ operations.auditEvents }}</strong>
          </div>
        </div>
        <el-table v-if="operations.recentAudit.length" :data="operations.recentAudit" size="small">
          <el-table-column label="时间" width="190">
            <template #default="scope">{{ formatTime(scope.row.createTime) }}</template>
          </el-table-column>
          <el-table-column prop="action" label="动作" width="170" />
          <el-table-column prop="outcome" label="结果" width="130" />
          <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
        </el-table>
      </section>

      <el-alert
        v-if="credential"
        type="success"
        show-icon
        :closable="false"
        title="Credential 只显示这一次，请立即保存。"
        class="credential-alert"
      >
        <template #default>
          <div class="secret-row">
            <code>{{ credential }}</code>
            <el-button size="small" @click="copy(credential)">复制 Credential</el-button>
          </div>
          <div class="secret-row">
            <code>{{ connectionConfig }}</code>
            <el-button size="small" @click="copy(connectionConfig)">复制接入配置</el-button>
          </div>
        </template>
      </el-alert>

      <el-alert
        v-else
        type="info"
        show-icon
        :closable="false"
        title="系统不会再次返回已有 Credential；如已遗失，请轮换 Credential。"
        class="credential-alert"
      />

      <div class="tool-contract">
        <strong>Production MCP Tools</strong>
        <el-tag effect="plain">search_semantics</el-tag>
        <el-tag effect="plain">get_semantic_context</el-tag>
        <el-tag effect="plain">validate_query_plan</el-tag>
        <el-tag effect="plain">execute_query_plan</el-tag>
        <el-tag effect="plain">get_query_result</el-tag>
      </div>

      <div class="actions">
        <el-button :loading="testing" @click="checkDeployment">检查部署状态</el-button>
        <el-button
          v-if="deployment.status === 'RUNNING'"
          :disabled="!canManage"
          :loading="submitting"
          @click="disable"
        >
          停用
        </el-button>
        <el-button
          v-else
          type="primary"
          :disabled="!canManage || !queryReady"
          :loading="submitting"
          @click="enable"
        >
          启用
        </el-button>
        <el-button :disabled="!canManage" :loading="submitting" @click="rotate">
          轮换 Credential
        </el-button>
        <el-button type="danger" plain :disabled="!canManage" :loading="submitting" @click="revoke">
          删除 / 撤销
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { getApiErrorMessage } from '@/services/common';
  import {
    projectMcpService,
    type ProjectMcpCredentialResponse,
    type ProjectMcpDeployment,
    type ProjectMcpOperations,
  } from '@/services/queryweaverMcp';

  const props = defineProps<{
    projectId: number;
    canManage: boolean;
    queryReady: boolean;
  }>();

  const loading = ref(false);
  const submitting = ref(false);
  const testing = ref(false);
  const deployment = ref<ProjectMcpDeployment | null>(null);
  const operations = ref<ProjectMcpOperations>();
  const credential = ref('');
  const credentialConfig = ref<ProjectMcpCredentialResponse['config']>();

  const statusType = computed(() => {
    if (deployment.value?.status === 'RUNNING') return 'success';
    if (deployment.value?.status === 'DISABLED') return 'warning';
    return 'info';
  });

  const connectionConfig = computed(() => {
    if (!credentialConfig.value) return '';
    return JSON.stringify(
      {
        transport: 'streamable-http',
        url: credentialConfig.value.endpoint,
        headers: {
          [credentialConfig.value.header]: credentialConfig.value.headerValue,
        },
      },
      null,
      2,
    );
  });

  async function loadOperations() {
    if (!deployment.value || deployment.value.status === 'REVOKED' || !props.canManage) {
      operations.value = undefined;
      return;
    }
    operations.value = await projectMcpService.operations(props.projectId);
  }

  async function load() {
    loading.value = true;
    try {
      deployment.value = await projectMcpService.get(props.projectId);
      await loadOperations();
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '读取 MCP Deployment 失败'));
    } finally {
      loading.value = false;
    }
  }

  function reveal(response: ProjectMcpCredentialResponse) {
    deployment.value = response.deployment;
    credential.value = response.credential;
    credentialConfig.value = response.config;
  }

  async function deploy() {
    submitting.value = true;
    try {
      reveal(await projectMcpService.deploy(props.projectId));
      await loadOperations();
      ElMessage.success('MCP Server 已部署');
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '部署失败'));
    } finally {
      submitting.value = false;
    }
  }

  async function enable() {
    submitting.value = true;
    try {
      deployment.value = await projectMcpService.enable(props.projectId);
      await loadOperations();
      ElMessage.success('MCP Server 已启用');
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '启用失败'));
    } finally {
      submitting.value = false;
    }
  }

  async function disable() {
    submitting.value = true;
    try {
      deployment.value = await projectMcpService.disable(props.projectId);
      await loadOperations();
      ElMessage.success('MCP Server 已停用');
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '停用失败'));
    } finally {
      submitting.value = false;
    }
  }

  async function rotate() {
    await ElMessageBox.confirm('旧 Credential 会立即失效，确定继续？', '轮换 Credential', {
      type: 'warning',
    });
    submitting.value = true;
    try {
      reveal(await projectMcpService.rotateCredential(props.projectId));
      await loadOperations();
      ElMessage.success('Credential 已轮换');
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '轮换失败'));
    } finally {
      submitting.value = false;
    }
  }

  async function revoke() {
    await ElMessageBox.confirm(
      '该 Deployment 会被撤销，现有 Credential 立即失效。',
      '删除 MCP Deployment',
      {
        type: 'warning',
      },
    );
    submitting.value = true;
    try {
      await projectMcpService.revoke(props.projectId);
      credential.value = '';
      credentialConfig.value = undefined;
      operations.value = undefined;
      await load();
      ElMessage.success('MCP Deployment 已撤销');
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '撤销失败'));
    } finally {
      submitting.value = false;
    }
  }

  async function checkDeployment() {
    testing.value = true;
    try {
      const result = await projectMcpService.check(props.projectId);
      if (result.ok) {
        ElMessage.success('Deployment 运行状态与当前 Published Catalog 绑定正常');
      } else {
        ElMessage.warning('Deployment 状态或 Catalog 绑定已失效，请检查后重新启用');
      }
    } catch (error) {
      ElMessage.error(getApiErrorMessage(error, '部署状态检查失败'));
    } finally {
      testing.value = false;
    }
  }

  const formatTime = (value: string) => new Date(value).toLocaleString('zh-CN');

  async function copy(value: string) {
    await navigator.clipboard.writeText(value);
    ElMessage.success('已复制');
  }

  onMounted(load);
</script>

<style scoped>
  .mcp-panel {
    display: grid;
    gap: 18px;
  }

  .mcp-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  }

  .mcp-heading h2 {
    margin: 0 0 6px;
  }

  .mcp-heading p {
    margin: 0;
    color: var(--el-text-color-secondary);
  }

  .deployment-details {
    max-width: 960px;
  }

  .copy-row,
  .secret-row,
  .tool-contract,
  .actions {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  .copy-row code,
  .secret-row code {
    white-space: pre-wrap;
    word-break: break-all;
  }

  .operations-panel {
    display: grid;
    max-width: 960px;
    gap: 12px;
    padding: 16px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 12px;
  }

  .operations-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }

  .operations-heading > div {
    display: grid;
    gap: 4px;
  }

  .operations-heading span,
  .operations-grid span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .operations-grid {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 10px;
  }

  .operations-grid > div {
    display: grid;
    gap: 5px;
    padding: 10px;
    border-radius: 8px;
    background: var(--el-fill-color-light);
  }

  .credential-alert {
    max-width: 960px;
  }

  .credential-alert :deep(.el-alert__content) {
    width: 100%;
  }

  .secret-row + .secret-row {
    margin-top: 10px;
  }
</style>
