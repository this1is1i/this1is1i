"""Local-only sample preparation, governed-worktree materialization and independent builds."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
PIN = '5e62608af333b5757bf767e05b19e2b0d56f0afb'
TEMPLATE = ROOT / 'samples/article-app'


def owned_destination(path):
    path = Path(path).resolve()
    if not any(path.is_relative_to(parent) and path != parent for parent in
               [(ROOT / '.runtime').resolve(), (ROOT / 'samples/workspaces').resolve()]):
        raise ValueError('Destination must be a new child of this project .runtime or samples/workspaces')
    return path


def run(argv, cwd, capture=False, timeout=240):
    environment = os.environ.copy()
    for key in ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_OBJECT_DIRECTORY',
                'GIT_ALTERNATE_OBJECT_DIRECTORIES', 'GIT_NAMESPACE']:
        environment.pop(key, None)
    environment.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                       PYTHONUTF8='1', npm_config_cache=str(Path(cwd) / '.cache/npm'))
    argv = [str(arg) for arg in argv]
    if os.name == 'nt' and argv[0].lower().endswith(('.cmd', '.bat')):
        if any(any(c in arg for c in '%!&|<>^"\r\n') for arg in argv):
            raise ValueError('Batch command arguments contain unsupported shell metacharacters')
        command = ' '.join('"' + arg + '"' for arg in argv)
        # Pass an exact cmd command line; list2cmdline escapes embedded quotes for CRT, not cmd.
        argv = '"' + os.environ.get('COMSPEC', 'cmd.exe') + '" /d /s /c "' + command + '"'
    result = subprocess.run(argv, cwd=cwd, env=environment, check=True, timeout=timeout,
                            text=True, encoding='utf-8', errors='replace',
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    return result.stdout.strip() if capture else None


def git(repo, *args):
    return run(['git', '--no-replace-objects', '--no-lazy-fetch', '-C', repo, *args], ROOT, True)


def write_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as stream:
        stream.write(value)


def confined(path, root):
    resolved, boundary = Path(path).resolve(), Path(root).resolve()
    if not resolved.is_relative_to(boundary) or resolved == boundary:
        raise ValueError('Resolved side-effect path escapes its owning directory: ' + str(path))
    return resolved


def copy_owned(source, target, boundary, ignored):
    source = Path(source).resolve()
    confined(target, boundary)
    # Reject external symlink/junction sources before copytree follows any file content.
    for parent, directories, files in os.walk(source, followlinks=False):
        excluded = set(ignored(parent, directories + files))
        directories[:] = [name for name in directories if name not in excluded]
        for name in directories + [name for name in files if name not in excluded]:
            if not (Path(parent) / name).resolve().is_relative_to(source):
                raise ValueError('Copy source contains an external link: ' + str(Path(parent) / name))
    shutil.copytree(source, target, ignore=ignored, symlinks=True)


def verifier(part='all', cwd='.'):
    app = 'modern/article-app' if cwd == '.' else ('.' if part == 'all' else '..')
    return [sys.executable, '-X', 'utf8', str(ROOT / 'scripts/article_sample.py'), 'verify',
            '--app', app, '--part', part]


def prepare(destination):
    destination = owned_destination(destination)
    if destination.exists():
        raise ValueError('Destination already exists; inspect its manifest, never overwrite or blindly retry')
    source = ROOT / 'samples/workspaces/springmvc-blog'
    git(source, 'cat-file', '-e', PIN + '^{commit}')
    destination.mkdir(parents=True)
    repo = destination / 'repository'
    # Durable ownership marker precedes every Git mutation. A partial attempt remains inspectable.
    write_new(destination / 'manifest.json', json.dumps({'id': str(uuid.uuid4()), 'repository': str(repo),
              'sourceCommit': PIN, 'template': str(TEMPLATE), 'policy': 'LOCAL_ONLY_HUMAN_MERGE'}, indent=2))
    run(['git', 'init', '-b', 'main', repo], ROOT, True)
    # Platform Git and verifier Git must observe the same bytes even with external defaults.
    git(repo, 'config', '--local', 'core.autocrlf', 'false')
    if os.name == 'nt':
        # Task IDs and immutable validation paths add depth beyond the legacy checkout.
        # Configure only this newly owned repository; never change Windows/global Git.
        git(repo, 'config', '--local', 'core.longpaths', 'true')
    git(repo, '-c', 'protocol.file.allow=always', 'fetch', '--no-tags', '--no-write-fetch-head', str(source), PIN)
    git(repo, 'update-ref', 'refs/heads/main', PIN, '0' * 40)
    git(repo, 'checkout', 'main')
    command = verifier()
    task = {'id': 'SCAFFOLD', 'title': 'Materialize independent article application', 'role': 'DEVELOPER',
            'dependencies': [], 'allowedPaths': ['modern'], 'estimatedHours': 2, 'workingDirectory': '.',
            'acceptanceCommands': [' '.join(command)]}
    definition = {'projectId': 'legacy-blog', 'repository': str(repo), 'targetRef': 'refs/heads/main',
        'forbiddenPaths': ['.git', 'Blog', 'modern/article-app/backend/data'],
        'plan': {'baseCommit': PIN, 'targetArchitectureMermaid': 'flowchart LR\n Platform -->|acceptance evidence| SampleAPI\n SampleUI --> SampleAPI\n SampleAPI --> BusinessDB\n Platform --> GovernanceDB',
                 'workPackages': [task], 'criticalPath': ['SCAFFOLD'], 'parallelExecutionGroups': {},
                 'apiContract': 'Article CRUD scaffold; old routes and authorization require milestone 10 parity approval',
                 'databaseCompatibility': 'Isolated H2 development fixture only; production database strategy remains undecided',
                 'functionalParity': {'legacy': 'Preserve source and behavior checklist; scaffold is not parity acceptance'},
                 'requiredEvidence': ['independent backend tests', 'independent Vue tests and build', 'human review']},
        'resources': {'modern': {'kind': 'PATH', 'locator': 'modern', 'version': '1'}},
        'taskResources': {'SCAFFOLD': ['modern']}, 'directoryCreators': {'modern': 'SCAFFOLD'},
        'taskProfiles': {'SCAFFOLD': 'sample-build'},
        'validationProfiles': [{'id': 'sample-build', 'version': '1', 'checks': [
            {'id': 'independent-build', 'argv': command, 'workingDirectory': '.', 'timeoutSeconds': 300}]}],
        'architectureDecisions': [{'id': 'ADR-ISOLATION', 'statement': 'Independent sample process and business database; platform stores governance only',
            'evidence': [{'path': 'Blog/pom.xml', 'blobId': git(repo, 'rev-parse', PIN + ':Blog/pom.xml')}], 'taskIds': ['SCAFFOLD']}]}
    write_new(destination / 'project.json', json.dumps(definition, ensure_ascii=False, indent=2))
    return destination / 'project.json'


def materialize(worktree):
    worktree = owned_destination(worktree)
    if not (worktree / '.git').is_file():
        raise ValueError('Materialize only into a platform-created Git worktree')
    common = Path(git(worktree, 'rev-parse', '--path-format=absolute', '--git-common-dir')).resolve()
    manifest = common.parent.parent / 'manifest.json'
    if not manifest.is_file() or Path(json.loads(manifest.read_text(encoding='utf-8'))['repository']).resolve() != common.parent:
        raise ValueError('Worktree is not owned by a prepared sample manifest')
    if git(worktree, 'rev-parse', 'HEAD') != PIN or git(worktree, 'status', '--porcelain'):
        raise ValueError('Scaffold task must start clean at the fixed source commit')
    target = worktree / 'modern/article-app'
    confined(target, worktree)
    if target.exists():
        raise ValueError('Scaffold destination exists; inspect it instead of overwriting')
    copy_owned(TEMPLATE, target, worktree, shutil.ignore_patterns('target', 'node_modules', 'dist', 'data', '.cache', '*.tsbuildinfo'))
    return target


def verify(app, part):
    app = Path(app).resolve()
    if not app.is_relative_to(ROOT):
        raise ValueError('Verification must remain inside this project workspace')
    # Validate both build roots and their output roots before any subprocess or dependency copy.
    for relative in ['backend', 'backend/target', 'frontend', 'frontend/node_modules', 'frontend/dist']:
        confined(app / relative, app)
    if part in ['all', 'backend']:
        maven = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
        if not maven:
            raise ValueError('Maven must already be installed; this command never installs global tools')
        run([maven, '-o', '-s', ROOT / 'scripts/maven-offline-settings.xml',
             '-Dmaven.repo.local=' + str(ROOT / 'backend/.m2-repository'), 'verify'], app / 'backend')
    if part in ['all', 'frontend']:
        frontend = app / 'frontend'
        dependencies = frontend / 'node_modules'
        if not dependencies.exists():
            cached = ROOT / 'frontend/node_modules'
            if not cached.is_dir():
                raise ValueError('Project frontend dependencies must be installed first; no implicit download')
            expected = json.loads((frontend / 'package-lock.json').read_text(encoding='utf-8'))['packages']
            installed = json.loads((ROOT / 'frontend/package-lock.json').read_text(encoding='utf-8'))['packages']
            if {k:v for k,v in expected.items() if k} != {k:v for k,v in installed.items() if k}:
                raise ValueError('Sample and cached dependency locks differ; install the sample dependencies explicitly')
            copy_owned(cached, dependencies, app, shutil.ignore_patterns('.vite-temp', '.vite'))
        node = shutil.which('node')
        if not node:
            raise ValueError('Node must already be installed')
        for program, args in [('vitest/vitest.mjs', ['run']), ('vue-tsc/bin/vue-tsc.js', ['--noEmit']), ('vite/bin/vite.js', ['build'])]:
            run([node, dependencies / program, *args], frontend)
    return {'app': str(app), 'part': part, 'result': 'PASSED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('prepare').add_argument('--destination', type=Path, required=True)
    sub.add_parser('materialize').add_argument('--worktree', type=Path, required=True)
    check = sub.add_parser('verify')
    check.add_argument('--app', type=Path, required=True)
    check.add_argument('--part', choices=['all', 'backend', 'frontend'], default='all')
    args = parser.parse_args()
    result = prepare(args.destination) if args.command == 'prepare' else materialize(args.worktree) if args.command == 'materialize' else verify(args.app, args.part)
    print(json.dumps(result, ensure_ascii=False, default=str))


if __name__ == '__main__':
    main()
