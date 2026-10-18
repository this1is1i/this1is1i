import { reactive } from "vue";

export interface Execution { executionId: string; branch: string; path: string; baseCommit: string; commitSha: string | null }
export interface Task {
  id: string; status: string; currentlyValid: boolean; owner: string; dependencies: string[];
  gates: Record<string, boolean>; gateEvidence?: Record<string, string>; execution?: Execution; contextVersion: number; contractVersion: number; schemaVersion: number;
  claimable?: boolean; allowedPaths?: string[]; forbiddenPaths?: string[]; workingDirectory?: string; acceptanceCommands?: string[];
  capacityHeld?: boolean;
  executor?: { state: string; observedAt: string; request: { executorId: string; deadline: string }; result?: { sequence: number; commitSha: string | null; checks: { id: string; exitCode: number; timedOut: boolean; truncated: boolean }[] } | null };
  capacityBlockers?: { kind: "ACTOR" | "ENVIRONMENT"; resourceId: string; capacity: number; used: number }[];
}
export interface Snapshot {
  projectId: string; subjectId: string; roles: string[]; contextVersion: number; reconciliationRequired: boolean;
  tasks: Task[]; profiles: { id: string; version: string; checks: string[] }[];
  assignees?: { subjectId: string; roles: string[] }[];
  executors?: { id: string; kind: string }[];
  evidenceRequired?: boolean;
  activeLeases: { leaseId: string; resourcePath: string; taskExecutionId: string; expiresAt: string }[];
  events: { sequence: number; type: string; subject: string; occurredAt: string; details: Record<string, string> }[];
}
export interface Validation {
  id: string; candidateId: string; state: string; fingerprint: string; commitSha: string; detail: string;
  checks: { checkId: string; exitCode: number; timedOut: boolean; truncated: boolean; stdout: string; stderr: string; argv: string[] }[];
}
export interface CandidateView {
  candidate: {
    plan: { id: string; createdAt: string; fingerprint: string; targetRef: string; targetCommit: string;
      profile: { id: string; version: string }; inputs: { taskId: string; owner: string; execution: Execution }[];
      staticReport: { conflicts: { kind: string; tasks: string[]; evidence: string }[] } };
    state: string; assembly: { commit: string | null; conflictingTask: string | null; conflictingPaths: string[]; evidence: string } | null;
    latestValidation: string | null;
  };
  effectiveState: string; currentlyValid: boolean; invalidReason: string; validation: Validation | null;
}
export interface Proposal { id: string; branch: string; commitSha: string; targetRef: string; targetCommit: string; validationId: string; fingerprint: string; mergePolicy: string }
interface Operation { path: string; body: Record<string, unknown>; label: string }
class HttpError extends Error { constructor(readonly status: number, message: string) { super(message); } }
const ROOT = "/api/v1/integrations/candidates";
const gateRoles: Record<string, string> = { CODE_REVIEW: "ENGINEERING_LEAD", UNIT_TEST: "QA_LEAD", INTEGRATION_TEST: "QA_LEAD", BUSINESS_ACCEPTANCE: "BUSINESS_APPROVER" };

