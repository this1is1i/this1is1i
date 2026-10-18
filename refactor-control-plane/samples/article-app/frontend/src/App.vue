<script setup lang="ts">
import ArticleWorkspace from "./article/ArticleWorkspace.vue";
</script>
<template>
  <main class="sample-shell">
    <header><p>独立样例应用</p><h1>文章管理</h1>
      <p>独立业务数据库；按博客归属管理文章，保留稳定地址与原始正文。</p></header>
    <ArticleWorkspace />
  </main>
</template>
<style scoped>.sample-shell{max-width:1100px;margin:40px auto;padding:0 24px}header{margin-bottom:24px}header p{color:#697487}</style>
