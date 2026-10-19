"""Isolated browser release fixture. Test identities only; no existing service or database is reused."""
import argparse
from contextlib import ExitStack
import functools
import hashlib
import http.server
import json
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import urllib.request
import urllib.error
import uuid
import sys
import article_sample as sample
import mysql_sample
from verify_milestone7 import Server, request

ROOT=sample.ROOT


def owned_case(value):
    path=Path(value).resolve();boundary=(ROOT/'.runtime').resolve()
    if not boundary.is_relative_to(ROOT) or boundary==ROOT or not path.is_relative_to(boundary) or path==boundary:
        raise ValueError('Release cases must remain inside this project .runtime')
    return path


def proxy(directory, origin):
    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self,*args):pass
        def handle_request(self):
            if not self.path.startswith(('/api/','/mcp')):
                if self.command!='GET':return self.send_error(405)
                if not Path(self.translate_path(self.path)).is_file():self.path='/index.html'
                return super().do_GET()
            body=self.rfile.read(int(self.headers.get('Content-Length','0'))) or None
            headers={k:v for k,v in self.headers.items() if k.lower() in ['authorization','content-type','accept','cookie','x-csrf-token']}
            query=urllib.request.Request(origin+self.path,data=body,headers=headers,method=self.command)
            try:response=urllib.request.urlopen(query,timeout=360)
            except urllib.error.HTTPError as error:response=error
            with response:
                data=response.read();self.send_response(response.code)
                for key,value in response.headers.items():
                    if key.lower() in ['content-type','set-cookie','content-disposition','cache-control','x-content-type-options']:self.send_header(key,value)
                self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        do_GET=do_POST=do_PUT=do_DELETE=handle_request
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(directory)))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    return server,'http://127.0.0.1:'+str(server.server_port)


def materialize(case):
    data=json.loads((case/'urls.json').read_text(encoding='utf-8'));origin=data['platformOrigin']
    snapshot=request(origin,'/api/v1/workbench','manager');task=snapshot['tasks'][0]
    assert task['status']=='CLAIMED' and task['owner']=='developer'
    token=request(origin,'/api/v1/governance/commands','manager',dict(taskId='SCAFFOLD',action='assign_task',assignee='developer',expectedVersion=1,idempotencyKey=str(uuid.uuid4())))['taskToken']
    def mcp(name,**fields):
        reply=request(origin,'/mcp',payload={'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':name,'arguments':dict(taskId='SCAFFOLD',taskToken=token,expectedVersion=1,idempotencyKey=str(uuid.uuid4()),**fields)}})
        assert 'error' not in reply,reply
        return reply['result']['structuredContent']
    mcp('reserve_paths',ownerId='developer',resourcePath='modern',ttlSeconds=300)
    tree=Path(task['execution']['path']);sample.materialize(tree)
    sample.git(tree,'add','modern');sample.git(tree,'-c','user.name=Release fixture','-c','user.email=fixture@localhost','-c','commit.gpgsign=false','commit','-m','Release article fixture')
    sha=sample.git(tree,'rev-parse','HEAD');assert mcp('submit_changeset',commitSha=sha)['accepted']
    (case/'submitted.json').write_text(json.dumps({'executionId':task['execution']['executionId'],'commitSha':sha}),encoding='utf-8')


