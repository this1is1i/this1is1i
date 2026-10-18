<script setup lang="ts">
import ArticleWorkspace from "./article/ArticleWorkspace.vue";
</script>
<template>
  <main class="sample-shell">
    <header><p>独立样例应用</p><h1>文章管理</h1>
      <p>这是文章切片脚手架，使用独立业务数据库。旧权限、富文本和路由的完整对照在业务验收阶段确认。</p></header>
    <ArticleWorkspace />
  </main>
</template>
<style scoped>.sample-shell{max-width:1100px;margin:40px auto;padding:0 24px}header{margin-bottom:24px}header p{color:#697487}</style>
