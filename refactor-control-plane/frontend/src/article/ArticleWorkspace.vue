<script setup lang="ts">
import { onMounted, ref } from "vue";

interface Article {
  id: number;
  title: string;
  content: string;
  status: "DRAFT" | "PUBLISHED" | "ARCHIVED";
}

const articles = ref<Article[]>([]);
const state = ref<"loading" | "ready" | "offline">("loading");
const title = ref("");
const content = ref("");
const status = ref<Article["status"]>("DRAFT");
const editingId = ref<number | null>(null);
const feedback = ref("");

async function loadArticles() {
  state.value = "loading";
  try {
    const response = await fetch("/api/articles", { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    articles.value = await response.json() as Article[];
    state.value = "ready";
  } catch {
    state.value = "offline";
  }
}

function beginEdit(article: Article) {
  editingId.value = article.id;
  title.value = article.title;
  content.value = article.content;
  status.value = article.status;
  feedback.value = `正在编辑 #${article.id}`;
}

function resetEditor() {
  editingId.value = null;
  title.value = "";
  content.value = "";
  status.value = "DRAFT";
}

async function saveArticle() {
  if (!title.value.trim() || !content.value.trim()) {
    feedback.value = "标题和正文不能为空。";
    return;
  }
  const url = editingId.value === null ? "/api/articles" : `/api/articles/${editingId.value}`;
  const response = await fetch(url, {
    method: editingId.value === null ? "POST" : "PUT",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ title: title.value, content: content.value, status: status.value }),
  });
  if (!response.ok) {
    feedback.value = `保存失败：HTTP ${response.status}`;
    return;
  }
  feedback.value = editingId.value === null ? "文章已新增。" : "文章已更新。";
  resetEditor();
  await loadArticles();
}

async function removeArticle(article: Article) {
  const response = await fetch(`/api/articles/${article.id}`, { method: "DELETE" });
  if (!response.ok) {
    feedback.value = `删除失败：HTTP ${response.status}`;
    return;
  }
  feedback.value = `已删除 #${article.id}`;
  if (editingId.value === article.id) resetEditor();
  await loadArticles();
}

onMounted(loadArticles);
</script>

<template>
  <section class="panel article-workspace">
    <div class="panel-heading">
      <div>
        <span class="eyebrow">验收切片 / Vue 3 + Spring Boot</span>
        <h3>文章管理迁移切片</h3>
      </div>
      <button class="text-button" type="button" @click="loadArticles">刷新</button>
    </div>
    <p class="article-contract">版本化契约：<code>/api/articles</code> · 最终合并仍需人工批准</p>
    <p class="article-contract">支持新增文章、编辑与删除；所有操作通过同一版本化 REST 契约。</p>
    <form class="article-editor" @submit.prevent="saveArticle">
      <label>标题 <input v-model="title" name="title" maxlength="200" required></label>
      <label>正文 <textarea v-model="content" name="content" rows="3" required></textarea></label>
      <label>状态
        <select v-model="status" name="status">
          <option value="DRAFT">DRAFT</option>
          <option value="PUBLISHED">PUBLISHED</option>
          <option value="ARCHIVED">ARCHIVED</option>
        </select>
      </label>
      <div class="article-editor-actions">
        <button class="text-button" type="submit">{{ editingId === null ? "新增文章" : "保存编辑" }}</button>
        <button v-if="editingId !== null" class="text-button" type="button" @click="resetEditor">取消编辑</button>
      </div>
      <p v-if="feedback" role="status">{{ feedback }}</p>
    </form>
    <div v-if="state === 'loading'" class="article-empty">正在加载文章…</div>
    <div v-else-if="state === 'offline'" class="article-empty">后端未连接，迁移切片处于只读说明模式。</div>
    <div v-else-if="articles.length === 0" class="article-empty">REST 服务已连接，当前暂无文章。</div>
    <ul v-else class="article-list">
      <li v-for="article in articles" :key="article.id">
        <div><strong>{{ article.title }}</strong><span>{{ article.status }}</span></div>
        <div class="article-row-actions">
          <button class="text-button" type="button" @click="beginEdit(article)">编辑</button>
          <button class="text-button danger" type="button" @click="removeArticle(article)">删除</button>
        </div>
      </li>
    </ul>
  </section>
</template>
