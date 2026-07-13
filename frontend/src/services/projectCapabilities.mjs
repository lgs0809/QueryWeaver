/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

const GOVERNED_OPERATOR_ROLES = new Set(['EDITOR', 'REVIEWER', 'PUBLISHER', 'ADMIN']);
const REVIEW_OPERATOR_ROLES = new Set(['REVIEWER', 'PUBLISHER', 'ADMIN']);
const PROJECT_EDIT_ROLES = new Set(['EDITOR', 'OWNER']);

export function defaultHomeForRole(role) {
  return role === 'VIEWER' ? '/chat' : '/projects';
}

export function canEditProject(accessRole, operatorRole, globalAdmin = false) {
  return (
    Boolean(globalAdmin) ||
    (PROJECT_EDIT_ROLES.has(accessRole || '') && GOVERNED_OPERATOR_ROLES.has(operatorRole || ''))
  );
}

export function canReviewProject(accessRole, operatorRole, globalAdmin = false) {
  return (
    Boolean(globalAdmin) ||
    (PROJECT_EDIT_ROLES.has(accessRole || '') && REVIEW_OPERATOR_ROLES.has(operatorRole || ''))
  );
}

export function canManageProject(accessRole, operatorRole, globalAdmin = false) {
  return (
    Boolean(globalAdmin) ||
    (accessRole === 'OWNER' && GOVERNED_OPERATOR_ROLES.has(operatorRole || ''))
  );
}

export function projectSectionVisible(section, capabilities) {
  if (section === 'external') return capabilities.manage;
  if (section === 'prepare') return capabilities.edit;
  if (section === 'improve' || section === 'governance') return capabilities.review;
  return true;
}

export function projectListAction(summary, operatorRole) {
  if (!summary?.available) return 'VIEW';
  if (summary.queryReady) return 'CHAT';
  return canEditProject(summary.accessRole, operatorRole, summary.globalAdmin) ? 'PREPARE' : 'VIEW';
}
