<script setup lang="ts">
import { onBeforeUnmount, onMounted } from "vue";
import { createArticleEditor } from "./editor";

const model = createArticleEditor();
const state = model.state;
onMounted(model.load);
onBeforeUnmount(model.dispose);
</script>

<template>
  <section class="panel article-workspace">
    <div class="panel-heading">
      <div>
        <span class="eyebrow">验收切片 / Vue 3 + Spring Boot</span>
        <h3>文章管理迁移切片</h3>
      </div>
      <button class="text-button" type="button" :disabled="state.loading" @click="model.load()">{{ state.loading ? "刷新中…" : "刷新" }}</button>
    </div>
    <p class="article-contract">文章契约：<code>/api/articles</code></p>
    <p class="article-contract">支持新增文章、编辑与删除；所有操作通过同一 REST 契约。</p>
    <form class="article-editor" @submit.prevent="model.save()">
      <label>标题 <input v-model="state.editor.title" name="title" maxlength="255" required></label>
      <label>正文 <textarea v-model="state.editor.content" name="content" rows="3" required></textarea></label>
      <label>状态
        <select v-model="state.editor.status" name="status">
          <option value="DRAFT">DRAFT</option>
          <option value="PUBLISHED">PUBLISHED</option>
          <option value="ARCHIVED">ARCHIVED</option>
        </select>
      </label>
      <div class="article-editor-actions">
        <button class="text-button" type="submit" :disabled="!model.canWrite()">{{ state.busy ? "提交中…" : state.editingId === null ? "新增文章" : "保存编辑" }}</button>
        <button v-if="state.editingId !== null" class="text-button" type="button" @click="model.reset()">取消编辑</button>
      </div>
      <p v-if="state.feedback" role="status">{{ state.feedback }}</p>
      <p v-if="state.error" role="alert">{{ state.error }}</p>
      <div v-if="state.uncertain" class="notice warning"><p>服务端可能已完成操作。刷新并核对文章后再继续；当前草稿不会自动重发。</p><button class="text-button" type="button" :disabled="!state.fresh || state.busy" @click="model.acknowledge()">已核对结果，允许后续操作</button></div>
    </form>
    <div v-if="state.loading" class="article-empty">正在加载文章…</div>
    <div v-else-if="!state.fresh" class="article-empty">文章状态未确认，请刷新后操作。</div>
    <div v-else-if="state.articles.length === 0" class="article-empty">REST 服务已连接，当前暂无文章。</div>
    <ul v-else class="article-list">
      <li v-for="article in state.articles" :key="article.id">
        <div><strong>{{ article.title }}</strong><span>{{ article.status }}</span></div>
        <div class="article-row-actions">
          <button class="text-button" type="button" @click="model.beginEdit(article)">编辑</button>
          <button class="text-button danger" type="button" :disabled="!model.canWrite()" @click="model.remove(article)">删除</button>
        </div>
      </li>
    </ul>
  </section>
</template>
