import { describe, expect, it } from "vitest";

describe("role workbench contract", () => {
  it("provides every approved role and its role-specific primary view", async () => {
    const moduleUrl = new URL("../src/role-workbench.ts", import.meta.url).href;
    const roleModule = await import(/* @vite-ignore */ moduleUrl).catch(() => ({}));

    expect(roleModule).toHaveProperty("roles");
    expect(roleModule.roles.map((role: { id: string }) => role.id)).toEqual([
      "project-manager",
      "architect",
      "engineering-lead",
      "developer",
      "qa-lead",
      "business-approver",
      "platform-admin",
      "auditor"
    ]);
    expect(roleModule.getRoleWorkspace("project-manager").primaryView).toBe("计划与资源");
    expect(roleModule.getRoleWorkspace("architect").primaryView).toBe("架构与决策");
    expect(roleModule.getRoleWorkspace("qa-lead").primaryView).toBe("质量门禁");
    expect(() => roleModule.getRoleWorkspace("unknown-role")).toThrowError(
      "Unknown role workspace: unknown-role"
    );
  });
});
