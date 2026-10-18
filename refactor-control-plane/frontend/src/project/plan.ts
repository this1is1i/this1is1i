import { reactive } from "vue";
export interface Definition { projectId: string; repository: string; targetRef: string; targetCommit?: string; baselineCandidateId?: string;
  plan: { baseCommit: string; targetArchitectureMermaid: string; apiContract: string; databaseCompatibility: string;
    workPackages: {id:string;title:string;role:string;dependencies:string[];allowedPaths:string[];workingDirectory:string;acceptanceCommands:string[]}[] };
  architectureDecisions?: {id:string;statement:string;evidence:{path:string;blobId:string}[];taskIds:string[]}[];
}
export interface Revision {fingerprint:string;parentFingerprint:string|null;definition:Definition;architect:string|null;manager:string|null;activated:boolean}
interface History {activeFingerprint:string;runtimeFingerprint:string;subjectId:string;roles:string[];revisions:Revision[]}
interface Analysis {snapshotCommit:string;currentArchitectureMermaid:string;relations:{kind:string;from:string;to:string;confidence:string;explanation:string;evidenceIds:string[]}[];
  findings:{kind:string;statement:string}[];evidence:{id:string;path:string;blobId:string;snapshotCommit:string}[]}
interface Detail {revision:Revision;analysis:Analysis}
interface Operation {path:string;body:Record<string,unknown>}
const ROOT="/api/v1/project/plan";
class HttpError extends Error {constructor(readonly status:number,message:string){super(message);}}
export function createPlanWorkspace(fetcher:typeof fetch=fetch) {
  let credential="",epoch=0,read=0,selection=0;
  const state=reactive({history:null as History|null,detail:null as Detail|null,selected:"",fresh:false,detailFresh:false,
    loading:false,busy:false,error:"",notice:"",pending:null as Operation|null});
  async function api<T>(path:string,token:string,body?:Record<string,unknown>):Promise<T> {
    const response=await fetcher(path,{method:body?"POST":"GET",cache:"no-store",redirect:"error",
      headers:{Accept:"application/json",Authorization:`Bearer ${token}`,...(body?{"Content-Type":"application/json"}:{})},...(body?{body:JSON.stringify(body)}:{})});
    if(!response.ok){const data=await response.json().catch(()=>({})) as {error?:string};throw new HttpError(response.status,`HTTP ${response.status}：${data.error??"计划暂不可用"}`);}
    return response.json() as Promise<T>;
  }
  function logout(){epoch++;read++;selection++;credential="";Object.assign(state,{history:null,detail:null,selected:"",fresh:false,detailFresh:false,loading:false,busy:false,error:"",notice:"",pending:null});}
  async function login(token:string){logout();credential=token.trim();if(!credential){state.error="请输入项目凭据。";return;}await refresh();}
  async function refresh(){
    if(!credential)return;const session=epoch,sequence=++read,token=credential;selection++;
    state.fresh=false;state.detailFresh=false;state.loading=true;state.error="";
    try{const history=await api<History>(`${ROOT}/revisions`,token);if(session!==epoch||sequence!==read)return;
      state.history=history;state.fresh=true;const id=history.revisions.some(r=>r.fingerprint===state.selected)?state.selected:history.activeFingerprint;await select(id);
    }catch(error){if(session===epoch&&sequence===read)state.error=error instanceof Error?error.message:"读取失败";}
    finally{if(session===epoch&&sequence===read)state.loading=false;}
  }
  async function select(id:string){
    if(!credential)return;const session=epoch,sequence=++selection,token=credential;state.selected=id;state.detail=null;state.detailFresh=false;
    try{const detail=await api<Detail>(`${ROOT}/revisions/${encodeURIComponent(id)}`,token);if(session===epoch&&sequence===selection){state.detail=detail;state.detailFresh=true;}}
    catch(error){if(session===epoch&&sequence===selection)state.error=error instanceof Error?error.message:"计划详情读取失败";}
  }
  const restartRequired=()=>!!state.history&&state.history.activeFingerprint!==state.history.runtimeFingerprint;
  const writable=()=>state.fresh&&state.detailFresh&&!state.loading&&!state.busy&&!state.pending&&!restartRequired();
  const hasRole=(role:string)=>!!state.history?.roles.includes(role);
  const isActive=()=>state.selected===state.history?.activeFingerprint;
  const current=()=>isActive()||(!state.detail?.revision.activated&&state.detail?.revision.parentFingerprint===state.history?.activeFingerprint);
  function canDecide(decision:string){
    const revision=state.detail?.revision;if(!writable()||!revision||!current())return false;
    return decision==="ARCHITECTURE"?hasRole("ARCHITECT")&&!revision.architect&&revision.manager!==state.history?.subjectId:
      hasRole("PROJECT_MANAGER")&&!!revision.architect&&!revision.manager&&revision.architect!==state.history?.subjectId;
  }
  const canStage=()=>writable()&&hasRole("ARCHITECT")&&!!state.history?.revisions.find(r=>r.fingerprint===state.history?.activeFingerprint&&r.architect&&r.manager);
  const canActivate=()=>writable()&&hasRole("PROJECT_MANAGER")&&!isActive()&&current()&&!!state.detail?.revision.architect&&!!state.detail?.revision.manager;
  async function run(operation:Operation){
    if(!credential||state.busy)return;const session=epoch,token=credential;state.busy=true;state.error="";
    // Save the exact naturally idempotent request before it can reach the server.
    state.pending=operation;
    try{const response=await api<{fingerprint?:string;restartRequired?:boolean}>(operation.path,token,operation.body);if(session!==epoch)return;
      state.pending=null;state.notice=response.restartRequired?"新计划已激活。请重启平台服务，然后刷新；旧执行记录已保留。":"已记录，请以刷新后的审批状态为准。";
      if(response.fingerprint)state.selected=response.fingerprint;await refresh();
    }catch(error){if(session!==epoch)return;state.error=error instanceof Error?error.message:"写入结果未知";
      if(error instanceof HttpError&&[400,403,404].includes(error.status))state.pending=null;
      else state.notice="结果尚未确认。请刷新核对，或重试同一请求；当前请求已保留。";
    }finally{if(session===epoch)state.busy=false;}
  }
  async function decide(decision:string){if(!canDecide(decision))return;await run({path:isActive()?`${ROOT}/decisions`:`${ROOT}/revisions/${state.selected}/decisions`,body:{expectedFingerprint:state.selected,decision}});}
  async function stage(text:string){if(!canStage())return;try{const definition:unknown=JSON.parse(text);await run({path:`${ROOT}/revisions`,body:{expectedFingerprint:state.history!.activeFingerprint,definition}});}catch(error){state.error=error instanceof Error?error.message:"JSON 格式无效";}}
  async function activate(){if(!canActivate())return;await run({path:`${ROOT}/revisions/${state.selected}/activate`,body:{expectedFingerprint:state.history!.activeFingerprint}});}
  async function retry(){if(state.pending&&!state.busy)await run(state.pending);}
  return{state,login,logout,refresh,select,decide,stage,activate,retry,canDecide,canStage,canActivate,restartRequired};
}