def start_sample(case,args):
    urls=json.loads((case/'urls.json').read_text(encoding='utf-8'))
    cid=args.candidate
    detail=request(urls['platformOrigin'],'/api/v1/integrations/candidates/'+cid,'lead')
    assert detail['validation']['state']=='PASSED' and detail['currentlyValid']
    checked=json.loads(detail['validation']['checks'][0]['stdout'].strip().splitlines()[-1]);app=Path(checked['app'])
    fixture=mysql_sample.Fixture(case/'sample-database',args.mysqld,args.mysql,app)
    child=log=ui=None
    try:
        fixture.start()
        crypto=next((ROOT/'backend/.m2-repository/org/springframework/security/spring-security-crypto').glob('*/*.jar'))
        jcl=next((ROOT/'backend/.m2-repository/org/springframework/spring-jcl').glob('*/*.jar'))
        java_file=case/'FixtureHash.java'
        java_file.write_text('class FixtureHash { public static void main(String[] a) { System.out.print("{bcrypt}"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("browser-fixture-only")); }}',encoding='utf-8')
        hash_value=sample.run([args.java,'--class-path',str(crypto)+(';' if sample.os.name=='nt' else ':')+str(jcl),java_file],ROOT,True)
        accounts=case/'article-accounts.json';accounts.write_text(json.dumps([{'subject':s,'passwordHash':hash_value} for s in ['author-one','author-two']]),encoding='utf-8')
        settings=dict(line.split('=',1) for line in (fixture.path/'application.properties').read_text(encoding='utf-8').splitlines() if '=' in line)
        config={'source':{'url':settings['article.fixture.source.url'],'username':settings['article.fixture.source.username'],'password':settings['article.fixture.source.password']},
                'target':{'url':settings['spring.datasource.url'],'username':settings['spring.datasource.username'],'password':settings['spring.datasource.password']},
                'accountsFile':str(accounts),'sourceZone':'Europe/Istanbul','targetStatus':'PUBLISHED','owners':{'3':'author-one','8':'author-two'},'operator':'fixture-operator'}
        config_file=case/'migration.json';config_file.write_text(json.dumps(config),encoding='utf-8')
        jar=case/'article.jar';shutil.copy2(app/'backend/target/article-sample-0.1.0-SNAPSHOT.jar',jar)
        preview=json.loads(sample.run([args.java,'-jar',jar,'--migration','preview',config_file],ROOT,True))
        result=json.loads(sample.run([args.java,'-jar',jar,'--migration','apply',config_file,preview['fingerprint']],ROOT,True));assert result['imported']==7
        log=(case/'article.log').open('w',encoding='utf-8')
        command=[args.java,'-jar',str(jar),'--server.address=127.0.0.1','--server.port=0','--spring.config.additional-location=file:'+str(fixture.path/'application.properties'),'--article.accounts-file='+str(accounts)]
        child=subprocess.Popen(command,cwd=case,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if sample.os.name=='nt' else 0)
        for _ in range(300):
            if child.poll() is not None:raise RuntimeError('Owned article process exited; inspect article.log')
            match=re.search(r'Tomcat started on port (\d+)',(case/'article.log').read_text(encoding='utf-8'))
            if match:break
            time.sleep(.2)
        else:raise TimeoutError('Owned article startup unconfirmed')
        ui,url=proxy(app/'frontend/dist','http://127.0.0.1:'+match[1]);urls.update(sampleUi=url,candidateId=cid,candidateCommit=checked['candidateCommit'])
        (case/'urls.json').write_text(json.dumps(urls,indent=2),encoding='utf-8')
        return fixture,child,log,ui
    except BaseException:
        close_business((fixture,child,log,ui));raise


def close_database(fixture):
    try:
        fixture.close()
    except BaseException as error:
        # Windows mysqld has a separate server child. Terminating its launcher
        # cannot prove database shutdown. Preserve unknown state for inspection.
        path=fixture.path.resolve();boundary=(ROOT/'.runtime').resolve()
        if boundary.is_relative_to(ROOT) and path.is_relative_to(boundary) and path!=boundary:
            (path/'cleanup-unknown.json').write_text(json.dumps({'cleanupState':'UNCONFIRMED','databaseStopped':None}),encoding='utf-8')
        raise RuntimeError('MySQL cleanup unconfirmed; inspect retained manifest and processes before retrying') from error


def close_business(business):
    fixture,child,log,web=business
    with ExitStack() as cleanup:
        cleanup.callback(close_database,fixture)
        if log:cleanup.callback(log.close)
        if child:
            cleanup.callback(lambda: child.wait(timeout=20))
            cleanup.callback(lambda: child.terminate() if child.poll() is None else None)
        if web:
            cleanup.callback(web.server_close);cleanup.callback(web.shutdown)


