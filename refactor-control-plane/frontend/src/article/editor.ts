import { reactive } from "vue";

export interface Article { id: number; title: string; content: string; status: "DRAFT" | "PUBLISHED" | "ARCHIVED" }
type Draft = Omit<Article, "id">;
const empty = (): Draft => ({ title: "", content: "", status: "DRAFT" });

export function createArticleEditor(fetcher: typeof fetch = fetch) {
  let alive = true, readSequence = 0, selection = 0;
  const state = reactive({ articles: [] as Article[], editor: empty(), editingId: null as number | null,
    loading: false, fresh: false, busy: false, uncertain: false, feedback: "", error: "" });
  const canWrite = () => alive && state.fresh && !state.busy && !state.uncertain;
  async function load() {
    if (!alive) return;
    const request = ++readSequence; state.loading = true; state.fresh = false; state.error = "";
    try {
      const response = await fetcher("/api/articles", { cache: "no-store", headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const articles = await response.json() as Article[];
      if (!Array.isArray(articles)) throw new Error("响应不是文章列表");
      if (!alive || request !== readSequence) return;
      state.articles = articles; state.fresh = true;
    } catch (error) {
      if (alive && request === readSequence) state.error = `读取失败：${error instanceof Error ? error.message : "网络异常"}；写操作已禁用，请刷新。`;
    } finally { if (alive && request === readSequence) state.loading = false; }
  }
  function beginEdit(article: Article) {
    if (!alive) return;
    selection++; state.editingId = article.id;
    state.editor = { title: article.title, content: article.content, status: article.status };
    state.feedback = `正在编辑 #${article.id}`;
  }
  function reset() { selection++; state.editingId = null; state.editor = empty(); }
  async function write(method: string, id: number | null, draft?: Draft) {
    if (!canWrite()) return;
    const selected = selection, previousDraft = JSON.stringify(state.editor), previousId = state.editingId;
    state.busy = true; state.error = ""; state.feedback = ""; state.fresh = false;
    readSequence++; state.loading = false;
    let definitive = false;
    try {
      const response = await fetcher(id === null ? "/api/articles" : `/api/articles/${id}`, {
        method, headers: { Accept: "application/json", ...(draft ? { "Content-Type": "application/json" } : {}) },
        ...(draft ? { body: JSON.stringify(draft) } : {})
      });
      if (!response.ok) {
        definitive = [400, 401, 403, 404, 405, 409, 422].includes(response.status);
        throw new Error(`HTTP ${response.status}`);
      }
      const created = method === "POST" ? await response.json() as Article : null;
      if (method === "POST" && (!created || !Number.isSafeInteger(created.id) || created.id <= 0))
        throw new Error("新增响应缺少有效文章 ID");
      if (!alive) return;
      state.feedback = method === "DELETE" ? `已删除 #${id}。` : id === null ? "文章已新增。" : `文章 #${id} 已更新。`;
      if (selected === selection && previousId === state.editingId) {
        if (previousDraft === JSON.stringify(state.editor) && (method !== "DELETE" || state.editingId === id)) reset();
        else if (created) state.editingId = created.id;
      }
      await load();
    } catch (error) {
      if (!alive) return;
      readSequence++; state.loading = false; state.fresh = false; state.uncertain = !definitive;
      state.error = `${error instanceof Error ? error.message : "网络异常"}；${definitive ? "请求被拒绝，草稿已保留，请刷新后修改。" : "结果未知，草稿已保留；先刷新核对服务端结果，避免重复提交。"}`;
    } finally { if (alive) state.busy = false; }
  }
  async function save() {
    if (!canWrite()) return;
    if (!state.editor.title.trim() || !state.editor.content.trim()) { state.error = "标题和正文不能为空。"; return; }
    await write(state.editingId === null ? "POST" : "PUT", state.editingId, { ...state.editor });
  }
  return { state, load, beginEdit, reset, save, canWrite,
    remove: (article: Article) => write("DELETE", article.id),
    acknowledge: () => { if (alive && state.fresh && !state.busy) state.uncertain = false; },
    dispose: () => { alive = false; readSequence++; } };
}
