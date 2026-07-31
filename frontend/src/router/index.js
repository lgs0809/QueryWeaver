/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { createRouter, createWebHistory } from 'vue-router';
import { ElMessage } from 'element-plus';
import routes from '@/router/routes';
import { platformContext } from '@/services/platformContext';

const routerBase = import.meta.env.VITE_SEMEVOSQL_BASE_PATH || '/semevosql/';

const router = createRouter({
  history: createWebHistory(routerBase),
  routes,
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 };
  },
});

let hasShownWarning = false;
const roleRank = { VIEWER: 0, EDITOR: 1, REVIEWER: 2, PUBLISHER: 3, ADMIN: 4 };

const ensureRole = async minimumRole => {
  if (!minimumRole) return true;
  const operator = await platformContext.operator();
  return (roleRank[operator.role] ?? -1) >= (roleRank[minimumRole] ?? Number.MAX_SAFE_INTEGER);
};

router.beforeEach(async (to, from, next) => {
  document.title = to.meta?.title ? `${to.meta.title} - SemEvoSQL` : 'SemEvoSQL';

  try {
    if (!(await ensureRole(to.meta?.minimumRole))) {
      ElMessage.error(
        to.path === '/admin/models' ? '当前运行权限不足，无法管理模型' : '当前运行权限不足，无法访问此页面',
      );
      next('/projects');
      return;
    }
    hasShownWarning = false;
    next();
  } catch (error) {
    console.error('读取运行权限失败:', error);
    if (!hasShownWarning) {
      ElMessage.warning({
        message: '运行权限状态暂时不可用；受保护页面已保持关闭。',
        duration: 5000,
      });
      hasShownWarning = true;
    }
    if (to.meta?.minimumRole) {
      next('/projects');
      return;
    }
    next();
  }
});

export default router;
