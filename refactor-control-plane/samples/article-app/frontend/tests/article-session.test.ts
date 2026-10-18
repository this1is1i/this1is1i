import { expect, it } from "vitest";
import { createArticleEditor } from "../src/article/editor";
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const session = (subject = "alice", token = "token") => ({ authenticated: !!subject, subject, csrfToken: token, csrfHeader: "X-CSRF-TOKEN" });
const article = { id: 1, title: "title", content: "<p>body</p>", status: "DRAFT", menuId: 201, version: 4, publicPath: "/alice/articles/a" };

it("anonymous readers cannot write and receive only public publications", async () => {
  const urls: string[] = [];
  const model = createArticleEditor((async (url: string) => { urls.push(url); return json(url === "/api/session" ? session("") : []); }) as typeof fetch);
  await model.load(); Object.assign(model.state.editor, { title: "new", content: "body" }); await model.save();
  expect(model.canWrite()).toBe(false); expect(urls).not.toContain("/api/manage/articles");
  expect(model.state.subject).toBe("");
});

it("sends csrf and expectedVersion for edits and soft deletion", async () => {
  const writes: { url: string; init: RequestInit }[] = [];
  const model = createArticleEditor((async (url: string, init?: RequestInit) => {
    if (init?.method) { writes.push({ url, init }); return init.method === "DELETE" ? new Response(null, { status: 204 }) : json({ ...article, version: 5 }); }
    return json(url === "/api/session" ? session() : url === "/api/manage/menus" ? [{ id: 201, blogUrl: "alice", menuName: "articles" }] : url === "/api/manage/articles" ? [article] : []);
  }) as typeof fetch);
  await model.load(); model.beginEdit(article as any); await model.save(); await model.remove({ ...article, version: 5 } as any);
  expect(new Headers(writes[0].init.headers).get("X-CSRF-TOKEN")).toBe("token");
  expect(JSON.parse(writes[0].init.body as string).expectedVersion).toBe(4);
  expect(writes[1].url).toBe("/api/manage/articles/1?expectedVersion=5");
});

it("a late private list cannot overwrite a newer anonymous session", async () => {
  let resolve!: (value: Response) => void; let identities = 0;
  const pending = new Promise<Response>(done => resolve = done);
  const model = createArticleEditor((async (url: string) => {
    if (url === "/api/session") return json(session(++identities === 1 ? "alice" : ""));
    if (url === "/api/manage/articles") return pending;
    return json([]);
  }) as typeof fetch);
  const first = model.load();
  await new Promise(done => setTimeout(done, 0)); await model.load();
  resolve(json([article])); await first;
  expect(model.state.subject).toBe(""); expect(model.state.articles).toEqual([]); expect(model.canWrite()).toBe(false);
});

it("login refreshes csrf before management reads and never sends plaintext credentials as JSON", async () => {
  let loggedIn = false; const writes: RequestInit[] = [];
  const model = createArticleEditor((async (url: string, init?: RequestInit) => {
    if (url === "/api/session/login") { writes.push(init!); loggedIn = true; return new Response(null, { status: 204 }); }
    return json(url === "/api/session" ? session(loggedIn ? "alice" : "", loggedIn ? "rotated" : "before") : []);
  }) as typeof fetch);
  await model.load(); await model.login("alice", "private-password");
  expect(model.state.subject).toBe("alice"); expect(new Headers(writes[0].headers).get("X-CSRF-TOKEN")).toBe("before");
  expect(writes[0].body).toBe("username=alice&password=private-password");
  expect(JSON.stringify(model.state)).not.toContain("private-password");
});

it("unconfirmed sessions clear private drafts and lists", async () => {
  let failed = false;
  const model = createArticleEditor((async (url: string) => {
    if (url === "/api/session") { if (failed) throw new Error("offline"); return json(session()); }
    return json(url === "/api/manage/articles" ? [article] : url === "/api/manage/menus" ? [{ id: 201 }] : []);
  }) as typeof fetch);
  await model.load(); model.beginEdit(article as any); failed = true; await model.load();
  expect(model.state.subject).toBe(""); expect(model.state.articles).toEqual([]); expect(model.state.editor.content).toBe("");
  failed = false; await model.load();
  expect(model.state.subject).toBe("alice"); expect(model.state.editor.content).toBe(article.content); expect(model.state.editingVersion).toBe(4);
});

it("late public previews cannot reappear after refreshing deleted content", async () => {
  let resolve!: (value: Response) => void; const pending = new Promise<Response>(done => resolve = done);
  const model = createArticleEditor((async (url: string) => url.startsWith("/api/public/") ? pending : json(url === "/api/session" ? session("") : [])) as typeof fetch);
  await model.load(); const preview = model.openPublication(article.publicPath); await model.load();
  resolve(json({ id: 1, title: "deleted", html: "<p>hidden</p>" })); await preview;
  expect(model.state.publication).toBeNull();
});
