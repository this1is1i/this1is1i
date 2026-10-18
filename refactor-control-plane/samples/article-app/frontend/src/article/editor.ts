import { reactive } from "vue";

export interface Article { id: number; title: string; content: string; status: "DRAFT" | "PUBLISHED" | "ARCHIVED"; menuId: number; version: number; publicPath: string }
interface Menu { id: number; blogUrl: string; menuName: string }
interface Publication { id: number; title: string; html: string; publicPath: string }
type Draft = Pick<Article, "title" | "content" | "status" | "menuId">;
const empty = (menuId = 0): Draft => ({ title: "", content: "", status: "DRAFT", menuId });

export function createArticleEditor(fetcher: typeof fetch = fetch) {
  let alive = true, readSequence = 0, selection = 0, previewSequence = 0;
  let csrfToken = "", csrfHeader = "";
  let suspended: { subject: string; editor: Draft; id: number | null; version: number | null } | null = null;
  const state = reactive({ articles: [] as Article[], menus: [] as Menu[], publications: [] as Publication[], publication: null as Publication | null,
    subject: "", sessionReady: false, editor: empty(), editingId: null as number | null, editingVersion: null as number | null,
    loading: false, fresh: false, busy: false, uncertain: false, feedback: "", error: "" });
  const canWrite = () => alive && !!state.subject && !!csrfToken && state.fresh && state.menus.length > 0 && !state.busy && !state.uncertain;
  async function read(url: string) {
    const response = await fetcher(url, { cache: "no-store", credentials: "same-origin", headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }
  function reset() { selection++; state.editingId = null; state.editingVersion = null; state.editor = empty(state.menus[0]?.id); }
  function clearPrivate() { state.articles = []; state.menus = []; state.feedback = ""; reset(); }
  async function load() {
    if (!alive) return;
    const request = ++readSequence; state.loading = true; state.fresh = false; state.error = "";
    previewSequence++; state.publication = null;
    let sessionConfirmed = false;
    try {
      const session = await read("/api/session");
      if (!alive || request !== readSequence) return;
      if (typeof session.authenticated !== "boolean" || typeof session.subject !== "string" || typeof session.csrfToken !== "string" || session.csrfHeader !== "X-CSRF-TOKEN")
        throw new Error("会话响应无效");
      const subject = session.authenticated ? session.subject : "";
      if (state.subject !== subject) clearPrivate();
      if (subject && suspended?.subject === subject) {
        state.editor = suspended.editor; state.editingId = suspended.id; state.editingVersion = suspended.version; suspended = null;
      }
      state.subject = subject; state.sessionReady = true; csrfToken = session.csrfToken; csrfHeader = session.csrfHeader;
      sessionConfirmed = true;
      const [articles, menus, publications] = await Promise.all([
        subject ? read("/api/manage/articles") : [], subject ? read("/api/manage/menus") : [], read("/api/articles")
      ]);
      if (!Array.isArray(articles) || !Array.isArray(menus) || !Array.isArray(publications)) throw new Error("列表响应无效");
      if (!alive || request !== readSequence) return;
      state.articles = articles; state.menus = menus; state.publications = publications;
      if (state.editingId === null && !state.editor.menuId) state.editor.menuId = menus[0]?.id ?? 0;
      state.fresh = true;
    } catch (error) {
      if (alive && request === readSequence) {
        if (!sessionConfirmed) {
          if (state.subject) suspended = { subject: state.subject, editor: { ...state.editor }, id: state.editingId, version: state.editingVersion };
          clearPrivate(); state.subject = "";
        }
        csrfToken = ""; state.sessionReady = false;
        state.error = `读取失败：${error instanceof Error ? error.message : "网络异常"}；写操作已禁用，请刷新。`;
      }
    } finally { if (alive && request === readSequence) state.loading = false; }
  }
  function beginEdit(article: Article) {
    if (!alive || !state.subject) return;
    selection++; state.editingId = article.id; state.editingVersion = article.version;
    state.editor = { title: article.title, content: article.content, status: article.status, menuId: article.menuId };
    state.feedback = `正在编辑 #${article.id}`;
  }
  async function authenticate(action: "login" | "logout", username = "", password = "") {
    if (!alive || state.busy || state.loading || !csrfToken) return;
    state.busy = true; state.fresh = false; state.error = ""; readSequence++;
    try {
      const response = await fetcher(`/api/session/${action}`, { method: "POST", credentials: "same-origin",
        headers: { [csrfHeader]: csrfToken, "Content-Type": "application/x-www-form-urlencoded" },
        ...(action === "login" ? { body: new URLSearchParams({ username, password }).toString() } : {}) });
      if (!alive) return;
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      if (action === "logout") suspended = null;
      clearPrivate(); state.subject = ""; state.sessionReady = false; csrfToken = "";
      await load();
    } catch (error) {
      if (!alive) return;
      csrfToken = ""; state.sessionReady = false; clearPrivate(); state.subject = "";
      state.error = `会话操作未确认：${error instanceof Error ? error.message : "网络异常"}；请刷新确认登录状态。`;
    } finally { if (alive) state.busy = false; }
  }
  async function write(method: string, id: number | null, draft?: Draft, expectedVersion?: number) {
    if (!canWrite()) return;
    const selected = selection, previousDraft = JSON.stringify(state.editor), previousId = state.editingId;
    state.busy = true; state.error = ""; state.feedback = ""; state.fresh = false;
    previewSequence++; state.publication = null;
    readSequence++; state.loading = false;
    let definitive = false;
    try {
      const url = id === null ? "/api/manage/articles" : `/api/manage/articles/${id}${method === "DELETE" ? `?expectedVersion=${expectedVersion}` : ""}`;
      const response = await fetcher(url, { method, credentials: "same-origin",
        headers: { Accept: "application/json", [csrfHeader]: csrfToken, ...(draft ? { "Content-Type": "application/json" } : {}) },
        ...(draft ? { body: JSON.stringify({ ...draft, ...(id !== null ? { expectedVersion } : {}) }) } : {}) });
      if (!response.ok) {
        definitive = [400, 401, 403, 404, 405, 409, 422].includes(response.status);
        throw new Error(`HTTP ${response.status}`);
      }
      const saved = method === "DELETE" ? null : await response.json() as Article;
      if (method !== "DELETE" && (!saved || !Number.isSafeInteger(saved.id) || saved.id <= 0 || !Number.isSafeInteger(saved.version) || saved.version <= 0))
        throw new Error("保存响应缺少有效文章 ID 或版本");
      if (!alive) return;
      state.feedback = method === "DELETE" ? `已软删除 #${id}。` : id === null ? "文章已新增。" : `文章 #${id} 已更新。`;
      if (selected === selection && previousId === state.editingId) {
        if (previousDraft === JSON.stringify(state.editor) && (method !== "DELETE" || state.editingId === id)) reset();
        else if (saved) { state.editingId = saved.id; state.editingVersion = saved.version; state.editor.menuId = saved.menuId; }
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
    if (!state.editor.title.trim() || !state.editor.content.trim() || !state.editor.menuId) { state.error = "标题、正文和所属菜单不能为空。"; return; }
    await write(state.editingId === null ? "POST" : "PUT", state.editingId, { ...state.editor }, state.editingVersion ?? undefined);
  }
  async function openPublication(path: string) {
    const request = ++previewSequence; state.publication = null;
    try {
      if (!/^\/[^/?#]+\/[^/?#]+\/[^/?#]+$/.test(path)) throw new Error("文章地址无效");
      const publication = await read("/api/public" + path);
      if (alive && request === previewSequence) state.publication = publication;
    } catch (error) { if (alive && request === previewSequence) state.error = `阅读失败：${error instanceof Error ? error.message : "网络异常"}`; }
  }
  return { state, load, beginEdit, reset, save, canWrite, openPublication,
    login: (username: string, password: string) => authenticate("login", username, password),
    logout: () => authenticate("logout"), remove: (article: Article) => write("DELETE", article.id, undefined, article.version),
    acknowledge: () => { if (alive && state.fresh && !state.busy) state.uncertain = false; },
    dispose: () => { alive = false; readSequence++; previewSequence++; csrfToken = ""; } };
}
