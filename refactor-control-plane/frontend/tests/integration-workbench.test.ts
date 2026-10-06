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
