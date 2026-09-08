export interface PlanWorkPackage {
  id: string;
  title: string;
  role: string;
}

export interface ProjectOverview {
  projectId: string;
  mergePolicy: "HUMAN_ONLY";
  analysis: {
    snapshotCommit: string;
    technologyStack: string[];
    evidence: Array<{ claim: string; path: string; sourceType: string }>;
  };
  plan: {
    workPackages: PlanWorkPackage[];
  };
}

export async function fetchProjectOverview(fetcher: typeof fetch = fetch): Promise<ProjectOverview> {
  const response = await fetcher("/api/v1/demo/overview", {
    headers: { Accept: "application/json" }
  });
  if (!response.ok) {
    throw new Error(`Control plane API returned HTTP ${response.status}`);
  }
  return response.json() as Promise<ProjectOverview>;
}
