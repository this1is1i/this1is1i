<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { createWorkbench } from "./workbench";
import ModelReceipts from "./ModelReceipts.vue";

const model = createWorkbench();
const state = model.state;
const token = ref("");
const reconciliationReason = ref("");
const selectedTasks = ref<string[]>([]);
const profileId = ref("");
const evidence = ref<Record<string, string>>({});
const stopEvidence = ref<Record<string, string>>({});
const assignees = ref<Record<string, string>>({});
const executorChoices = ref<Record<string, string>>({});
const gates = ["CODE_REVIEW", "UNIT_TEST", "INTEGRATION_TEST", "BUSINESS_ACCEPTANCE"];
const gateLabels: Record<string, string> = { CODE_REVIEW: "代码评审", UNIT_TEST: "单元测试", INTEGRATION_TEST: "集成测试", BUSINESS_ACCEPTANCE: "业务验收" };
const labels: Record<string, string> = { READY: "就绪", PREPARING: "准备中", ASSEMBLED: "待发布", CONFLICTED: "有冲突", STALE: "已过期", PASSED: "通过", FAILED: "失败", RUNNING: "执行中 / 待对账", SUBMITTED: "已提交", CODE_REVIEW: "评审中", TESTING: "测试中", ACCEPTED: "已验收", REJECTED: "已拒绝", CLAIMED: "已领取", BLOCKED: "已阻塞", CANCELLED: "已取消" };
const status = (value: string) => labels[value] ?? value;
const metrics = computed(() => [
  [state.snapshot?.tasks.length ?? 0, "工作包"],
  [state.snapshot?.tasks.filter(task => ["CLAIMED", "RUNNING"].includes(task.status)).length ?? 0, "执行中"],
  [state.snapshot?.activeLeases.length ?? 0, "有效租约"],
  [state.candidateIds.length, "集成候选"]
]);
const eligible = computed(() => state.snapshot?.tasks.filter(task => task.currentlyValid && task.execution?.commitSha
  && ["SUBMITTED", "CODE_REVIEW", "TESTING", "ACCEPTED"].includes(task.status)) ?? []);
const closure = computed(() => {
  if (!selectedTasks.value.length) return "尚未选择任务";
  try { return model.dependencyClosure(selectedTasks.value).join(" → "); } catch (error) { return (error as Error).message; }
});
async function login() {
  const value = token.value; token.value = ""; selectedTasks.value = []; evidence.value = {}; stopEvidence.value = {}; assignees.value = {}; executorChoices.value = {}; profileId.value = "";
  await model.login(value);
  profileId.value = state.snapshot?.profiles[0]?.id ?? "";
}
function logout() { token.value = ""; selectedTasks.value = []; evidence.value = {}; stopEvidence.value = {}; assignees.value = {}; executorChoices.value = {}; model.logout(); }
onBeforeUnmount(() => model.logout());
</script>

