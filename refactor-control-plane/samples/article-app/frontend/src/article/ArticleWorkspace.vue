<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { createArticleEditor } from "./editor";
const model = createArticleEditor();
const state = model.state;
const username = ref(""), password = ref("");
async function login() { const pending = model.login(username.value, password.value); password.value = ""; await pending; }
onMounted(async () => {
  await model.load();
  if (window.location.pathname !== "/") await model.openPublication(window.location.pathname);
});
onBeforeUnmount(model.dispose);
</script>

<template>
  <section class="panel article-workspace">
    <div class="panel-heading">
      <div><span class="eyebrow">验收切片 / Vue 3 + Spring Boot</span><h3>文章管理迁移切片</h3></div>
      <button class="text-button" type="button" :disabled="state.loading || state.busy" @click="model.load()">{{ state.loading ? "刷新中…" : "刷新" }}</button>
    </div>
    <p class="article-contract">公开阅读：<code>/api/articles</code>；登录后可新增文章、编辑与软删除，仅管理所属博客。</p>
    <form v-if="!state.subject" class="article-editor" @submit.prevent="login()">
      <label>账号 <input v-model="username" name="username" autocomplete="username" required></label>
      <label>密码 <input v-model="password" name="password" type="password" autocomplete="current-password" required></label>
      <button class="text-button" type="submit" :disabled="!state.sessionReady || state.loading || state.busy">登录</button>
    </form>
    <div v-else class="article-row-actions"><span>当前账号：{{ state.subject }}</span>
      <button class="text-button" type="button" :disabled="state.busy || state.loading" @click="model.logout()">退出登录</button>
    </div>
    <p v-if="state.feedback" role="status">{{ state.feedback }}</p>
    <p v-if="state.error" role="alert">{{ state.error }}</p>
    <template v-if="state.subject">
      <p v-if="state.fresh && state.menus.length === 0" class="notice">当前账号尚未配置博客菜单，请完成归属配置后再新增文章。</p>
      <form class="article-editor" @submit.prevent="model.save()">
        <label>所属菜单
          <select v-model="state.editor.menuId" :disabled="state.editingId !== null" required>
            <option v-for="menu in state.menus" :key="menu.id" :value="menu.id">{{ menu.blogUrl }} / {{ menu.menuName }}</option>
          </select>
        </label>
        <label>标题（最多 255 字符）<input v-model="state.editor.title" name="title" maxlength="510" required></label>
        <label>正文（保留原 HTML，公开展示时清洗）<textarea v-model="state.editor.content" name="content" rows="6" required></textarea></label>
        <label>状态 <select v-model="state.editor.status" name="status">
          <option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option><option value="ARCHIVED">已归档</option>
        </select></label>
        <div class="article-editor-actions">
          <button class="text-button" type="submit" :disabled="!model.canWrite()">{{ state.busy ? "提交中…" : state.editingId === null ? "新增文章" : "保存编辑" }}</button>
          <button v-if="state.editingId !== null" class="text-button" type="button" @click="model.reset()">取消编辑</button>
        </div>
        <div v-if="state.uncertain" class="notice warning"><p>服务端可能已完成操作。刷新并核对文章后再继续；当前草稿不会自动重发。</p>
          <button class="text-button" type="button" :disabled="!state.fresh || state.busy" @click="model.acknowledge()">已核对结果，允许后续操作</button>
        </div>
      </form>
      <p>软删除会隐藏文章并保留正文、归属和原地址。</p>
      <ul class="article-list">
        <li v-for="article in state.articles" :key="article.id">
          <div><strong>{{ article.title }}</strong><span>{{ article.status }} · 版本 {{ article.version }}</span><small>{{ article.publicPath }}</small></div>
          <div class="article-row-actions">
            <button class="text-button" type="button" @click="model.beginEdit(article)">编辑</button>
            <button class="text-button danger" type="button" :disabled="!model.canWrite()" @click="model.remove(article)">软删除</button>
          </div>
        </li>
      </ul>
    </template>
    <h4>已发布文章</h4>
    <p v-if="state.loading">正在加载…</p><p v-else-if="state.fresh && state.publications.length === 0">暂无已发布文章。</p>
    <ul class="article-list"><li v-for="article in state.publications" :key="article.id">
      <a :href="article.publicPath">{{ article.title }}</a>
      <button class="text-button" type="button" @click="model.openPublication(article.publicPath)">阅读</button>
    </li></ul>
    <article v-if="state.publication" class="article-publication">
      <h2>{{ state.publication.title }}</h2>
      <!-- Only the public endpoint's server-sanitized html is rendered. Raw editor content is never bound to v-html. -->
      <div v-html="state.publication.html"></div>
    </article>
  </section>
</template>
