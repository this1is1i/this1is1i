import { createSSRApp, type Component } from "vue";
import { renderToString } from "@vue/server-renderer";
import { expect, it } from "vitest";

it("renders the Vue 3 article migration workspace", async () => {
  const moduleUrl = new URL("../src/article/ArticleWorkspace.vue", import.meta.url).href;
  const module = await import(/* @vite-ignore */ moduleUrl).catch(() => ({}));
  expect(module).toHaveProperty("default");

  const html = await renderToString(createSSRApp((module as { default: Component }).default));
  expect(html).toContain("文章管理迁移切片");
  expect(html).toContain("Vue 3 + Spring Boot");
  expect(html).toContain("/api/articles");
  expect(html).toContain("新增文章");
  expect(html).toContain("编辑");
  expect(html).toContain("删除");
});
