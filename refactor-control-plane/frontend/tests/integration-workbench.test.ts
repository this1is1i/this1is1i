import { expect, it } from "vitest";

async function factory() {
  const module = await import(/* @vite-ignore */ new URL("../src/integration/workbench.ts", import.meta.url).href).catch(() => ({}));
  expect(module).toHaveProperty("createWorkbench");
  return (module as any).createWorkbench;
}
const snapshot = (subjectId = "lead", roles = ["ENGINEERING_LEAD"]) => ({
  subjectId, roles, projectId: "legacy-blog", contextVersion: 3, reconciliationRequired: false,
  tasks: [{ id: "A", owner: "dev", dependencies: [], status: "ACCEPTED", currentlyValid: true, gates: {}, execution: { commitSha: "aaa" } },
    { id: "B", owner: "dev", dependencies: ["A"], status: "ACCEPTED", currentlyValid: true, gates: {}, execution: { commitSha: "bbb" } }],
  profiles: [{ id: "java", version: "1", checks: ["compile"] }], activeLeases: [], events: []
});
const view = (id = "one", valid = true) => ({
  candidate: { plan: { id, fingerprint: "fp-" + id, inputs: [{ taskId: "A", owner: "dev" }], staticReport: { conflicts: [] } },
    state: "READY", assembly: { commit: "candidate-sha" }, latestValidation: "validation" },
  effectiveState: valid ? "READY" : "STALE", currentlyValid: valid, invalidReason: valid ? "" : "target changed",
  validation: { id: "validation", state: "PASSED", fingerprint: "fp-" + id, commitSha: "candidate-sha", checks: [] }
});
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(r => resolve = r); return { promise, resolve }; };

it("binds configured gates to personal statements or the selected current platform validation", async () => {
  const create = await factory(), posts: any[] = [];
  const actor: any = snapshot("lead", ["ENGINEERING_LEAD"]);
  actor.evidenceRequired = true; actor.tasks[0].status = "CODE_REVIEW"; actor.tasks[0].execution.executionId = "execution-a";
  const candidate = view();
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") { posts.push(JSON.parse(init.body as string)); return json({ recorded: true }); }
    return json(url.endsWith("workbench") ? actor : url.endsWith("candidates") ? ["one"] : candidate);
  }, () => "gate-key");
  await model.login("lead-secret");
  await model.taskCommand("A", "record_gate", "CODE_REVIEW", true, " Reviewed diff ");
  expect(posts[0]).toMatchObject({ statement: "Reviewed diff", gate: "CODE_REVIEW", passed: true, taskExecutionId: "execution-a" });
  expect(posts[0]).not.toHaveProperty("evidenceRef");
  actor.subjectId = "qa"; actor.roles = ["QA_LEAD"]; actor.tasks[0].status = "TESTING"; actor.tasks[1].status = "TESTING";
  await model.login("qa-secret");
  await model.taskCommand("A", "record_gate", "UNIT_TEST", true, "external report");
  expect(posts).toHaveLength(1);
  await model.select("one");
  await model.taskCommand("B", "record_gate", "UNIT_TEST", true);
  expect(posts).toHaveLength(1);
  await model.taskCommand("A", "record_gate", "UNIT_TEST", true);
  expect(posts[1]).toMatchObject({ candidateId: "one", validationId: "validation", gate: "UNIT_TEST" });
  expect(posts[1]).not.toHaveProperty("statement");
  candidate.candidate.latestValidation = "newer"; await model.refresh();
  await model.taskCommand("A", "record_gate", "UNIT_TEST", true); expect(posts).toHaveLength(2);
  candidate.candidate.latestValidation = "validation"; actor.tasks[0].status = "ACCEPTED"; actor.tasks[0].gates.UNIT_TEST = true;
  await model.refresh();
  await model.taskCommand("A", "record_gate", "UNIT_TEST", false); expect(posts).toHaveLength(2);
  await model.taskCommand("A", "record_gate", "UNIT_TEST", true); expect(posts).toHaveLength(3);
});

