"""Project-only milestone 10 candidate validation and governed acceptance fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import uuid
import article_sample as sample
import mysql_sample
from verify_milestone7 import Server, request

ROOT = sample.ROOT


def check(app, mysqld, mysql):
    app = mysql_sample.verify_workspace(app)
    commit = sample.git(app, 'rev-parse', 'HEAD')
    fixture = mysql_sample.Fixture(ROOT / '.runtime' / ('phase10-candidate-db-' + uuid.uuid4().hex), mysqld, mysql, app)
    try:
        fixture.start()
        database = mysql_sample.verify(fixture)
        sample.verify(app, 'frontend')
    finally:
        fixture.close()
    assert fixture.manifest['state'] == 'STOPPED'
    return {'app': str(app), 'candidateCommit': commit, 'mysql': database, 'mysqlFixture': str(fixture.path),
            'fixtureState': fixture.manifest['state'], 'frontend': 'tests, typecheck and production build passed'}


def governed(args):
    case = mysql_sample.new_destination(args.destination)
    case.mkdir(parents=True)
    config = sample.prepare(case / 'managed')
    definition = json.loads(config.read_text(encoding='utf-8'))
    argv = [sys.executable, '-X', 'utf8', str(Path(__file__).resolve()), 'check', '--app', 'modern/article-app',
            '--mysqld', str(args.mysqld), '--mysql', str(args.mysql)]
    definition['plan']['workPackages'][0]['acceptanceCommands'] = [' '.join(argv)]
    definition['plan']['workPackages'][0]['title'] = 'Deliver approved article behavior and independent MySQL migration'
    definition['plan']['apiContract'] = 'Owned management with CSRF and CAS, sanitized public DTO and exact stable legacy routes'
    definition['plan']['databaseCompatibility'] = 'Independent MySQL8 source SELECT-only, target transactional mapping; explicit zone/status/owners; soft-deleted targets preserved'
    definition['plan']['functionalParity'] = {'article': 'Pinned seven posts: raw content, routes, blog/menu ownership and UTC mapping; approved editing and soft deletion'}
    definition['plan']['requiredEvidence'] = ['actual candidate MySQL behavior/migration tests', 'Vue tests/typecheck/build', 'independent review and business fixture roles']
    definition['validationProfiles'][0]['checks'] = [{'id': 'mysql-article-parity', 'argv': argv, 'workingDirectory': '.', 'timeoutSeconds': 300}]
    definition['requirements'] = [{'id': 'MVP-08', 'statement': 'Approved independent article slice with seven-post MySQL migration and preserved behavior', 'taskIds': ['SCAFFOLD']}]
    config.write_text(json.dumps(definition, ensure_ascii=False, indent=2), encoding='utf-8')
    identities = json.loads((ROOT / 'backend/src/test/resources/review-identities.json').read_text(encoding='utf-8'))
    identities.append({'subjectId': 'auditor', 'projectId': 'legacy-blog', 'roles': ['AUDITOR'],
                       'tokenSha256': hashlib.sha256(b'auditor-test-token').hexdigest()})
    identity_file = case / 'identities.json'
    identity_file.write_text(json.dumps(identities), encoding='utf-8')
    evidence = {'scope': 'milestone10 governed isolated acceptance; test roles do not attest actual human approval',
                'fixture': str(case), 'mainBefore': sample.git(case / 'managed/repository', 'rev-parse', 'main')}
    command = [args.java, '-jar', str(ROOT / 'backend/target/control-plane-backend-0.1.0-SNAPSHOT.jar'),
               '--server.address=127.0.0.1', '--server.port=0', '--control-plane.project.mode=configured',
               '--control-plane.project.definition-file=' + str(config), '--control-plane.identity-file=' + str(identity_file),
               '--management.endpoint.shutdown.enabled=true', '--management.endpoints.web.exposure.include=health,shutdown',
               '--spring.datasource.url=jdbc:h2:file:' + (case / 'governance').as_posix()]
    server = None
    try:
        server = Server(command, ROOT / 'backend', case / 'platform.log')
        origin = server.origin
        request(origin, '/api/articles', expected=404)
        state = request(origin, '/api/v1/project/plan', 'manager')
        scope = state['fingerprint']
        for actor, decision in [('architect', 'ARCHITECTURE'), ('manager', 'EXECUTION')]:
            request(origin, '/api/v1/project/plan/decisions', actor, {'expectedFingerprint': scope, 'decision': decision})
        execution_id = None

        def govern(actor, action, **fields):
            payload = dict(taskId='SCAFFOLD', action=action, expectedVersion=1, idempotencyKey=str(uuid.uuid4()), **fields)
            if execution_id:
                payload['taskExecutionId'] = execution_id
            return request(origin, '/api/v1/governance/commands', actor, payload)

        token = govern('manager', 'assign_task', assignee='developer')['taskToken']

        def mcp(name, **fields):
            response = request(origin, '/mcp', payload={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': {'name': name,
                'arguments': dict(taskId='SCAFFOLD', taskToken=token, expectedVersion=1, idempotencyKey=str(uuid.uuid4()), **fields)}})
            assert 'error' not in response, response
            return response['result']['structuredContent']

        execution = mcp('claim_task')['task']['execution']
        execution_id = execution['executionId']
        tree = Path(execution['path'])
        mcp('reserve_paths', ownerId='developer', resourcePath='modern')
        sample.materialize(tree)
        sample.git(tree, 'add', 'modern')
        sample.git(tree, '-c', 'user.name=Milestone10 fixture', '-c', 'user.email=fixture@localhost', '-c', 'commit.gpgsign=false', 'commit', '-m', 'Article behavior and independent migration')
        sha = sample.git(tree, 'rev-parse', 'HEAD')
        assert mcp('submit_changeset', commitSha=sha)['accepted']
        endpoint = '/api/v1/integrations/candidates'
        candidate = request(origin, endpoint, 'lead', {'idempotencyKey': 'm10-candidate', 'expectedVersion': 1,
                            'taskIds': ['SCAFFOLD'], 'profileId': 'sample-build'})['candidate']
        cid, fingerprint = candidate['plan']['id'], candidate['plan']['fingerprint']
        validation = request(origin, endpoint + '/' + cid + '/validate', 'qa', {'idempotencyKey': 'm10-validate', 'expectedFingerprint': fingerprint})
        assert validation['state'] == 'PASSED', validation
        result = json.loads(validation['checks'][0]['stdout'].strip().splitlines()[-1])
        assert result['candidateCommit'] == candidate['assembly']['commit']
        assert result['mysql']['sourceRows'] == 7 and result['fixtureState'] == 'STOPPED'
        govern('lead', 'start_review', commitSha=sha)
        govern('lead', 'record_gate', commitSha=sha, gate='CODE_REVIEW', passed=True, statement='Fixture role: reviewed approved article slice; migration independent review recorded in milestone10 evidence.')
        govern('qa', 'start_testing', commitSha=sha)
        for gate in ['UNIT_TEST', 'INTEGRATION_TEST']:
            govern('qa', 'record_gate', commitSha=sha, gate=gate, passed=True, candidateId=cid, validationId=validation['id'])
        govern('business', 'record_gate', commitSha=sha, gate='BUSINESS_ACCEPTANCE', passed=True,
                statement='Fixture role: approved decisions matched actual seven-post migration, ownership, sanitized HTML, stable paths and soft deletion tests; not a production human attestation.')
        govern('business', 'accept_task', commitSha=sha)
        proposal = request(origin, endpoint + '/' + cid + '/proposal', 'lead', {'idempotencyKey': 'm10-proposal', 'expectedFingerprint': fingerprint, 'validationId': validation['id']})
        assert proposal['automaticallyMergeMain'] is False
        report = request(origin, '/api/v1/audit/export?expectedPlanScope=' + scope + '&candidateId=' + cid, 'auditor')
        assert report['payload']['selectedCandidate']['eligibility'] == 'ELIGIBLE_AT_OBSERVATION'
        audit = case / 'audit.json'
        audit.write_text(json.dumps(report, ensure_ascii=False), encoding='utf-8')
        sample.run([sys.executable, '-X', 'utf8', ROOT / 'scripts/verify-audit-export.py', audit], ROOT)
        main_after = sample.git(case / 'managed/repository', 'rev-parse', 'main')
        assert main_after == evidence['mainBefore'] == sample.PIN
        evidence.update(planScope=scope, taskCommit=sha, executionId=execution_id, candidateId=cid,
                        candidateCommit=candidate['assembly']['commit'], candidateFingerprint=fingerprint,
                        validationId=validation['id'], validation=result, proposal=proposal,
                        auditPayloadSha256=report['integrity']['payloadSha256'], mainAfter=main_after)
    finally:
        if server:
            server.stop()
        evidence['platformStopped'] = server is None or server.child.poll() is not None
        (case / 'evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['check', 'governed'])
    parser.add_argument('--app', type=Path)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--mysqld', type=Path, required=True)
    parser.add_argument('--mysql', type=Path, required=True)
    parser.add_argument('--java', default=shutil.which('java'))
    args = parser.parse_args()
    result = check(args.app, args.mysqld, args.mysql) if args.mode == 'check' else governed(args)
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
