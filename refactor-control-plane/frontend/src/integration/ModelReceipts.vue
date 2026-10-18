<script setup lang="ts">
import type { ModelRun } from "./workbench";
defineProps<{ runs?: ModelRun[] | null; evidenceRef?: string | null }>();
</script>

<template>
  <div class="muted" aria-label="模型调用记录">
    <p v-if="runs == null">执行服务未报告模型调用。</p>
    <p v-else-if="!runs.length">执行服务报告未使用模型。</p>
    <template v-else>
      <p>来源：执行服务声明。测试结论以平台验证为准。</p>
      <p v-if="evidenceRef">模型证据：<code>{{ evidenceRef }}</code></p>
      <details v-for="run in runs" :key="run.id">
        <summary>{{ run.model }} · {{ run.promptVersion }} · {{ run.totalTokens }} tokens</summary>
        <p>请求工具：{{ run.requestedTools.join(', ') || '未声明' }}</p>
        <p>实际调用记录：{{ run.toolCalls.length }}</p>
        <p v-for="call in run.toolCalls" :key="call.id">{{ call.name }} · {{ call.state }}</p>
        <p>完整输入摘要：<code>{{ run.inputHash }}</code></p>
        <p>输出摘要：<code>{{ run.outputHash }}</code></p>
      </details>
    </template>
  </div>
</template>
