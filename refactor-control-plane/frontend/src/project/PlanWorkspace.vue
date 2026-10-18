<script setup lang="ts">
import { onBeforeUnmount,ref } from "vue";
import {createPlanWorkspace} from "./plan";
const model=createPlanWorkspace(),state=model.state;
const token=ref(""),draft=ref("");
async function login(){const value=token.value;token.value="";draft.value="";await model.login(value);}
async function importFile(event:Event){const file=(event.target as HTMLInputElement).files?.[0];if(!file)return;
  if(file.size>262144){state.error="计划文件不能超过 256 KiB。";return;}draft.value=await file.text();}
onBeforeUnmount(model.logout);
</script>
<template>
  <section class="panel plan-workspace" aria-label="项目计划">
    <div class="panel-heading"><div><span class="eyebrow">目标仓库 / 证据与审批</span><h3>项目计划</h3></div>
      <button v-if="state.history" class="text-button" :disabled="state.loading||state.busy" @click="model.refresh">刷新计划</button></div>
    <div class="panel-body">
      <p class="muted">架构师确认目标和 ADR，项目经理确认执行计划。切换新版本后需要重启平台；主分支由人工合并。</p>
      <form class="connection-form" @submit.prevent="login"><label>计划访问凭据<input v-model="token" type="password" autocomplete="off" required placeholder="连接或切换审批身份"></label><button class="action-button" :disabled="state.busy">连接计划</button></form>
      <p v-if="state.error" class="notice warning" role="alert">{{state.error}}</p>
      <p v-if="state.notice" class="notice" role="status">{{state.notice}}</p>
      <p v-if="model.restartRequired()" class="notice warning">新版本等待服务重启。当前运行时已停止接受写入。</p>
      <div v-if="state.pending" class="notice warning"><p>待核对请求：{{state.pending.path}}</p><button class="text-button" :disabled="state.busy" @click="model.retry">重试原请求</button><details><summary>查看原请求</summary><pre>{{JSON.stringify(state.pending.body,null,2)}}</pre></details></div>
      <template v-if="state.history">
        <p>当前身份：<strong>{{state.history.subjectId}}</strong> · {{state.history.roles.join(' / ')}}</p>
        <label>计划版本<select :value="state.selected" :disabled="state.busy" @change="model.select(($event.target as HTMLSelectElement).value)"><option v-for="revision in state.history.revisions" :key="revision.fingerprint" :value="revision.fingerprint">{{revision.fingerprint.slice(0,12)}} · {{revision.fingerprint===state.history.activeFingerprint?'当前版本':revision.activated?'历史版本':'待审批'}}</option></select></label>
      </template>
      <template v-if="state.detail">
        <div class="plan-summary"><p>仓库：<code>{{state.detail.revision.definition.repository}}</code></p><p>基线：<code>{{state.detail.revision.definition.plan.baseCommit}}</code></p><p>目标分支：<code>{{state.detail.revision.definition.targetRef}}</code></p><p>架构确认：{{state.detail.revision.architect??'待确认'}} · 执行确认：{{state.detail.revision.manager??'待确认'}}</p></div>
        <div class="plan-actions"><button class="action-button" :disabled="!model.canDecide('ARCHITECTURE')" @click="model.decide('ARCHITECTURE')">确认架构与 ADR</button><button class="action-button" :disabled="!model.canDecide('EXECUTION')" @click="model.decide('EXECUTION')">批准执行计划</button><button class="text-button" :disabled="!model.canActivate()" @click="model.activate">激活新版本</button></div>
        <div class="plan-table"><table><caption>工作包与验收命令</caption><thead><tr><th>任务</th><th>依赖 / 路径</th><th>工作目录 / 命令</th></tr></thead><tbody><tr v-for="task in state.detail.revision.definition.plan.workPackages" :key="task.id"><td>{{task.id}} · {{task.title}}<br>{{task.role}}</td><td>{{task.dependencies.join(', ')||'无'}}<br>{{task.allowedPaths.join(', ')}}</td><td><code>{{task.workingDirectory}}</code><pre>{{task.acceptanceCommands.join('\n')}}</pre></td></tr></tbody></table></div>
        <details><summary>目标架构、兼容要求与 ADR</summary><pre>{{state.detail.revision.definition.plan.targetArchitectureMermaid}}</pre><p>API：{{state.detail.revision.definition.plan.apiContract}}</p><p>数据库：{{state.detail.revision.definition.plan.databaseCompatibility}}</p><ul><li v-for="decision in state.detail.revision.definition.architectureDecisions??[]" :key="decision.id"><strong>{{decision.id}}</strong> {{decision.statement}}<p>关联任务：{{decision.taskIds.join(', ')}}</p><p v-for="ref in decision.evidence" :key="ref.path"><code>{{ref.path}} @ {{ref.blobId}}</code></p></li></ul></details>
        <details><summary>当前架构关系与源码证据（{{state.detail.analysis.relations.length}}）</summary><div class="plan-table"><table><thead><tr><th>关系</th><th>依据</th></tr></thead><tbody><tr v-for="(edge,index) in state.detail.analysis.relations" :key="index"><td>{{edge.from}} → {{edge.to}}<br>{{edge.kind}} · {{edge.confidence}}</td><td>{{edge.explanation}}<p v-for="item in state.detail.analysis.evidence.filter(e=>edge.evidenceIds.includes(e.id))" :key="item.id"><code>{{item.path}} @ {{item.blobId}}</code></p></td></tr></tbody></table></div><ul><li v-for="(finding,index) in state.detail.analysis.findings.filter(f=>f.kind==='UNKNOWN')" :key="index">{{finding.statement}}</li></ul></details>
        <details><summary>导入计划修订</summary><p>从当前计划复制并修改，或读取本地 JSON 文件。导入后仍需两名独立主体重新审批。</p><button class="text-button" type="button" @click="draft=JSON.stringify(state.detail.revision.definition,null,2)">复制所选版本</button><input aria-label="读取计划 JSON" type="file" accept=".json,application/json" @change="importFile"><label>修订内容<textarea v-model="draft" rows="16" spellcheck="false" aria-label="修订 JSON"></textarea></label><button class="action-button" :disabled="!model.canStage()||!draft.trim()" @click="model.stage(draft)">校验并提交修订</button></details>
      </template>
    </div>
  </section>
</template>
<style scoped>
.plan-workspace{margin-bottom:24px}.plan-summary{overflow-wrap:anywhere;margin:20px 0}.plan-actions{display:flex;gap:12px;flex-wrap:wrap}.plan-table{overflow:auto;margin:24px 0}table{border-collapse:collapse;width:100%;font-size:13px}caption{text-align:left;font-weight:600;padding:12px 0}th,td{text-align:left;border-bottom:1px solid #e3e8ed;padding:12px;vertical-align:top}pre,code{font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere}details{margin-top:20px;padding:16px;background:#f6f8fa;border-radius:10px}summary{cursor:pointer;font-weight:600}textarea{display:block;width:100%;font-family:monospace;box-sizing:border-box;margin:12px 0}select{padding:8px;margin-left:8px;max-width:100%}label{display:block}
</style>
