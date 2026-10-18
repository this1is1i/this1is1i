"""Isolated milestone 7 smoke: real Git worktrees, independent build, plan revision and file DB restart.

Uses only the explicitly supplied local test identities. Never targets an existing project database.
"""
import argparse
from contextlib import ExitStack
import functools
import http.server
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.request
import urllib.error
import uuid
import article_sample as sample

ROOT = sample.ROOT


def request(origin, path, actor=None, payload=None, expected=200, method=None):
    headers = {'Content-Type': 'application/json'}
    if actor:
        headers['Authorization'] = 'Bearer ' + actor + '-test-token'
    req = urllib.request.Request(origin + path, headers=headers,
        data=None if payload is None else json.dumps(payload).encode('utf-8'), method=method)
    try:
        with urllib.request.urlopen(req, timeout=360) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    assert status == expected, (path, status, data.decode('utf-8')[:3000])
    return json.loads(data) if data else None


class Server:
    def __init__(self, command, cwd, log):
        self.stream = log.open('w', encoding='utf-8')
        self.child = subprocess.Popen(command, cwd=cwd, stdout=self.stream, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if sample.os.name == 'nt' else 0)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if self.child.poll() is not None:
                    raise RuntimeError(log.read_text(encoding='utf-8'))
                match = re.search(r'Tomcat started on port (\d+)', log.read_text(encoding='utf-8'))
                if match:
                    self.origin = 'http://127.0.0.1:' + match[1]
                    request(self.origin, '/actuator/health')
                    return
                time.sleep(.2)
            raise TimeoutError('Service startup exceeded 60 seconds')
        except BaseException:
            self.stop()
            raise

    def stop(self):
        try:
            if self.child.poll() is None:
                if hasattr(self, 'origin'):
                    # Windows terminate() bypasses JVM/H2 shutdown hooks. This is a planned restart.
                    try:
                        request(self.origin, '/actuator/shutdown', payload={})
                        self.child.wait(timeout=30)
                    except BaseException:
                        if self.child.poll() is None:
                            self.child.terminate()
                            self.child.wait(timeout=20)
                        raise
                else:
                    self.child.terminate()
                    self.child.wait(timeout=20)
        finally:
            self.stream.close()


def stop_resources(services, proxies):
    # ExitStack runs every callback even if an earlier resource fails to stop.
    with ExitStack() as cleanup:
        for service in services:
            if service:
                cleanup.callback(service.stop)
        for item in proxies:
            cleanup.callback(item.server_close)
            cleanup.callback(item.shutdown)


