import { createSSRApp, type Component } from "vue";
import { renderToString } from "@vue/server-renderer";
import { describe, expect, it } from "vitest";

async function loadWorkbench(): Promise<Component | undefined> {
  const componentUrl = new URL("../src/App.vue", import.meta.url).href;
  const appModule = await import(/* @vite-ignore */ componentUrl).catch(() => ({}));

  expect(appModule).toHaveProperty("default");
  return (appModule as { default?: Component }).default;
}

describe("role workbench", () => {
  it("renders the project manager command center and every approved role", async () => {
    const component = await loadWorkbench();
    if (!component) return;

    const html = await renderToString(createSSRApp(component));

    expect(html).toContain("Legacy Blog Modernization");
    expect(html).toContain("项目经理");
    expect(html).toContain("计划与资源");
    expect(html.match(/data-role-id=/g)).toHaveLength(8);
    expect(html).toContain("静态演示");
    expect(html).toContain("后端未连接");
    expect(html).not.toContain("治理服务正常");
    expect(html).toMatch(/<button[^>]*disabled[^>]*>查看任务图/);
  });

  it("renders role-specific priorities when an architect opens the workbench", async () => {
    const component = await loadWorkbench();
    if (!component) return;

    const html = await renderToString(
      createSSRApp(component, { initialRole: "architect" })
    );

    expect(html).toContain("架构师");
    expect(html).toContain("架构与决策");
    expect(html).toContain("语义影响");
  });
});
