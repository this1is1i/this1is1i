import { describe, expect, it } from "vitest";
import { fetchProjectOverview } from "../src/control-plane-api";

describe("control plane API", () => {
  it("loads the evidence-backed project overview", async () => {
    const fakeFetch: typeof fetch = async () => new Response(JSON.stringify({
      projectId: "legacy-blog",
      mergePolicy: "HUMAN_ONLY",
      analysis: { snapshotCommit: "abc", technologyStack: ["Java 7"], evidence: [] },
      plan: { workPackages: [{ id: "WP-120", title: "API", role: "DEVELOPER" }] }
    }), { status: 200, headers: { "Content-Type": "application/json" } });

    const overview = await fetchProjectOverview(fakeFetch);

    expect(overview.projectId).toBe("legacy-blog");
    expect(overview.mergePolicy).toBe("HUMAN_ONLY");
    expect(overview.plan.workPackages).toHaveLength(1);
  });

  it("fails closed when the governance backend is unavailable", async () => {
    const fakeFetch: typeof fetch = async () => new Response("offline", { status: 503 });

    await expect(fetchProjectOverview(fakeFetch)).rejects.toThrow("Control plane API returned HTTP 503");
  });
});