it("dispatches a configured executor only for the current owner's claimed execution and preserves unknown requests", async () => {
  const create = await factory(); const requests: any[] = [];
  const actor: any = snapshot("dev", ["DEVELOPER"]);
  actor.executors = [{ id: "internal", kind: "HTTP" }];
  actor.tasks = [{ id: "A", owner: "dev", status: "CLAIMED", currentlyValid: true, capacityHeld: true,
    execution: { executionId: "execution-a", commitSha: null }, gates: {}, dependencies: [] }];
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") { requests.push({url, body: JSON.parse(init.body as string)}); return json({ error: "uncertain" }, 409); }
    return json(url.endsWith("workbench") ? actor : []);
  }, () => "executor-key");
  await model.login("secret");
  expect(model.dispatchExecutor).toBeTypeOf("function");
  expect(model.canDispatch(model.state.snapshot.tasks[0])).toBe(true);
  await model.dispatchExecutor("A", "unconfigured"); expect(requests).toHaveLength(0);
  await model.dispatchExecutor("A", "internal");
  expect(requests[0]).toEqual({url:"/api/v1/executions/execution-a/dispatch",body:{taskId:"A",executorId:"internal",expectedVersion:3,idempotencyKey:"executor-key"}});
  await model.dispatchExecutor("A", "internal"); expect(requests).toHaveLength(1);
  await model.retry(); expect(requests[1]).toEqual(requests[0]);
});

it("requires independent stop evidence tied to the current execution before allowing another attempt", async () => {
  const create = await factory(); const posts: any[] = [];
  const actor: any = snapshot("manager", ["PROJECT_MANAGER"]);
  actor.tasks = [{ id: "A", owner: "dev", status: "BLOCKED", currentlyValid: true, capacityHeld: true,
    execution: { executionId: "attempt-1", commitSha: null }, gates: {}, dependencies: [] }];
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") { posts.push(JSON.parse(init.body as string)); return json({ task: actor.tasks[0] }); }
    return json(url.endsWith("workbench") ? actor : []);
  }, () => "stop-key");
  await model.login("secret");
  const task = model.state.snapshot.tasks[0];
  expect(model.canTask(task, "retry_task")).toBe(false);
  expect(model.canTask(task, "confirm_execution_stopped")).toBe(true);
  await model.taskCommand("A", "confirm_execution_stopped"); expect(posts).toHaveLength(0);
  await model.taskCommand("A", "confirm_execution_stopped", undefined, undefined, " process-log:exited ");
  expect(posts).toEqual([{ action: "confirm_execution_stopped", taskId: "A", expectedVersion: 3,
    idempotencyKey: "stop-key", taskExecutionId: "attempt-1", evidenceRef: "process-log:exited" }]);
  actor.tasks[0].currentlyValid = false; actor.tasks[0].status = "STALE"; await model.refresh();
  expect(model.canTask(model.state.snapshot.tasks[0], "confirm_execution_stopped")).toBe(true);
  actor.tasks[0].currentlyValid = true; actor.tasks[0].status = "RUNNING"; await model.refresh();
  expect(model.canTask(model.state.snapshot.tasks[0], "confirm_execution_stopped")).toBe(true);
  actor.subjectId = "dev"; await model.refresh();
  expect(model.canTask(model.state.snapshot.tasks[0], "confirm_execution_stopped")).toBe(false);
});

it("lets independent QA reconcile a stale unknown validation with exact evidence",async()=>{
  const create=await factory();const posts:any[]=[];const candidate=view("one",false);candidate.validation.state="RUNNING";
  const model=create(async(url:string,init?:RequestInit)=>{if(init?.method==="POST"){posts.push(JSON.parse(init.body as string));return json({...candidate.validation,state:"ABANDONED"});}
    return json(url.endsWith("workbench")?snapshot("qa",["QA_LEAD"]):url.endsWith("candidates")?["one"]:candidate);},()=>"reconcile-key");
  await model.login("qa-secret");await model.select("one");expect(model.canReconcile).toBeTypeOf("function");expect(model.canReconcile()).toBe(true);
  await model.reconcile("Process stopped; inspected validation worktree");
  expect(posts[0]).toEqual({idempotencyKey:"reconcile-key",expectedFingerprint:"fp-one",validationId:"validation",reason:"Process stopped; inspected validation worktree"});
});

