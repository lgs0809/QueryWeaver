export type OperatorRole = 'VIEWER' | 'EDITOR' | 'REVIEWER' | 'PUBLISHER' | 'ADMIN';
export type ProjectAccessRole = 'VIEWER' | 'EDITOR' | 'OWNER';
export type ProjectSection = 'overview' | 'external' | 'prepare' | 'improve' | 'governance';
export type ProjectListAction = 'VIEW' | 'CHAT' | 'PREPARE';

export interface ProjectCapabilitySet {
  edit: boolean;
  review: boolean;
  manage: boolean;
}

export interface ProjectListSummaryCapabilityInput {
  available: boolean;
  queryReady: boolean;
  accessRole?: ProjectAccessRole;
  globalAdmin?: boolean;
}

export function defaultHomeForRole(role?: OperatorRole): '/chat' | '/projects';
export function canEditProject(
  accessRole?: ProjectAccessRole,
  operatorRole?: OperatorRole,
  globalAdmin?: boolean,
): boolean;
export function canReviewProject(
  accessRole?: ProjectAccessRole,
  operatorRole?: OperatorRole,
  globalAdmin?: boolean,
): boolean;
export function canManageProject(
  accessRole?: ProjectAccessRole,
  operatorRole?: OperatorRole,
  globalAdmin?: boolean,
): boolean;
export function projectSectionVisible(
  section: ProjectSection,
  capabilities: ProjectCapabilitySet,
): boolean;
export function projectListAction(
  summary: ProjectListSummaryCapabilityInput | undefined,
  operatorRole?: OperatorRole,
): ProjectListAction;
