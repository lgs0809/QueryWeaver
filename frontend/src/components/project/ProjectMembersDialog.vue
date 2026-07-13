<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <el-dialog v-model="visible" title="项目成员" width="680px" :close-on-click-modal="false">
    <p class="intro">控制谁可以查看、编辑或管理这个项目。服务端权限仍是最终安全边界。</p>

    <div class="add-member">
      <el-input v-model="memberId" placeholder="输入成员账号" />
      <el-select v-model="memberRole" style="width: 150px">
        <el-option label="查看" value="VIEWER" />
        <el-option label="编辑" value="EDITOR" />
        <el-option label="项目所有者" value="OWNER" />
      </el-select>
      <el-button type="primary" :loading="saving" :disabled="!memberId.trim()" @click="saveMember">
        添加 / 更新
      </el-button>
    </div>

    <el-table v-loading="loading" :data="members" empty-text="暂无项目成员">
      <el-table-column prop="operatorId" label="成员" min-width="180" />
      <el-table-column label="权限" width="140">
        <template #default="scope">
          <el-tag :type="roleType(scope.row.accessRole)" effect="plain">
            {{ roleLabel(scope.row.accessRole) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="grantedBy" label="授权人" min-width="150" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="scope">
          <el-button link type="danger" @click="removeMember(scope.row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="visible = false">完成</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    queryWeaverService,
    type ProjectAccessRole,
    type ProjectMembership,
  } from '@/services/queryweaver';

  const props = defineProps<{ modelValue: boolean; projectId: number }>();
  const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();
  const visible = computed({
    get: () => props.modelValue,
    set: value => emit('update:modelValue', value),
  });
  const members = ref<ProjectMembership[]>([]);
  const loading = ref(false);
  const saving = ref(false);
  const memberId = ref('');
  const memberRole = ref<ProjectAccessRole>('VIEWER');

  const load = async () => {
    if (!visible.value) return;
    loading.value = true;
    try {
      members.value = await queryWeaverService.projectMembers(props.projectId);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '项目成员加载失败');
    } finally {
      loading.value = false;
    }
  };

  const saveMember = async () => {
    const id = memberId.value.trim();
    if (!id) return;
    saving.value = true;
    try {
      await queryWeaverService.grantProjectMember(props.projectId, id, memberRole.value);
      memberId.value = '';
      memberRole.value = 'VIEWER';
      ElMessage.success('项目成员权限已更新');
      await load();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '项目成员更新失败');
    } finally {
      saving.value = false;
    }
  };

  const removeMember = async (member: ProjectMembership) => {
    try {
      await ElMessageBox.confirm(
        `确定移除“${member.operatorId}”的项目访问权限吗？`,
        '移除项目成员',
        { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' },
      );
      await queryWeaverService.revokeProjectMember(props.projectId, member.operatorId);
      ElMessage.success('项目成员已移除');
      await load();
    } catch (error) {
      if (error === 'cancel' || error === 'close') return;
      ElMessage.error(error instanceof Error ? error.message : '移除项目成员失败');
    }
  };

  const roleLabel = (role: ProjectAccessRole) => {
    if (role === 'OWNER') return '项目所有者';
    if (role === 'EDITOR') return '编辑';
    return '查看';
  };
  const roleType = (role: ProjectAccessRole) => {
    if (role === 'OWNER') return 'success';
    if (role === 'EDITOR') return 'primary';
    return 'info';
  };

  watch(visible, value => {
    if (value) void load();
  });
</script>

<style scoped>
  .intro {
    margin: -4px 0 18px;
    color: #64748b;
    line-height: 1.6;
  }
  .add-member {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 150px auto;
    gap: 10px;
    margin-bottom: 18px;
  }
  @media (max-width: 680px) {
    .add-member {
      grid-template-columns: 1fr;
    }
  }
</style>