it("assigns only to project members and claims only ready work owned by the current identity", async () => {
  const create = await factory(); const posts: any[] = [];
  const actor: any = snapshot("manager", ["PROJECT_MANAGER"]);
  actor.assignees = [{ subjectId: "dev", roles: ["DEVELOPER"] }];
  actor.tasks = [{ id: "A", owner: "task:A", status: "READY", currentlyValid: true, claimable: true, gates: {}, dependencies: [] }];
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") { posts.push({ url, body: JSON.parse(init.body as string) }); return json({ assigned: true }); }
    return json(url.endsWith("workbench") ? actor : []);
  }, () => "dispatch-key");
  await model.login("manager-secret");
  expect(model.canAssign).toBeTypeOf("function"); expect(model.canAssign(model.state.snapshot.tasks[0])).toBe(true);
  await model.assign("A", "outsider"); expect(posts).toHaveLength(0);
  await model.assign("A", "dev");
  expect(posts[0]).toEqual({ url: "/api/v1/workbench/assign", body: { taskId: "A", assignee: "dev", expectedVersion: 3, idempotencyKey: "dispatch-key" } });
  actor.subjectId = "dev"; actor.roles = ["DEVELOPER"]; actor.tasks[0].owner = "dev";
  await model.login("developer-secret"); expect(model.canClaim(model.state.snapshot.tasks[0])).toBe(true);
  await model.claim("A"); expect(posts[1].url).toBe("/api/v1/workbench/claim");
  expect(posts[1].body).toEqual({ taskId: "A", expectedVersion: 3, idempotencyKey: "dispatch-key" });
  expect(JSON.stringify(model.state)).not.toContain("secret");
  actor.tasks[0].claimable = false; await model.refresh(); await model.claim("A"); expect(posts).toHaveLength(2);
  actor.tasks[0].claimable = true; actor.tasks[0].owner = "other"; await model.refresh(); await model.claim("A"); expect(posts).toHaveLength(2);
});

it("claim uncertainty preserves the original key and delayed results cannot revive a logged-out session", async () => {
  const create = await factory(), pending = deferred<Response>(); const bodies: string[] = [];
  const actor: any = snapshot("dev", ["DEVELOPER"]);
  actor.tasks = [{ id: "A", owner: "dev", status: "READY", currentlyValid: true, claimable: true, dependencies: [] }];
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") { bodies.push(init.body as string); return bodies.length === 1 ? json({ error: "pending plan" }, 409) : pending.promise; }
    return json(url.endsWith("workbench") ? actor : []);
  }, () => "claim-once");
  await model.login("token"); expect(model.claim).toBeTypeOf("function"); await model.claim("A");
  expect(model.state.pending).not.toBeNull(); await model.claim("A"); expect(bodies).toHaveLength(1);
  const retry = model.retry(); model.logout(); pending.resolve(json({ task: { execution: { path: "private-path" } } })); await retry;
  expect(bodies[1]).toBe(bodies[0]); expect(model.state.snapshot).toBeNull(); expect(JSON.stringify(model.state)).not.toContain("private-path");
});

it("ignores a late authenticated response after logout", async () => {
  const create = await factory(); const pending = deferred<Response>();
  const model = create(async (url: string) => url.endsWith("workbench") ? pending.promise : json([]));
  const login = model.login("secret"); model.logout(); pending.resolve(json(snapshot())); await login;
  expect(model.state.snapshot).toBeNull(); expect(model.state.candidateIds).toEqual([]);
  expect(JSON.stringify(model.state)).not.toContain("secret");
});

it("ignores an old candidate response after switching selection", async () => {
  const create = await factory(); const pending = deferred<Response>();
  const model = create(async (url: string) => url.endsWith("workbench") ? json(snapshot()) : url.endsWith("/one") ? pending.promise : url.endsWith("/two") ? json(view("two")) : json(["one", "two"]));
  await model.login("token"); const first = model.select("one"); await model.select("two"); pending.resolve(json(view())); await first;
  expect(model.state.detail.candidate.plan.id).toBe("two");
});

it("expands dependencies and submits authoritative context with one idempotency key", async () => {
  const create = await factory(); const posts: any[] = [];
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") { posts.push(JSON.parse(init.body as string)); return json(view()); }
    return json(url.endsWith("workbench") ? snapshot() : url.endsWith("/one") ? view() : ["one"]);
  }, () => "stable-key");
  await model.login("token"); await model.createCandidate(["B"], "java");
  expect(posts).toEqual([{ taskIds: ["A", "B"], profileId: "java", expectedVersion: 3, idempotencyKey: "stable-key" }]);
});

it("blocks duplicate writes and retries a network-uncertain write with its original body", async () => {
  const create = await factory(); const pending = deferred<Response>(); const posts: string[] = [];
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") { posts.push(init.body as string); if (posts.length === 1) return pending.promise; return json(view()); }
    return json(url.endsWith("workbench") ? snapshot() : url.endsWith("/one") ? view() : []);
  }, () => "original-key");
  await model.login("token"); const first = model.createCandidate(["A"], "java");
  await model.createCandidate(["B"], "java"); expect(posts).toHaveLength(1);
  pending.resolve(new Response("unavailable", { status: 503 })); await first;
  expect(model.state.pending).not.toBeNull();
  await model.createCandidate(["B"], "java"); expect(posts).toHaveLength(1);
  await model.retry(); expect(posts).toHaveLength(2); expect(posts[1]).toBe(posts[0]);
});