<template>
  <section class="integration-workspace" aria-label="集成工作台">
    <div class="panel">
      <div class="panel-heading">
        <div><span class="eyebrow">真实执行 / 人工治理</span><h3>集成工作台</h3></div>
        <div v-if="state.snapshot" class="inline-actions">
          <button type="button" class="action-button secondary" :disabled="state.loading" @click="model.refresh()">{{ state.loading ? "刷新中…" : "刷新状态" }}</button>
          <button type="button" class="action-button secondary" @click="logout">退出</button>
        </div>
      </div>
      <div class="panel-body">
        <p class="muted">角色视图不授予操作权限。实际操作以当前项目凭据为准，最终合并由人工完成。</p>
        <form v-if="!state.snapshot" class="connection-form" @submit.prevent="login">
          <label for="project-token">项目访问凭据<input id="project-token" v-model="token" type="password" autocomplete="off" spellcheck="false" required placeholder="粘贴本项目的访问令牌" /></label>
          <button class="action-button" type="submit" :disabled="state.loading">{{ state.loading ? "连接中…" : "连接项目" }}</button>
          <p class="muted">凭据仅在当前页面内存中使用；刷新页面后需要重新连接。</p>
        </form>
        <template v-else>
          <p class="identity-line"><strong>{{ state.snapshot.subjectId }}</strong> · {{ state.snapshot.projectId }} · {{ state.snapshot.roles.join(" / ") }} <span>上下文 v{{ state.snapshot.contextVersion }}</span></p>
          <p v-if="!state.fresh" class="notice warning">当前数据尚未核实，写操作已暂停。请刷新状态。</p>
          <p v-if="state.snapshot.reconciliationRequired" class="notice warning">操作日志需要服务端对账，写操作已暂停。</p>
        </template>
        <p v-if="state.error" role="alert" class="notice error">{{ state.error }}</p>
        <p v-if="state.notice" role="status" class="notice">{{ state.notice }}</p>
        <p v-if="state.busy" role="status" class="notice">正在执行操作，可刷新查看进度。退出页面不会取消服务端执行。</p>
        <div v-if="state.pending && !state.busy" class="notice warning">
          <p>上次{{ state.pending.label }}的结果尚未确认。刷新可查看服务端状态，重试会复用原请求。结束本地重试不会取消服务端执行，核对前请勿发起重复操作。</p>
          <button type="button" class="action-button secondary" @click="model.retry()">使用原请求重试</button>
          <button type="button" class="action-button secondary" :disabled="!state.fresh" @click="model.acknowledgePending()">已核对状态，结束本地重试</button>
        </div>
      </div>
    </div>

    <template v-if="state.snapshot">
      <section class="metric-grid" aria-label="真实治理指标"><article v-for="metric in metrics" :key="String(metric[1])" class="metric-card"><span>{{ metric[1] }}</span><strong>{{ metric[0] }}</strong><small>{{ state.fresh ? "服务端当前状态" : "未刷新快照" }}</small></article></section>

      <div class="integration-grid">
        <section class="panel">
          <div class="panel-heading"><div><span class="eyebrow">提交组合</span><h3>创建集成候选</h3></div></div>
          <form class="panel-body candidate-form" @submit.prevent="model.createCandidate(selectedTasks, profileId)">
            <p class="muted">选择已提交任务，系统会补齐完整依赖。创建操作需要独立工程负责人。</p>
            <fieldset :disabled="!model.writable() || !model.hasRole('ENGINEERING_LEAD')"><legend>任务集合</legend>
              <p v-if="!eligible.length" class="empty-state">暂无已有效提交的任务。执行者提交代码后会出现在这里。</p>
              <label v-for="task in eligible" :key="task.id" class="check-label"><input v-model="selectedTasks" type="checkbox" :value="task.id" :disabled="task.owner === state.snapshot.subjectId" /> <strong>{{ task.id }}</strong> <span>{{ status(task.status) }} · {{ task.owner }}</span></label>
            </fieldset>
            <p class="muted">完整依赖集合：{{ closure }}</p>
            <label for="validation-profile">验证方案<select id="validation-profile" v-model="profileId"><option value="" disabled>选择已配置方案</option><option v-for="profile in state.snapshot.profiles" :key="profile.id" :value="profile.id">{{ profile.id }} · v{{ profile.version }} · {{ profile.checks.length }} 项检查</option></select></label>
            <p v-if="!state.snapshot.profiles.length" class="notice warning">本项目尚未配置本机验证方案，请由项目维护者配置后刷新。</p>
            <button class="action-button" type="submit" :disabled="!model.writable() || !model.hasRole('ENGINEERING_LEAD') || !selectedTasks.length || !profileId">生成候选</button>
          </form>
        </section>
        <section class="panel">
          <div class="panel-heading"><div><span class="eyebrow">历史与当前状态</span><h3>集成候选</h3></div></div>
          <div class="panel-body">
            <p v-if="!state.candidateIds.length" class="empty-state">还没有集成候选。创建后可查看冲突、验证与合并建议。</p>
            <ul v-else class="candidate-list"><li v-for="id in state.candidateIds" :key="id"><button type="button" class="candidate-link" :aria-pressed="state.selectedId === id" @click="model.select(id)"><span>候选 {{ id.slice(0, 8) }}</span><code>{{ id }}</code></button></li></ul>
          </div>
        </section>
      </div>

      <section v-if="state.selectedId" class="panel candidate-detail" aria-label="候选详情" :aria-busy="state.detailLoading">
        <div class="panel-heading"><div><span class="eyebrow">冲突与证据</span><h3>候选 {{ state.selectedId.slice(0, 8) }}</h3></div><span v-if="state.detail" class="status-pill" :class="{ blocked: !state.detail.currentlyValid || state.detail.effectiveState === 'CONFLICTED' }">{{ status(state.detail.effectiveState) }}</span></div>
        <div class="panel-body">
          <p v-if="state.detailLoading" role="status">正在核对候选及输入提交…</p>
          <template v-if="state.detail">
            <p v-if="!state.detail.currentlyValid" class="notice warning">旧证据已失效：{{ state.detail.invalidReason }}</p>
            <p v-if="!state.detailFresh" class="notice warning">候选状态未确认，操作已禁用。</p>
            <dl class="evidence-grid"><dt>目标分支 / 基线</dt><dd>{{ state.detail.candidate.plan.targetRef }}<code>{{ state.detail.candidate.plan.targetCommit }}</code></dd><dt>候选提交</dt><dd><code>{{ state.detail.candidate.assembly?.commit ?? "尚未生成可验证提交" }}</code></dd><dt>组合指纹</dt><dd><code>{{ state.detail.candidate.plan.fingerprint }}</code></dd><dt>验证方案</dt><dd>{{ state.detail.candidate.plan.profile.id }} · v{{ state.detail.candidate.plan.profile.version }}</dd></dl>
            <ul class="source-list"><li v-for="input in state.detail.candidate.plan.inputs" :key="input.taskId"><strong>{{ input.taskId }}</strong> · {{ input.owner }}<code>{{ input.execution.commitSha }}</code></li></ul>
            <div v-if="state.detail.candidate.plan.staticReport.conflicts.length" class="notice warning"><h4>静态检查</h4><p v-for="(conflict, index) in state.detail.candidate.plan.staticReport.conflicts" :key="index">{{ conflict.kind }} · {{ conflict.tasks.join('、') }} — {{ conflict.evidence }}</p></div>
            <div v-if="state.detail.candidate.assembly?.conflictingTask" class="notice error"><h4>合并冲突：{{ state.detail.candidate.assembly.conflictingTask }}</h4><ul><li v-for="path in state.detail.candidate.assembly.conflictingPaths" :key="path"><code>{{ path }}</code></li></ul><p>由任务负责人修正并重新提交，再生成新候选。旧候选保留供审计。</p></div>
            <details v-if="state.detail.candidate.assembly?.evidence"><summary>合并原始证据</summary><pre>{{ state.detail.candidate.assembly.evidence }}</pre></details>
            <div class="validation-summary"><h4>最新组合验证：{{ state.detail.validation ? status(state.detail.validation.state) : "尚未执行" }}</h4>
              <p v-if="state.detail.validation?.state === 'RUNNING'" class="notice warning">记录尚未完成。刷新确认进度；服务重启后需维护者检查该次执行，不能自动重跑。</p>
              <div v-if="state.detail.validation?.state === 'RUNNING'"><label>对账说明<input v-model="reconciliationReason" maxlength="2000" placeholder="确认进程已停止，记录工作目录核查结果"></label><button class="text-button" type="button" :disabled="!model.canReconcile()||!reconciliationReason.trim()" @click="model.reconcile(reconciliationReason)">记录验证已中断</button><p class="muted">保留原始记录；该操作不会将验证标为通过。</p></div>
              <p v-if="state.detail.validation?.detail" class="notice warning">{{ state.detail.validation.detail }}</p>
              <details v-for="check in state.detail.validation?.checks ?? []" :key="check.checkId"><summary>{{ check.checkId }} · 退出码 {{ check.exitCode }} {{ check.timedOut ? '· 超时' : '' }} {{ check.truncated ? '· 日志超限' : '' }}</summary><code>{{ check.argv.join(' ') }}</code><pre>{{ check.stdout || "无标准输出" }}</pre><pre v-if="check.stderr">{{ check.stderr }}</pre></details>
            </div>
            <p class="muted">运行验证需要独立 QA 负责人；生成合并建议还要求所有任务完成人工验收。本机方案仅执行项目配置的可信代码。</p>
            <div class="inline-actions"><button type="button" class="action-button" :disabled="!model.canValidate()" @click="model.validate()">运行组合验证</button><button type="button" class="action-button secondary" :disabled="!model.canPropose()" @click="model.propose()">生成人工合并建议</button></div>
            <div v-if="state.proposal" class="notice"><h4>人工合并建议已生成</h4><p>建议 ID：{{ state.proposal.id }}。请在人工合并前再次刷新核对目标与候选，平台不会执行合并。</p><dl class="evidence-grid"><dt>候选分支</dt><dd><code>{{ state.proposal.branch }}</code></dd><dt>提交</dt><dd><code>{{ state.proposal.commitSha }}</code></dd><dt>验证 ID</dt><dd><code>{{ state.proposal.validationId }}</code></dd></dl></div>
          </template>
        </div>
      </section>

      <section class="panel" aria-label="任务审批">
        <div class="panel-heading"><div><span class="eyebrow">分配 / 领取 / 独立审批</span><h3>工作包与质量门禁</h3></div></div>
        <div class="panel-body task-cards"><article v-for="task in state.snapshot.tasks" :key="task.id" class="task-card">
          <div class="task-title"><h4>{{ task.id }} <small>{{ task.owner }}</small></h4><span class="status-pill" :class="{ blocked: !task.currentlyValid || ['REJECTED', 'BLOCKED'].includes(task.status) }">{{ status(task.status) }}{{ task.currentlyValid ? '' : ' · 已失效' }}</span></div>
          <p class="muted">依赖：{{ task.dependencies.join('、') || '无' }} · API v{{ task.contractVersion }} / Schema v{{ task.schemaVersion }}</p><code v-if="task.execution?.commitSha">{{ task.execution.commitSha }}</code>
          <p v-for="blocker in task.capacityBlockers ?? []" :key="blocker.kind + ':' + blocker.resourceId" class="muted">
            {{ blocker.kind === 'ACTOR' ? '执行者' : '环境' }} {{ blocker.resourceId }}：已占用 {{ blocker.used }} / {{ blocker.capacity }} 槽位{{ blocker.capacity === 0 ? '，请在计划中配置容量。' : '，等待执行结束后可领取。' }}
          </p>
          <div v-if="model.hasRole('PROJECT_MANAGER') && task.status === 'READY' && !task.execution" class="dispatch-form">
            <label :for="'assignee-' + task.id">任务负责人<select :id="'assignee-' + task.id" v-model="assignees[task.id]" :disabled="!model.canAssign(task)"><option value="" disabled>选择项目成员</option><option v-for="member in state.snapshot.assignees ?? []" :key="member.subjectId" :value="member.subjectId">{{ member.subjectId }} · {{ member.roles.join(' / ') }}</option></select></label>
            <button class="action-button secondary" type="button" :disabled="!model.canAssign(task) || !assignees[task.id]" @click="model.assign(task.id, assignees[task.id])">分配任务</button>
          </div>
          <div v-if="task.owner === state.snapshot.subjectId && task.status === 'READY'" class="dispatch-form">
            <button class="action-button" type="button" :disabled="!model.canClaim(task)" @click="model.claim(task.id)">领取并创建工作树</button>
            <p class="muted">{{ task.claimable ? '领取后生成本次执行专属分支和目录。' : '当前不可领取，请核对容量、上游验收与语义版本。' }}</p>
          </div>
          <details v-if="task.execution" class="execution-details"><summary>执行信息与 MCP 接续</summary>
            <dl class="evidence-grid"><dt>执行 ID</dt><dd><code>{{ task.execution.executionId }}</code></dd><dt>任务分支</dt><dd><code>{{ task.execution.branch }}</code></dd><dt>工作树目录</dt><dd><code>{{ task.execution.path }}</code></dd><dt>基线 SHA</dt><dd><code>{{ task.execution.baseCommit }}</code></dd><dt>工作目录</dt><dd><code>{{ task.workingDirectory }}</code></dd><dt>允许路径</dt><dd><code v-for="path in task.allowedPaths" :key="path">{{ path }}</code></dd><dt>禁止路径</dt><dd><code v-for="path in task.forbiddenPaths" :key="path">{{ path }}</code></dd><dt>任务验收命令</dt><dd><code v-for="command in task.acceptanceCommands" :key="command">{{ command }}</code></dd></dl>
            <p class="muted">在此工作树修改代码；通过 MCP 申请与维护路径租约，再提交实际 SHA。任务凭据由项目经理通过治理 API 带外分发，本页不展示或保存任务令牌。领取不代表已经取得写入租约。</p>
          </details>
          <div v-if="model.canDispatch(task)" class="dispatch-form">
            <label :for="'executor-' + task.id">任务执行器<select :id="'executor-' + task.id" v-model="executorChoices[task.id]"><option value="" disabled>选择已配置执行器</option><option v-for="executor in state.snapshot.executors ?? []" :key="executor.id" :value="executor.id">{{ executor.id }} · {{ executor.kind }}</option></select></label>
            <button class="action-button" type="button" :disabled="!executorChoices[task.id]" @click="model.dispatchExecutor(task.id, executorChoices[task.id])">启动执行</button>
          </div>
          <div v-if="task.executor" class="notice">
            <p>执行器 {{ task.executor.request.executorId }} · {{ task.executor.state === 'UNKNOWN' ? '结果未知，保留占用并查询原执行' : task.executor.state === 'SUCCEEDED' ? '已提交，等待人工门禁' : task.executor.state === 'FAILED' ? '执行失败，可检查工作树后返工' : task.executor.state === 'STOPPED' ? '已确认停止' : '执行中' }}</p>
            <p class="muted">最近核对：{{ task.executor.observedAt }} · 运行截止：{{ task.executor.request.deadline }}</p>
            <p v-for="check in task.executor.result?.checks ?? []" :key="check.id" class="muted">{{ check.id }}：退出码 {{ check.exitCode }}{{ check.timedOut ? ' · 超时' : '' }}{{ check.truncated ? ' · 输出超过限制' : '' }}</p>
            <ModelReceipts :runs="task.executor.result?.modelRuns" :evidence-ref="task.executor.modelEvidenceRef" />
            <button v-if="task.owner === state.snapshot.subjectId" class="action-button secondary" type="button" :disabled="!model.writable()" @click="model.refreshExecutor(task.id)">查询原执行状态</button>
          </div>
          <p class="gate-summary"><span v-for="gate in gates" :key="gate">{{ task.gates[gate] ? '✓' : '○' }} {{ gateLabels[gate] }}</span></p>
          <div v-if="model.canTask(task, 'confirm_execution_stopped')" class="gate-form">
            <p class="muted">此执行仍占用容量。核对进程已退出或远端任务已终止后，填写证据并释放；此操作本身不会停止进程。</p>
            <label :for="'stop-evidence-' + task.id">停止核对证据<input :id="'stop-evidence-' + task.id" v-model="stopEvidence[task.execution!.executionId]" placeholder="进程退出记录或远端终止记录" /></label>
            <button class="action-button secondary" type="button" :disabled="!stopEvidence[task.execution!.executionId]?.trim()" @click="model.taskCommand(task.id, 'confirm_execution_stopped', undefined, undefined, stopEvidence[task.execution!.executionId])">确认已停止并释放容量</button>
          </div>
          <div class="inline-actions"><button v-for="action in [{ id: 'start_review', label: '开始评审' }, { id: 'start_testing', label: '进入测试' }, { id: 'accept_task', label: '验收任务' }, { id: 'retry_task', label: '允许新执行' }, { id: 'revalidate_task', label: '重新绑定语义版本' }].filter(action => model.canTask(task, action.id))" :key="action.id" class="action-button secondary" type="button" @click="model.taskCommand(task.id, action.id)">{{ action.label }}</button></div>
          <details v-if="Object.keys(task.gateEvidence ?? {}).length"><summary>门禁证据 ID</summary><dl class="evidence-grid"><template v-for="(id, gate) in task.gateEvidence" :key="gate"><dt>{{ gateLabels[gate] ?? gate }}</dt><dd><code>{{ id }}</code></dd></template></dl></details>
          <div v-if="gates.some(gate => model.canTask(task, 'record_gate', gate))" class="gate-form">
            <label v-if="!state.snapshot.evidenceRequired || ['CODE_REVIEW', 'BUSINESS_ACCEPTANCE'].some(gate => model.canTask(task, 'record_gate', gate))" :for="'evidence-' + task.id">{{ state.snapshot.evidenceRequired ? '评审／业务验收说明' : '证据引用' }}<input :id="'evidence-' + task.id" v-model="evidence[task.id]" :maxlength="state.snapshot.evidenceRequired ? 16000 : undefined" :placeholder="state.snapshot.evidenceRequired ? '说明本人核对的内容及结论' : '评审记录或测试报告引用'" /></label>
            <p v-if="state.snapshot.evidenceRequired && ['UNIT_TEST', 'INTEGRATION_TEST'].some(gate => model.canTask(task, 'record_gate', gate))">测试门禁使用上方所选候选的最新平台验证。请先检查验证命令及结果是否覆盖本项测试要求。当前验证：<code>{{ state.detailFresh ? state.detail?.validation?.id ?? '尚未验证' : '尚未选择有效候选' }}</code></p>
            <div v-for="gate in gates.filter(gate => model.canTask(task, 'record_gate', gate))" :key="gate" class="inline-actions"><strong>{{ gateLabels[gate] }}</strong><button class="action-button secondary" type="button" :disabled="!model.canRecordGate(task, gate, true, evidence[task.id])" @click="model.taskCommand(task.id, 'record_gate', gate, true, evidence[task.id])">{{ task.status === 'ACCEPTED' ? '更新通过证据' : '通过' }}</button><button v-if="task.status !== 'ACCEPTED'" class="action-button danger" type="button" :disabled="!model.canRecordGate(task, gate, false, evidence[task.id])" @click="model.taskCommand(task.id, 'record_gate', gate, false, evidence[task.id])">拒绝并返工</button></div>
          </div>
        </article></div>
      </section>
      <div class="integration-grid">
        <section class="panel"><div class="panel-heading"><h3>有效路径租约</h3></div><div class="panel-body"><p v-if="!state.snapshot.activeLeases.length" class="empty-state">当前没有有效租约。</p><ul class="source-list"><li v-for="lease in state.snapshot.activeLeases" :key="lease.leaseId"><code>{{ lease.resourcePath }}</code><small>{{ lease.taskExecutionId }} · 到期 {{ lease.expiresAt }}</small></li></ul></div></section>
        <section class="panel"><div class="panel-heading"><h3>真实变更事件</h3></div><div class="panel-body"><p v-if="!state.snapshot.events.length" class="empty-state">当前没有变更事件。</p><ol class="source-list"><li v-for="(event, index) in state.snapshot.events.slice(-20).reverse()" :key="index"><strong>{{ event.type }}</strong> · {{ event.subject }}<small>{{ event.occurredAt }}</small><details><summary>事件详情</summary><pre>{{ JSON.stringify(event.details, null, 2) }}</pre></details></li></ol></div></section>
      </div>
    </template>
  </section>
</template>