export function createWorkbench(fetcher: typeof fetch = fetch, newKey: () => string = () => crypto.randomUUID()) {
  // Credentials never enter serializable state, localStorage, URLs or request bodies.
  let credential = "", epoch = 0, readSequence = 0, detailSequence = 0, navigationSequence = 0;
  const state = reactive({ snapshot: null as Snapshot | null, candidateIds: [] as string[], selectedId: "",
    detail: null as CandidateView | null, proposal: null as Proposal | null,
    fresh: false, detailFresh: false, loading: false, detailLoading: false, busy: false,
    error: "", notice: "", pending: null as Operation | null });

  async function api<T>(path: string, token: string, body?: Record<string, unknown>): Promise<T> {
    const response = await fetcher(path, { method: body ? "POST" : "GET", cache: "no-store", redirect: "error",
      headers: { Accept: "application/json", Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({})) as { error?: string };
      throw new HttpError(response.status, `HTTP ${response.status}：${payload.error ?? "服务暂不可用"}`);
    }
    return response.json() as Promise<T>;
  }
  function logout() {
    epoch++; readSequence++; detailSequence++; credential = "";
    Object.assign(state, { snapshot: null, candidateIds: [], selectedId: "", detail: null, proposal: null,
      fresh: false, detailFresh: false, loading: false, detailLoading: false, busy: false, error: "", notice: "", pending: null });
  }
  async function login(token: string) {
    logout(); credential = token.trim();
    if (!credential) { state.error = "请输入项目访问凭据。"; return; }
    await refresh();
  }
  async function refresh() {
    if (!credential) return;
    const session = epoch, sequence = ++readSequence, token = credential;
    detailSequence++; state.detailLoading = false;
    state.loading = true; state.fresh = false; state.detailFresh = false; state.proposal = null; state.error = "";
    try {
      const [snapshot, ids] = await Promise.all([api<Snapshot>("/api/v1/workbench", token), api<string[]>(ROOT, token)]);
      if (session !== epoch || sequence !== readSequence) return;
      state.snapshot = snapshot; state.candidateIds = ids; state.fresh = true;
      if (state.selectedId) await loadCandidate(state.selectedId);
    } catch (error) {
      if (session !== epoch || sequence !== readSequence) return;
      const message = error instanceof Error ? error.message : "读取失败";
      if (error instanceof HttpError && error.status === 403) logout();
      state.error = message;
    } finally { if (session === epoch && sequence === readSequence) state.loading = false; }
  }
  async function select(id: string) {
    navigationSequence++;
    await loadCandidate(id);
  }
  async function loadCandidate(id: string) {
    if (!credential) return;
    const session = epoch, sequence = ++detailSequence, token = credential;
    state.selectedId = id; state.detailFresh = false; state.detail = null; state.proposal = null; state.detailLoading = true;
    try {
      const detail = await api<CandidateView>(`${ROOT}/${encodeURIComponent(id)}`, token);
      if (session === epoch && sequence === detailSequence) { state.detail = detail; state.detailFresh = true; }
    } catch (error) {
      if (session === epoch && sequence === detailSequence) state.error = error instanceof Error ? error.message : "候选读取失败";
    } finally { if (session === epoch && sequence === detailSequence) state.detailLoading = false; }
  }
  const hasRole = (role: string) => !!state.snapshot?.roles.includes(role);
  const writable = () => state.fresh && !!state.snapshot && !state.snapshot.reconciliationRequired && !state.busy && !state.pending;
  const canAssign = (task: Task) => writable() && hasRole("PROJECT_MANAGER") && task.currentlyValid && task.status === "READY" && !task.execution;
  const canClaim = (task: Task) => writable() && task.currentlyValid && task.status === "READY" && !task.execution
    && task.claimable === true && task.owner === state.snapshot?.subjectId;
  const canDispatch = (task: Task) => writable() && task.currentlyValid && task.status === "CLAIMED" && !!task.execution
    && task.capacityHeld === true && !task.executor && task.owner === state.snapshot?.subjectId;
  const independent = () => !!state.detail && !state.detail.candidate.plan.inputs.some(input => input.owner === state.snapshot?.subjectId);
  const currentCandidate = () => writable() && state.detailFresh && !!state.detail?.currentlyValid && state.detail.candidate.state === "READY" && independent();
  function canValidate() { return currentCandidate() && hasRole("QA_LEAD") && state.detail?.validation?.state !== "RUNNING"; }
  const canReconcile = () => writable() && state.detailFresh && independent() && hasRole("QA_LEAD") && state.detail?.validation?.state === "RUNNING";
  function canPropose() {
    const detail = state.detail;
    return currentCandidate() && hasRole("ENGINEERING_LEAD") && !!detail?.validation && detail.validation.state === "PASSED"
      && detail.candidate.latestValidation === detail.validation.id && detail.validation.fingerprint === detail.candidate.plan.fingerprint
      && detail.validation.commitSha === detail.candidate.assembly?.commit
      && detail.candidate.plan.inputs.every(input => state.snapshot?.tasks.some(task => task.id === input.taskId && task.currentlyValid && task.status === "ACCEPTED"));
  }
  function canTask(task: Task, action: string, gate?: string) {
    if (!writable() || task.owner === state.snapshot?.subjectId) return false;
    if (action === "confirm_execution_stopped") return hasRole("PROJECT_MANAGER") && task.capacityHeld === true
      && !!task.execution?.executionId && ["CLAIMED", "RUNNING", "BLOCKED", "CANCELLED", "STALE"].includes(task.status);
    if (action === "revalidate_task") return hasRole("ARCHITECT") && !task.capacityHeld && !task.currentlyValid && task.status !== "CANCELLED";
    if (!task.currentlyValid) return false;
    if (action === "retry_task") return hasRole("PROJECT_MANAGER") && !task.capacityHeld && ["REJECTED", "BLOCKED"].includes(task.status);
    if (!task.execution?.commitSha) return false;
    if (action === "start_review") return hasRole("ENGINEERING_LEAD") && task.status === "SUBMITTED";
    if (action === "start_testing") return hasRole("QA_LEAD") && task.status === "CODE_REVIEW" && task.gates.CODE_REVIEW === true;
    if (action === "accept_task") return hasRole("BUSINESS_APPROVER") && task.status === "TESTING" && Object.keys(gateRoles).every(key => task.gates[key]);
    if (action === "record_gate" && gate) return hasRole(gateRoles[gate] ?? "") && (task.status === (gate === "CODE_REVIEW" ? "CODE_REVIEW" : "TESTING")
      || (!!state.snapshot?.evidenceRequired && task.gates[gate] === true
        && (task.status === "ACCEPTED" || (task.status === "TESTING" && gate === "CODE_REVIEW"))));
    return false;
  }
  function canRecordGate(task: Task, gate: string, passed: boolean, statement?: string) {
    if (!canTask(task, "record_gate", gate) || (!passed && (task.status === "ACCEPTED" || (task.status === "TESTING" && gate === "CODE_REVIEW")))) return false;
    if (!state.snapshot?.evidenceRequired || ["CODE_REVIEW", "BUSINESS_ACCEPTANCE"].includes(gate)) return !!statement?.trim();
    const detail = state.detail, validation = detail?.validation;
    return currentCandidate() && detail!.candidate.plan.inputs.some(input => input.taskId === task.id)
      && !!validation && validation.state === (passed ? "PASSED" : "FAILED")
      && detail!.candidate.latestValidation === validation.id && validation.fingerprint === detail!.candidate.plan.fingerprint
      && validation.commitSha === detail!.candidate.assembly?.commit;
  }
  function dependencyClosure(ids: string[]) {
    const included = new Set<string>(), visiting = new Set<string>();
    function visit(id: string) {
      if (visiting.has(id)) throw new Error("任务依赖存在循环");
      if (included.has(id)) return;
      const task = state.snapshot?.tasks.find(task => task.id === id);
      if (!task || !task.currentlyValid || !task.execution?.commitSha || !["SUBMITTED", "CODE_REVIEW", "TESTING", "ACCEPTED"].includes(task.status))
        throw new Error(`任务 ${id} 尚未有效提交，无法组成完整依赖集合。`);
      if (task.owner === state.snapshot?.subjectId) throw new Error("执行者不能创建包含自己任务的集成候选。");
      visiting.add(id); task.dependencies.forEach(visit); visiting.delete(id); included.add(id);
    }
    ids.forEach(visit);
    if (!included.size || included.size > 20) throw new Error("请选择 1 至 20 个任务（包含依赖）。");
    return [...included].sort();
  }
  async function run(operation: Operation) {
    if (state.busy || !credential) return;
    const session = epoch, token = credential, navigation = navigationSequence;
    readSequence++; detailSequence++; state.loading = false; state.detailLoading = false;
    state.pending = operation; state.busy = true; state.proposal = null; state.error = ""; state.notice = "";
    try {
      const result = await api<CandidateView | Proposal | Validation>(operation.path, token, operation.body);
      if (session !== epoch) return;
      state.pending = null; state.notice = `${operation.label}已完成；正在核对当前状态。`;
      await refresh();
      if (session !== epoch) return;
      if (operation.path === ROOT && "candidate" in result && navigation === navigationSequence) await loadCandidate(result.candidate.plan.id);
      if (session !== epoch) return;
      if (operation.path.endsWith("/proposal") && "branch" in result && state.detailFresh && state.detail?.currentlyValid
          && state.detail.validation?.id === result.validationId && state.detail.candidate.plan.fingerprint === result.fingerprint) state.proposal = result;
    } catch (error) {
      if (session !== epoch) return;
      // 409 can mean a durable plan exists but Git publication or journal completion was interrupted.
      const definitive = error instanceof HttpError && [400, 401, 403, 404, 405, 422].includes(error.status);
      if (definitive) state.pending = null;
      readSequence++; detailSequence++; state.loading = false; state.detailLoading = false;
      state.fresh = false; state.detailFresh = false;
      state.error = `${error instanceof Error ? error.message : "请求失败"}${definitive ? "；请刷新后核对状态。" : "；结果尚未确认，请查询状态或使用原请求重试，不要创建重复操作。"}`;
    } finally { if (session === epoch) state.busy = false; }
  }
  async function createCandidate(ids: string[], profileId: string) {
    if (!writable() || !hasRole("ENGINEERING_LEAD")) return;
    try {
      const taskIds = dependencyClosure(ids);
      if (!state.snapshot?.profiles.some(profile => profile.id === profileId)) throw new Error("请选择已配置的验证方案。");
      await run({ path: ROOT, label: "候选创建", body: { taskIds, profileId, expectedVersion: state.snapshot.contextVersion, idempotencyKey: newKey() } });
    } catch (error) { state.error = error instanceof Error ? error.message : "不能创建候选"; }
  }
  async function validate() {
    if (!canValidate() || !state.detail) return;
    await run({ path: `${ROOT}/${encodeURIComponent(state.detail.candidate.plan.id)}/validate`, label: "组合验证",
      body: { expectedFingerprint: state.detail.candidate.plan.fingerprint, idempotencyKey: newKey() } });
  }
  async function propose() {
    if (!canPropose() || !state.detail) return;
    await run({ path: `${ROOT}/${encodeURIComponent(state.detail.candidate.plan.id)}/proposal`, label: "人工合并建议",
      body: { expectedFingerprint: state.detail.candidate.plan.fingerprint, validationId: state.detail.validation!.id, idempotencyKey: newKey() } });
  }
  async function reconcile(reason:string) {
    if(!canReconcile()||!state.detail)return;
    if(!reason.trim()){state.error="请先核对进程和验证工作目录，再填写对账说明。";return;}
    await run({path:`${ROOT}/${encodeURIComponent(state.detail.candidate.plan.id)}/reconcile-validation`,label:"未知验证对账",
      body:{idempotencyKey:newKey(),expectedFingerprint:state.detail.candidate.plan.fingerprint,validationId:state.detail.validation!.id,reason:reason.trim()}});
  }
  async function taskCommand(id: string, action: string, gate?: string, passed?: boolean, evidenceRef?: string) {
    const task = state.snapshot?.tasks.find(task => task.id === id);
    if (!task || !canTask(task, action, gate)) return;
    let gateFields: Record<string, unknown> = {};
    if (action === "record_gate") {
      if (typeof passed !== "boolean" || !gate || !canRecordGate(task, gate, passed, evidenceRef)) {
        state.error = state.snapshot?.evidenceRequired ? "请填写评审或业务说明；测试门禁须选择包含本任务、结果与审批一致的最新有效平台验证。已验收任务只能更新通过证据。" : "门禁审批需要填写证据引用。";
        return;
      }
      gateFields = { gate, passed, ...(state.snapshot?.evidenceRequired
        ? { taskExecutionId: task.execution!.executionId, ...(["CODE_REVIEW", "BUSINESS_ACCEPTANCE"].includes(gate) ? { statement: evidenceRef!.trim() }
          : { candidateId: state.detail!.candidate.plan.id, validationId: state.detail!.validation!.id }) }
        : { evidenceRef: evidenceRef!.trim() }) };
    }
    if (action === "confirm_execution_stopped" && !evidenceRef?.trim()) { state.error = "请先确认执行进程已停止，并填写核对证据。"; return; }
    await run({ path: "/api/v1/governance/commands", label: "任务操作", body: {
      taskId: id, action, expectedVersion: state.snapshot!.contextVersion, idempotencyKey: newKey(),
      ...(task.execution?.commitSha ? { commitSha: task.execution.commitSha } : {}),
      ...(action === "confirm_execution_stopped" ? { taskExecutionId: task.execution!.executionId, evidenceRef: evidenceRef!.trim() } : {}),
      ...gateFields
    } });
  }
  async function assign(id: string, assignee: string) {
    const task = state.snapshot?.tasks.find(task => task.id === id);
    if (!task || !canAssign(task)) return;
    if (!state.snapshot?.assignees?.some(member => member.subjectId === assignee)) { state.error = "请选择本项目成员。"; return; }
    await run({ path: "/api/v1/workbench/assign", label: "任务分配", body: {
      taskId: id, assignee, expectedVersion: state.snapshot.contextVersion, idempotencyKey: newKey()
    } });
  }
  async function claim(id: string) {
    const task = state.snapshot?.tasks.find(task => task.id === id);
    if (!task || !canClaim(task)) return;
    await run({ path: "/api/v1/workbench/claim", label: "任务领取", body: {
      taskId: id, expectedVersion: state.snapshot!.contextVersion, idempotencyKey: newKey()
    } });
  }
  async function dispatchExecutor(id: string, executorId: string) {
    const task = state.snapshot?.tasks.find(task => task.id === id);
    if (!task || !canDispatch(task)) return;
    if (!state.snapshot?.executors?.some(executor => executor.id === executorId)) { state.error = "请选择项目已配置的执行器。"; return; }
    await run({ path: `/api/v1/executions/${encodeURIComponent(task.execution!.executionId)}/dispatch`, label: "执行器投递", body: {
      taskId: id, executorId, expectedVersion: state.snapshot.contextVersion, idempotencyKey: newKey()
    } });
  }
  async function refreshExecutor(id: string) {
    const task = state.snapshot?.tasks.find(task => task.id === id);
    if (!task?.executor || !task.execution || !writable() || task.owner !== state.snapshot?.subjectId) return;
    await run({ path: `/api/v1/executions/${encodeURIComponent(task.execution.executionId)}/refresh`, label: "执行状态查询", body: {} });
  }
  return { state, login, logout, refresh, select, hasRole, writable, canValidate, canPropose, canTask, dependencyClosure,
    canAssign, canClaim, assign, claim, canDispatch, dispatchExecutor, refreshExecutor, canReconcile, reconcile,
    createCandidate, validate, propose, taskCommand, canRecordGate,
    acknowledgePending: () => { if (state.fresh && !state.busy) { state.pending = null; state.notice = "已结束本页重试记录；服务端操作和证据仍保留。"; } },
    retry: async () => { if (state.pending) await run({ ...state.pending, body: { ...state.pending.body } }); } };
}
