import { expect, it } from "vitest";

async function factory() {
  const module = await import(/* @vite-ignore */ new URL("../src/article/editor.ts", import.meta.url).href).catch(() => ({}));
  expect(module).toHaveProperty("createArticleEditor");
  return (module as any).createArticleEditor;
}
const article = (id = 1, title = "original") => ({ id, title, content: "body", status: "DRAFT" });
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const deferred = () => { let resolve!: (r: Response) => void; const promise = new Promise<Response>(r => resolve = r); return { promise, resolve }; };

it("keeps the newest list when older requests arrive late", async () => {
  const create = await factory(), old = deferred(); let reads = 0;
  const model = create(async () => ++reads === 1 ? old.promise : json([article(2)]));
  const first = model.load(); await model.load(); old.resolve(json([article(1)])); await first;
  expect(model.state.articles[0].id).toBe(2);
});

it("sends one write and preserves edits made while that write is pending", async () => {
  const create = await factory(), pending = deferred(); let writes = 0;
  const model = create(async (_: string, init?: RequestInit) => init?.method === "PUT" ? (++writes, pending.promise) : json([article()]));
  await model.load(); model.beginEdit(article()); const saving = model.save();
  model.state.editor.title = "new unsaved work"; await model.save();
  expect(writes).toBe(1); pending.resolve(json(article())); await saving;
  expect(model.state.editor.title).toBe("new unsaved work"); expect(model.state.editingId).toBe(1);
  expect(model.state.busy).toBe(false);
});

it("a save cannot clear another article selected during the request", async () => {
  const create = await factory(), pending = deferred();
  const model = create(async (_: string, init?: RequestInit) => init?.method === "PUT" ? pending.promise : json([article(), article(2)]));
  await model.load(); model.beginEdit(article()); const saving = model.save(); model.beginEdit(article(2));
  pending.resolve(json(article())); await saving;
  expect(model.state.editingId).toBe(2); expect(model.state.editor.title).toBe("original");
});

it("edits made during creation continue updating the created ID instead of posting a duplicate", async () => {
  const create = await factory(), pending = deferred(); const writes: { url: string; method: string }[] = [];
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method) { writes.push({ url, method: init.method }); return writes.length === 1 ? pending.promise : json(article(42)); }
    return json([]);
  });
  await model.load(); Object.assign(model.state.editor, { title: "new article", content: "body" });
  const saving = model.save(); model.state.editor.content = "continued typing";
  pending.resolve(json(article(42), 201)); await saving;
  expect(model.state.editor.content).toBe("continued typing"); expect(model.state.editingId).toBe(42);
  await model.save(); expect(writes).toEqual([{ url: "/api/articles", method: "POST" }, { url: "/api/articles/42", method: "PUT" }]);
});

it("unknown write outcomes keep the draft and require refresh plus explicit acknowledgement", async () => {
  const create = await factory(); let writes = 0;
  const model = create(async (_: string, init?: RequestInit) => {
    if (init?.method === "POST") { writes++; throw new Error("connection lost"); }
    return json([]);
  });
  await model.load(); model.state.editor.title = "draft"; model.state.editor.content = "body";
  await model.save(); expect(model.state.uncertain).toBe(true); expect(model.state.editor.title).toBe("draft");
  model.acknowledge(); await model.save(); expect(writes).toBe(1);
  await model.load(); await model.save(); expect(writes).toBe(1);
  model.acknowledge(); expect(model.canWrite()).toBe(true);
});

it("a created ID cannot attach to a different selection and malformed create results stay uncertain", async () => {
  const create = await factory(), pending = deferred(); let writes = 0;
  const model = create(async (_: string, init?: RequestInit) => init?.method === "POST" ? (++writes === 1 ? pending.promise : json({}, 201)) : json([article(2)]));
  await model.load(); Object.assign(model.state.editor, { title: "new article", content: "body" });
  const saving = model.save(); model.beginEdit(article(2)); pending.resolve(json(article(42), 201)); await saving;
  expect(model.state.editingId).toBe(2);
  model.reset(); Object.assign(model.state.editor, { title: "new draft", content: "body" }); await model.save();
  expect(model.state.uncertain).toBe(true); expect(model.state.editor.title).toBe("new draft"); expect(model.canWrite()).toBe(false);
});

it("a completed write with failed reload is reported as saved and cannot be duplicated", async () => {
  const create = await factory(); let reads = 0, writes = 0;
  const model = create(async (_: string, init?: RequestInit) => {
    if (init?.method === "POST") { writes++; return json(article(), 201); }
    if (++reads > 1) throw new Error("offline"); return json([]);
  });
  await model.load(); Object.assign(model.state.editor, { title: "draft", content: "body" });
  await model.save(); expect(model.state.feedback).toContain("已新增"); expect(model.state.error).toContain("读取失败");
  expect(model.canWrite()).toBe(false); expect(model.state.uncertain).toBe(false); await model.save(); expect(writes).toBe(1);
});

it("a stale read cannot resurrect a deleted article", async () => {
  const create = await factory(), old = deferred(); let reads = 0;
  const model = create(async (_: string, init?: RequestInit) => {
    if (init?.method === "DELETE") return new Response(null, { status: 204 });
    return ++reads === 2 ? old.promise : json(reads <= 3 ? [article()] : []);
  });
  await model.load(); const refresh = model.load();
  // A pending read is intentionally insufficient authorization for a destructive write.
  await model.remove(article()); expect(model.state.busy).toBe(false);
  await model.load(); await model.remove(article()); old.resolve(json([article()])); await refresh;
  expect(model.state.articles).toEqual([]);
});

it("server validation errors preserve the draft and disposal ignores delayed responses", async () => {
  const create = await factory(), old = deferred(); let reads = 0;
  const model = create(async (_: string, init?: RequestInit) => init?.method === "POST" ? json({}, 400) : ++reads === 1 ? json([]) : old.promise);
  await model.load(); Object.assign(model.state.editor, { title: "draft", content: "body" }); await model.save();
  expect(model.state.editor.title).toBe("draft"); expect(model.state.uncertain).toBe(false); expect(model.state.error).toContain("400");
  const loading = model.load(); model.dispose(); old.resolve(json([article()])); await loading;
  expect(model.state.articles).toEqual([]); expect(model.canWrite()).toBe(false);
});
