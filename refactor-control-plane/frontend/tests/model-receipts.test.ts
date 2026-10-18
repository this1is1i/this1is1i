import { createSSRApp } from "vue";
import { renderToString } from "@vue/server-renderer";
import { expect, it } from "vitest";

async function render(props: Record<string, unknown>) {
  const module = await import(/* @vite-ignore */ new URL("../src/integration/ModelReceipts.vue", import.meta.url).href);
  expect(module).toHaveProperty("default");
  return renderToString(createSSRApp((module as any).default, props));
}
it("distinguishes unreported model use from an explicitly empty executor receipt", async () => {
  expect(await render({})).toContain("未报告模型调用");
  expect(await render({ runs: [] })).toContain("报告未使用模型");
});
it("labels executor provenance and separates requested tools from reported actual calls", async () => {
  const html = await render({ evidenceRef: "evidence-123", runs: [{ id: "model-1", model: "internal-model", promptVersion: "v1",
    inputHash: "input-hash", contextHash: "context-hash", outputHash: "output-hash", totalTokens: 42,
    requestedTools: ["write_file", "unused_tool"], toolCalls: [{ id: "tool-1", name: "write_file", state: "SUCCEEDED" }] }] });
  expect(html).toContain("执行服务声明");
  expect(html).toContain("请求工具：write_file, unused_tool");
  expect(html).toContain("实际调用记录：1");
  expect(html).toContain("evidence-123");
  expect(html).toContain("internal-model");
});