def serve_sample(case,args):
    business=None
    stopped=None
    try:
        if not args.candidate:raise ValueError('An explicit validated --candidate is required')
        if (case/'stop').exists():raise ValueError('This fixture has already stopped')
        business=start_sample(case,args)
        print((case/'urls.json').read_text(encoding='utf-8'),flush=True)
        while not (case/'stop').exists():time.sleep(.2)
    finally:
        try:
            if business:
                close_business(business)
                stopped=business[1].poll() is not None and business[0].process.poll() is not None
        finally:
            (case/'sample-stopped.json').write_text(json.dumps({'sampleStopped':stopped}),encoding='utf-8')


def serve(args):
    case=mysql_sample.new_destination(args.case);case.mkdir(parents=True)
    definition=sample.prepare(case/'managed');data=json.loads(definition.read_text(encoding='utf-8'))
    argv=[sys.executable,'-X','utf8',str(ROOT/'scripts/verify_milestone10.py'),'check','--app','modern/article-app','--mysqld',str(args.mysqld),'--mysql',str(args.mysql)]
    data['plan']['workPackages'][0]['acceptanceCommands']=[' '.join(argv)]
    data['plan']['apiContract']='Owned management with CSRF, CAS, sanitized HTML and stable legacy routes'
    data['plan']['databaseCompatibility']='Independent MySQL8, SELECT-only source, transactional mapping and preserved edits/deletions'
    data['validationProfiles'][0]['checks']=[{'id':'release-article-parity','argv':argv,'workingDirectory':'.','timeoutSeconds':300}]
    data['requirements']=[{'id':'MVP-08','statement':'Release article parity','taskIds':['SCAFFOLD']}]
    definition.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    identities=json.loads((ROOT/'backend/src/test/resources/review-identities.json').read_text(encoding='utf-8'))
    identities.append({'subjectId':'auditor','projectId':'legacy-blog','roles':['AUDITOR'],'tokenSha256':hashlib.sha256(b'auditor-test-token').hexdigest()})
    identity=case/'identities.json';identity.write_text(json.dumps(identities),encoding='utf-8')
    jar=case/'platform.jar';shutil.copy2(ROOT/'backend/target/control-plane-backend-0.1.0-SNAPSHOT.jar',jar)
    platform=None;ui=None
    try:
        platform=Server([args.java,'-jar',str(jar),'--server.address=127.0.0.1','--server.port=0','--control-plane.project.mode=configured',
            '--control-plane.project.definition-file='+str(definition),'--control-plane.identity-file='+str(identity),
            '--management.endpoint.shutdown.enabled=true','--management.endpoints.web.exposure.include=health,shutdown',
            '--spring.datasource.url=jdbc:h2:file:'+(case/'governance').as_posix()],ROOT/'backend',case/'platform.log')
        ui,url=proxy(ROOT/'frontend/dist',platform.origin)
        (case/'urls.json').write_text(json.dumps({'platformUi':url,'platformOrigin':platform.origin},indent=2),encoding='utf-8')
        print(json.dumps({'platformUi':url,'case':str(case)}),flush=True)
        while not (case/'stop').exists():
            time.sleep(.2)
    finally:
        with ExitStack() as cleanup:
            if platform:cleanup.callback(platform.stop)
            if ui:cleanup.callback(ui.server_close);cleanup.callback(ui.shutdown)
        (case/'stopped.json').write_text(json.dumps({'platformStopped':platform is None or platform.child.poll() is not None}),encoding='utf-8')


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['serve','materialize','sample','stop']);p.add_argument('--case',type=Path,required=True)
    p.add_argument('--java',default=shutil.which('java'));p.add_argument('--mysqld',type=Path);p.add_argument('--mysql',type=Path);p.add_argument('--candidate');args=p.parse_args()
    case=owned_case(args.case)
    if args.mode=='serve':serve(args)
    elif args.mode=='materialize':materialize(case)
    elif args.mode=='sample':serve_sample(case,args)
    else:
        if not (case/'urls.json').is_file():raise ValueError('Owned release fixture required')
        (case/'stop').touch(exist_ok=False)


if __name__=='__main__':main()
