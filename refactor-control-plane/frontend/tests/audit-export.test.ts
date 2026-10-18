import { expect, it } from "vitest";

const scope="a".repeat(64);
const snapshot={projectId:"legacy-blog",subjectId:"auditor",roles:["AUDITOR"],planScope:scope,auditExportSupported:true,tasks:[],profiles:[],activeLeases:[],events:[]};
const response=(value:unknown)=>new Response(JSON.stringify(value),{headers:{"Content-Type":"application/json"}});
const factory=async()=> (await import(/* @vite-ignore */ new URL("../src/integration/workbench.ts",import.meta.url).href) as any).createWorkbench;
const deferred=()=>{let resolve!:(value:Response)=>void;const promise=new Promise<Response>(r=>resolve=r);return{promise,resolve};};

it("downloads the original audit JSON with header credentials and rejects stale session or refresh responses",async()=>{
  const create=await factory(), calls:any[]=[], saves:any[]=[];
  let pending=deferred();
  const model=create(async(url:string,init:RequestInit)=>{
    calls.push({url,init});
    if(url.includes("audit/export"))return pending.promise;
    return response(url.endsWith("workbench")?snapshot:[]);
  });
  await model.login("private-token");
  expect(model.exportAudit).toBeTypeOf("function");
  const text=`{"schemaVersion":"refactor-control-plane.audit.v1","payload":{"plan":{"scope":"${scope}"},"precise":12345678901234567890}}`;
  let exportPromise=model.exportAudit((...args:any[])=>saves.push(args));
  pending.resolve(new Response(text));await exportPromise;
  expect(saves).toEqual([[text,`audit-${scope}.json`]]);
  const call=calls.find(c=>c.url.includes("audit/export"));
  expect(call.url).toBe(`/api/v1/audit/export?expectedPlanScope=${scope}`);
  expect(call.init.headers.Authorization).toBe("Bearer private-token");
  expect(call.init.cache).toBe("no-store");expect(call.url).not.toContain("private-token");
  for(const action of [()=>model.logout(),()=>model.refresh(),()=>model.select("other")]){
    await model.login("private-token");pending=deferred();
    exportPromise=model.exportAudit((...args:any[])=>saves.push(args));
    await action();pending.resolve(new Response(text));await exportPromise;
    expect(saves).toHaveLength(1);
  }
});

it("requires an auditor and matching returned plan scope, and surfaces export errors without saving",async()=>{
  const create=await factory(), saves:any[]=[];let report:any={schemaVersion:"refactor-control-plane.audit.v1",payload:{plan:{scope:"wrong"}}};let count=0;
  const model=create(async(url:string)=>{
    if(url.includes("audit/export")){count++;return response(report);}
    return response(url.endsWith("workbench")?snapshot:[]);
  });
  await model.login("secret");await model.exportAudit((...args:any[])=>saves.push(args));
  expect(saves).toHaveLength(0);expect(model.state.error).toContain("审计");
  model.state.snapshot.roles=["DEVELOPER"];
  await model.exportAudit((...args:any[])=>saves.push(args));expect(count).toBe(1);
});