def proxy(directory, origins, key):
    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def handle_request(self):
            if not self.path.startswith(('/api/', '/mcp')):
                if self.command == 'GET':
                    return super().do_GET()
                return self.send_error(405)
            body = self.rfile.read(int(self.headers.get('Content-Length', '0'))) or None
            headers = {k: v for k, v in self.headers.items() if k.lower() in ['authorization', 'content-type', 'accept']}
            req = urllib.request.Request(origins[key] + self.path, data=body, headers=headers, method=self.command)
            try:
                response = urllib.request.urlopen(req, timeout=360)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                data = response.read()
                self.send_response(response.code)
                self.send_header('Content-Type', response.headers.get('Content-Type', 'application/json'))
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        do_GET = do_POST = do_PUT = do_DELETE = handle_request
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Handler, directory=str(directory)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, 'http://127.0.0.1:' + str(server.server_port)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--keep-serving', action='store_true')
    args = parser.parse_args()
    case = Path(tempfile.mkdtemp(prefix='phase7-e2e-', dir=ROOT / '.runtime'))
    config = sample.prepare(case / 'managed')
    repo = case / 'managed/repository'
    common = [args.java, '-jar', str(ROOT / 'backend/target/control-plane-backend-0.1.0-SNAPSHOT.jar'),
              '--server.address=127.0.0.1', '--server.port=0', '--control-plane.project.mode=configured',
              '--management.endpoint.shutdown.enabled=true', '--management.endpoints.web.exposure.include=health,shutdown',
              '--control-plane.project.definition-file=' + str(config),
              '--control-plane.identity-file=' + str(ROOT / 'backend/src/test/resources/review-identities.json'),
              '--spring.datasource.url=jdbc:h2:file:' + (case / 'governance').as_posix()]
    platform = business = None
    proxies = []
    evidence = {'fixture': str(case), 'mainBefore': sample.git(repo, 'rev-parse', 'main'), 'servicesStopped': False, 'checks': []}
    print(json.dumps({'fixture': str(case)}, ensure_ascii=False), flush=True)
    try:
        platform = Server(common, ROOT / 'backend', case / 'platform-1.log')
        origin = platform.origin
        request(origin, '/api/articles', expected=404)
        state = request(origin, '/api/v1/project/plan', 'manager')
        original = state['fingerprint']
        for actor, decision in [('architect', 'ARCHITECTURE'), ('manager', 'EXECUTION')]:
            request(origin, '/api/v1/project/plan/decisions', actor, {'expectedFingerprint': original, 'decision': decision})

        def govern(actor, action, task='SCAFFOLD', **values):
            return request(origin, '/api/v1/governance/commands', actor,
                dict(taskId=task, action=action, expectedVersion=1, idempotencyKey=str(uuid.uuid4()), **values))

        token = govern('manager', 'assign_task', assignee='developer')['taskToken']

        def mcp(name, task='SCAFFOLD', **values):
            result = request(origin, '/mcp', payload={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                'params': {'name': name, 'arguments': dict(taskId=task, taskToken=token, expectedVersion=1,
                    idempotencyKey=str(uuid.uuid4()), **values)}})
            assert 'error' not in result, result
            return result['result']['structuredContent']

        claimed = mcp('claim_task')
        tree = Path(claimed['task']['execution']['path'])
        mcp('reserve_paths', ownerId='developer', resourcePath='modern')
        sample.materialize(tree)
        sample.git(tree, 'add', 'modern')
        sample.git(tree, '-c', 'user.name=Milestone smoke', '-c', 'user.email=smoke@localhost',
                   '-c', 'commit.gpgsign=false', 'commit', '-m', 'Independent sample scaffold')
        sha = sample.git(tree, 'rev-parse', 'HEAD')
        assert mcp('submit_changeset', commitSha=sha)['accepted']
        root = '/api/v1/integrations/candidates'
        candidate = request(origin, root, 'lead', {'idempotencyKey': 'sample-candidate', 'expectedVersion': 1,
            'taskIds': ['SCAFFOLD'], 'profileId': 'sample-build'})['candidate']
        cid, fp = candidate['plan']['id'], candidate['plan']['fingerprint']
        print(json.dumps({'stage': 'validating independent application', 'candidate': cid}), flush=True)
        validation = request(origin, root + '/' + cid + '/validate', 'qa', {'idempotencyKey': 'sample-validate', 'expectedFingerprint': fp})
        assert validation['state'] == 'PASSED', validation
        validation_app = Path(json.loads(validation['checks'][0]['stdout'].strip().splitlines()[-1])['app'])
        govern('lead', 'start_review', commitSha=sha)
        govern('lead', 'record_gate', commitSha=sha, gate='CODE_REVIEW', passed=True, evidenceRef='isolated scaffold contract tests')
        govern('qa', 'start_testing', commitSha=sha)
        for gate, actor in [('UNIT_TEST', 'qa'), ('INTEGRATION_TEST', 'qa'), ('BUSINESS_ACCEPTANCE', 'business')]:
            govern(actor, 'record_gate', commitSha=sha, gate=gate, passed=True, evidenceRef='scaffold-only smoke:' + validation['id'])
        govern('business', 'accept_task', commitSha=sha)
        next_plan = json.loads(config.read_text(encoding='utf-8'))
        next_plan.update(targetCommit=sample.PIN, baselineCandidateId=cid)
        next_plan['plan']['baseCommit'] = candidate['assembly']['commit']
        task = next_plan['plan']['workPackages'][0]
        task.update(id='IMPLEMENT', title='Continue backend in accepted scaffold', workingDirectory='modern/article-app/backend', allowedPaths=['modern/article-app/backend'])
        command = sample.verifier('backend', task['workingDirectory'])
        task['acceptanceCommands'] = [' '.join(command)]
        next_plan['plan']['criticalPath'] = ['IMPLEMENT']
        next_plan['resources']['modern']['locator'] = 'modern/article-app/backend'
        next_plan['taskResources'] = {'IMPLEMENT': ['modern']}
        next_plan['taskProfiles'] = {'IMPLEMENT': 'sample-build'}
        next_plan['directoryCreators'] = {}
        next_plan['validationProfiles'][0]['checks'][0].update(argv=command, workingDirectory=task['workingDirectory'])
        next_plan['architectureDecisions'][0]['taskIds'] = ['IMPLEMENT']
        revision = request(origin, '/api/v1/project/plan/revisions', 'architect', {'expectedFingerprint': original, 'definition': next_plan})
        revised = revision['fingerprint']
        endpoint = '/api/v1/project/plan/revisions/' + revised
        for actor, decision in [('architect', 'ARCHITECTURE'), ('manager', 'EXECUTION')]:
            request(origin, endpoint + '/decisions', actor, {'expectedFingerprint': revised, 'decision': decision})
        activation = request(origin, endpoint + '/activate', 'manager', {'expectedFingerprint': original})
        assert activation['restartRequired']
        request(origin, '/api/v1/workbench', 'manager', expected=409)
        platform.stop()
        platform = Server(common, ROOT / 'backend', case / 'platform-2.log')
        origin = platform.origin
        assert request(origin, '/api/v1/project/plan', 'manager')['fingerprint'] == revised
        token = govern('manager', 'assign_task', task='IMPLEMENT', assignee='developer')['taskToken']
        downstream = mcp('claim_task', task='IMPLEMENT')['task']['execution']
        assert (Path(downstream['path']) / task['workingDirectory'] / 'pom.xml').is_file()
        assert downstream['baseCommit'] == candidate['assembly']['commit']
        mcp('report_change_event', task='IMPLEMENT', type='TaskStatusChanged', targetStatus='CANCELLED')
        assert not request(origin, root + '/' + cid, 'manager')['currentlyValid']

        app_command = [args.java, '-jar', str(validation_app / 'backend/target/article-sample-0.1.0-SNAPSHOT.jar'),
                       '--management.endpoint.shutdown.enabled=true', '--management.endpoints.web.exposure.include=health,shutdown',
                       '--server.port=0', '--spring.datasource.url=jdbc:h2:file:' + (case / 'business').as_posix()]
        business = Server(app_command, validation_app / 'backend', case / 'business-1.log')
        article = request(business.origin, '/api/articles', payload={'title': '独立样例验证', 'content': 'Isolated business data', 'status': 'DRAFT'}, expected=201)
        request(business.origin, '/api/v1/workbench', expected=404)
        business.stop()
        business = Server(app_command, validation_app / 'backend', case / 'business-2.log')
        assert request(business.origin, '/api/articles/' + str(article['id']))['title'] == article['title']
        evidence.update(mainAfter=sample.git(repo, 'rev-parse', 'main'), originalFingerprint=original, activeFingerprint=revised,
                        candidateId=cid, candidateCommit=candidate['assembly']['commit'], validationId=validation['id'],
                        validationApp=str(validation_app), downstreamExecution=downstream,
                        checks=['configured platform has no article endpoint', 'independent scaffold in real task worktree',
                                'combined candidate backend/frontend builds', 'independent role gates', 'reviewed candidate baseline',
                                'activation rejects old runtime workbench reads', 'approved plan file-DB restart', 'downstream contains scaffold',
                                'old candidate is historical', 'independent business process and file DB graceful restart', 'main unchanged'])
        assert evidence['mainAfter'] == evidence['mainBefore'] == sample.PIN
        if args.keep_serving:
            origins = {'platform': platform.origin, 'business': business.origin}
            ui, url = proxy(ROOT / 'frontend/dist', origins, 'platform'); proxies.append(ui)
            sample_ui, sample_url = proxy(validation_app / 'frontend/dist', origins, 'business'); proxies.append(sample_ui)
            evidence.update(platformUi=url, sampleUi=sample_url, platformOrigin=platform.origin)
        (case / 'evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(evidence, ensure_ascii=False, indent=2), flush=True)
        if args.keep_serving:
            restarted = False
            while not (case / 'stop').exists():
                if (case / 'restart-platform').exists() and not restarted:
                    platform.stop(); platform = Server(common, ROOT / 'backend', case / 'platform-3.log')
                    origins['platform'] = platform.origin; restarted = True
                    (case / 'restart-complete').write_text(platform.origin, encoding='utf-8')
                time.sleep(.5)
    finally:
        try:
            stop_resources([platform, business], proxies)
        finally:
            evidence['servicesStopped'] = all(item is None or item.child.poll() is not None for item in [platform, business])
            (case / 'evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
