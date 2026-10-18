import { expect,it } from "vitest";
import { createPlanWorkspace } from "../src/project/plan";
const revision={fingerprint:"one",parentFingerprint:null,definition:{plan:{baseCommit:"base",workPackages:[]}},architect:null,manager:null,activated:true};
const history={activeFingerprint:"one",runtimeFingerprint:"one",subjectId:"architect",roles:["ARCHITECT"],revisions:[revision]};
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{"Content-Type":"application/json"}});
it("ignores an old identity read and disables writes after refresh failure",async()=>{
  let finish!:(response:Response)=>void; let calls=0,fail=false;
  const fetcher=(async()=> {if(++calls===1)return new Promise<Response>(resolve=>finish=resolve); if(fail)return json({},503);return json(calls===2?history:{revision,analysis:{relations:[],findings:[],evidence:[]}});}) as typeof fetch;
  const model=createPlanWorkspace(fetcher); const old=model.login("old");await model.login("new");finish(json({...history,subjectId:"old"}));await old;
  expect(model.state.history?.subjectId).toBe("architect");expect(model.canDecide("ARCHITECTURE")).toBe(true);
  fail=true;await model.refresh();expect(model.canDecide("ARCHITECTURE")).toBe(false);
});
it("retains the exact uncertain operation and blocks a second mutation until retry",async()=>{
  const posts:string[]=[];let uncertain=true;
  const fetcher=(async(path,init)=>{if(init?.method==="POST"){posts.push(init.body as string);if(uncertain)throw new Error("connection lost");return json(revision);}return json(String(path).endsWith("/revisions")?history:{revision,analysis:{relations:[],findings:[],evidence:[]}});}) as typeof fetch;
  const model=createPlanWorkspace(fetcher);await model.login("token");await model.decide("ARCHITECTURE");
  expect(model.state.pending).not.toBeNull();expect(model.canDecide("ARCHITECTURE")).toBe(false);
  uncertain=false;await model.retry();expect(posts).toHaveLength(2);expect(posts[0]).toBe(posts[1]);expect(model.state.pending).toBeNull();
});