it("stale evidence, own work and the wrong role cannot enable a proposal", async () => {
  const create = await factory(); let actor = snapshot(); let candidate = view();
  const model = create(async (url: string) => json(url.endsWith("workbench") ? actor : url.endsWith("/one") ? candidate : ["one"]));
  await model.login("token"); await model.select("one"); expect(model.canPropose()).toBe(true);
  candidate = view("one", false); await model.refresh(); expect(model.canPropose()).toBe(false);
  candidate = view(); actor = snapshot("dev"); await model.refresh(); expect(model.canPropose()).toBe(false);
  actor = snapshot("qa", ["QA_LEAD"]); await model.refresh(); expect(model.canPropose()).toBe(false);
});

it("a failed refresh disables actions while preserving clearly stale evidence", async () => {
  const create = await factory(); let offline = false;
  const model = create(async (url: string) => offline ? new Response("offline", { status: 503 }) : json(url.endsWith("workbench") ? snapshot() : url.endsWith("/one") ? view() : ["one"]));
  await model.login("token"); await model.select("one"); offline = true; await model.refresh();
  expect(model.state.fresh).toBe(false); expect(model.canPropose()).toBe(false); expect(model.state.error).toContain("503");
});

it("a read started before a failed write cannot restore fresh action eligibility", async () => {
  const create = await factory(); const oldRead = deferred<Response>(); let detailReads = 0;
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") return json({ error: "target changed" }, 409);
    if (url.endsWith("workbench")) return json(snapshot());
    if (url.endsWith("/one")) { detailReads++; return detailReads === 2 ? oldRead.promise : json(view()); }
    return json(["one"]);
  });
  await model.login("token"); await model.select("one");
  // The refresh has received its task snapshot, but its candidate read is still pending.
  const refreshing = model.refresh();
  await new Promise(resolve => setTimeout(resolve, 0));
  // Use a task command while candidate refresh is still in flight.
  model.state.snapshot.tasks[0].status = "SUBMITTED";
  await model.taskCommand("A", "start_review");
  oldRead.resolve(json(view())); await refreshing;
  expect(model.state.fresh).toBe(false);
  expect(model.state.detailFresh).toBe(false);
});

it("an older full refresh cannot overwrite the result of a completed write", async () => {
  const create = await factory(); const oldRead = deferred<Response>(); let snapshots = 0;
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") return json({ recorded: true });
    if (url.endsWith("workbench")) { snapshots++; return snapshots === 2 ? oldRead.promise : json({ ...snapshot(), contextVersion: snapshots === 1 ? 3 : 4 }); }
    return json([]);
  });
  await model.login("token"); model.state.snapshot.tasks[0].status = "SUBMITTED";
  const writing = model.taskCommand("A", "start_review");
  // A user-triggered refresh during the write and the post-write refresh may overlap.
  const refresh = model.refresh();
  oldRead.resolve(json(snapshot())); await Promise.all([writing, refresh]);
  expect(model.state.snapshot.contextVersion).toBe(4);
});

it("retains the original key after a recoverable 409 during candidate publication", async () => {
  const create = await factory(); const requests: string[] = [];
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") { requests.push(init.body as string); return requests.length === 1 ? json({ error: "publication interrupted" }, 409) : json(view()); }
    return json(url.endsWith("workbench") ? snapshot() : url.endsWith("/one") ? view() : ["one"]);
  }, () => "recoverable");
  await model.login("token"); await model.createCandidate(["A"], "java");
  expect(model.state.pending).not.toBeNull(); await model.refresh(); expect(model.writable()).toBe(false);
  await model.retry(); expect(requests).toHaveLength(2); expect(requests[1]).toBe(requests[0]);
});

it("finishing a creation does not override a newer candidate selection", async () => {
  const create = await factory(); const pending = deferred<Response>();
  const model = create(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") return pending.promise;
    return json(url.endsWith("workbench") ? snapshot() : url.endsWith("/two") ? view("two") : url.endsWith("/new") ? view("new") : ["two", "new"]);
  });
  await model.login("token"); const creating = model.createCandidate(["A"], "java");
  await model.select("two"); pending.resolve(json(view("new"))); await creating;
  expect(model.state.selectedId).toBe("two"); expect(model.state.detail.candidate.plan.id).toBe("two");
});
