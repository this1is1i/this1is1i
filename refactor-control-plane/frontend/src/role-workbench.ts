export type RoleId =
  | "project-manager"
  | "architect"
  | "engineering-lead"
  | "developer"
  | "qa-lead"
  | "business-approver"
  | "platform-admin"
  | "auditor";

export interface RoleWorkspace {
  id: RoleId;
  label: string;
  primaryView: string;
  headline: string;
  responsibilities: readonly string[];
}

export const roles: readonly RoleWorkspace[] = [
  {
    id: "project-manager",
    label: "项目经理",
    primaryView: "计划与资源",
    headline: "协调关键路径、人员容量与交付风险",
    responsibilities: ["工作包编排", "资源冲突", "里程碑", "风险燃尽"]
  },
  {
    id: "architect",
    label: "架构师",
    primaryView: "架构与决策",
    headline: "让每项升级决策都能追溯到项目证据",
    responsibilities: ["现状架构", "目标架构", "ADR", "语义影响"]
  },
  {
    id: "engineering-lead",
    label: "开发负责人",
    primaryView: "交付与集成",
    headline: "控制任务边界、代码审查与集成顺序",
    responsibilities: ["任务细化", "接口契约", "代码 Review", "集成候选"]
  },
  {
    id: "developer",
    label: "开发人员",
    primaryView: "执行工作台",
    headline: "在授权 Worktree 中完成可验收的变更",
    responsibilities: ["任务上下文", "文件租约", "变更提交", "阻塞上报"]
  },
  {
    id: "qa-lead",
    label: "测试负责人",
    primaryView: "质量门禁",
    headline: "以测试证据决定变更能否进入验收",
    responsibilities: ["测试矩阵", "环境槽位", "回归影响", "门禁结果"]
  },
  {
    id: "business-approver",
    label: "业务验收人",
    primaryView: "业务验收",
    headline: "确认重构前后的业务行为保持一致",
    responsibilities: ["业务场景", "规则对照", "验收意见", "最终签字"]
  },
  {
    id: "platform-admin",
    label: "平台管理员",
    primaryView: "平台配置",
    headline: "管理模型、执行器、规则模板与访问权限",
    responsibilities: ["模型网关", "执行器", "角色权限", "规则模板"]
  },
  {
    id: "auditor",
    label: "审计与安全",
    primaryView: "审计与风险",
    headline: "检查决策、模型调用、审批与高风险操作",
    responsibilities: ["审计时间线", "模型调用", "例外审批", "风险报告"]
  }
];

export function getRoleWorkspace(roleId: RoleId): RoleWorkspace {
  const workspace = roles.find((role) => role.id === roleId);
  if (!workspace) {
    throw new Error(`Unknown role workspace: ${roleId}`);
  }
  return workspace;
}
