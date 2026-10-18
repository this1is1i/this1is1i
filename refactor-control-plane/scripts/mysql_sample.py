"""Run the article database contract against a new project-owned MySQL process, never a system service."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PIN = '5e62608af333b5757bf767e05b19e2b0d56f0afb'
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def new_destination(value):
    path = Path(value).resolve()
    boundary = (ROOT / '.runtime').resolve()
    if not boundary.is_relative_to(ROOT) or boundary==ROOT or not path.is_relative_to(boundary) or path == boundary or path.exists():
        raise ValueError('Use a new child of this project .runtime; existing data is never reused or overwritten')
    return path


def verify_workspace():
    app=(ROOT/'samples/article-app').resolve()
    if not app.is_relative_to(ROOT) or app==ROOT:
        raise ValueError('Article sample must remain inside this project')
    for path in [app/'backend',app/'backend/target']:
        resolved=path.resolve()
        if not resolved.is_relative_to(app) or resolved==app:
            raise ValueError('Sample build roots and output must remain inside the sample')
    if not (ROOT/'backend/.m2-repository').resolve().is_relative_to(ROOT):
        raise ValueError('Dependency cache must remain inside this project')


def statement(sql, prefix):
    match = re.search(r'^' + re.escape(prefix), sql, re.MULTILINE)
    if not match:
        raise ValueError('Fixed source SQL statement is missing')
    quote = None
    index = match.start()
    while index < len(sql):
        char = sql[index]
        if quote:
            if char == '\\':
                index += 2
                continue
            if char == quote:
                if index + 1 < len(sql) and sql[index + 1] == quote:
                    index += 2
                    continue
                quote = None
        elif char in "'\"`":
            quote = char
        elif char == ';':
            return sql[match.start():index + 1]
        index += 1
    raise ValueError('Unterminated fixed source SQL statement')


def execute(argv, cwd=ROOT, text=None, timeout=60, capture=True):
    environment = {key: value for key, value in os.environ.items() if key.upper() in ['PATH','SYSTEMROOT','WINDIR','TEMP','TMP','PATHEXT']}
    environment.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull, PYTHONUTF8='1',
                       MYSQL_TEST_LOGIN_FILE=str(ROOT / '.runtime' / ('unused-mysql-login-' + secrets.token_hex(16))))
    return subprocess.run([str(arg) for arg in argv], cwd=cwd, env=environment, input=text,
                          text=True, encoding='utf-8', errors='strict', capture_output=capture,
                          timeout=timeout, creationflags=FLAGS)


class Fixture:
    def __init__(self, destination, mysqld, mysql):
        verify_workspace()
        self.path = new_destination(destination)
        self.server = Path(mysqld).resolve(strict=True)
        self.client = Path(mysql).resolve(strict=True)
        self.admin = self.client.with_name('mysqladmin.exe' if os.name == 'nt' else 'mysqladmin')
        self.process = None

    def query(self, sql):
        result = execute([self.client, '--defaults-file=' + str(self.path / 'client.cnf'), '--batch', '--raw', '--skip-column-names'], text=sql, timeout=5)
        if result.returncode:
            raise RuntimeError('Owned MySQL fixture query failed; no credentials or SQL are printed')
        return result.stdout.strip()

    def start(self):
        version = execute([self.server, '--no-defaults', '--version'])
        if version.returncode or not re.search(r'\bVer 8\.0\.\d+', version.stdout):
            raise ValueError('This acceptance runner requires an installed MySQL 8.0 binary; it never installs or upgrades tools')
        help_result = execute([self.client, '--no-defaults', '--help'])
        if help_result.returncode or '--defaults-file' not in help_result.stdout:
            raise ValueError('MySQL client must support an explicit private option file')
        self.path.mkdir(parents=True)
        self.manifest = {'id': str(uuid.uuid4()), 'state': 'INITIALIZING', 'serverVersion': version.stdout.strip(), 'sourceCommit': PIN, 'policy': 'PROJECT_ONLY_NEW_DATA_NO_SERVICE'}
        self.save_manifest()
        (self.path / 'files').mkdir()
        self.log = (self.path / 'server.log').open('xb')
        common = ['--no-defaults', '--basedir=' + str(self.server.parent.parent), '--datadir=' + str(self.path / 'data'), '--secure-file-priv=' + str(self.path / 'files')]
        initialized = subprocess.run([str(self.server), *common, '--initialize-insecure', '--console'], cwd=self.path,
                                     stdout=self.log, stderr=subprocess.STDOUT, timeout=120, creationflags=FLAGS)
        if initialized.returncode:
            raise RuntimeError('Fixture initialization failed; retained data must be inspected before any retry')
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            self.port = listener.getsockname()[1]
        root_password = secrets.token_hex(24)
        application_password = secrets.token_hex(24)
        reader_password = secrets.token_hex(24)
        (self.path / 'bootstrap.sql').write_text("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + root_password + "';\n", encoding='utf-8')
        (self.path / 'client.cnf').write_text(f'[client]\nuser=root\npassword={root_password}\nhost=127.0.0.1\nport={self.port}\nprotocol=TCP\ndefault-character-set=utf8mb4\n', encoding='utf-8')
        self.process = subprocess.Popen([str(self.server), *common, '--console', '--bind-address=127.0.0.1', '--port=' + str(self.port), '--mysqlx=OFF', '--init-file=' + str(self.path / 'bootstrap.sql'), '--pid-file=' + str(self.path / 'server.pid')], cwd=self.path, stdout=self.log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        self.manifest.update(state='STARTING', pid=self.process.pid, port=self.port)
        self.save_manifest()
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError('Owned MySQL process stopped during startup; inspect the project server.log')
            try:
                self.require_identity()
                break
            except (RuntimeError, subprocess.TimeoutExpired):
                time.sleep(0.1)
        else:
            raise RuntimeError('MySQL startup outcome is unknown; inspect manifest and process before retrying')
        self.query(f"CREATE DATABASE article_source CHARACTER SET utf8mb4; CREATE DATABASE article_target CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE USER 'article_app'@'127.0.0.1' IDENTIFIED BY '{application_password}'; CREATE USER 'article_reader'@'127.0.0.1' IDENTIFIED BY '{reader_password}'; GRANT SELECT,INSERT,UPDATE,DELETE ON article_target.* TO 'article_app'@'127.0.0.1'; GRANT SELECT ON article_source.* TO 'article_reader'@'127.0.0.1';")
        source = ROOT / 'samples/workspaces/springmvc-blog'
        blob = execute(['git', '--no-replace-objects', '--no-lazy-fetch', '-C', source, 'show', PIN + ':Blog/src/main/resources/db/blogdb.sql'])
        if blob.returncode:
            raise RuntimeError('Pinned local source object is required; no fetch is performed')
        self.query('USE article_source; SET NAMES utf8mb4; ' + statement(blob.stdout, 'CREATE TABLE IF NOT EXISTS `posts`') + '\n' + statement(blob.stdout, 'INSERT INTO `posts`'))
        schema = (ROOT / 'samples/article-app/backend/src/main/resources/schema-mysql.sql').read_text(encoding='utf-8')
        self.query('USE article_target; ' + schema)
        suffix = '?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&allowPublicKeyRetrieval=true&sslMode=DISABLED'
        # Loopback-only disposable fixture; production profile has no implicit credentials or TLS downgrade.
        settings = f'spring.datasource.url=jdbc:mysql://127.0.0.1:{self.port}/article_target{suffix}\nspring.datasource.username=article_app\nspring.datasource.password={application_password}\nspring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver\nspring.sql.init.mode=never\narticle.fixture.source.url=jdbc:mysql://127.0.0.1:{self.port}/article_source{suffix}\narticle.fixture.source.username=article_reader\narticle.fixture.source.password={reader_password}\n'
        (self.path / 'application.properties').write_text(settings, encoding='utf-8')
        self.manifest.update(state='READY', serverPid=int((self.path/'server.pid').read_text(encoding='utf-8').strip()), sourceSqlSha256=hashlib.sha256(blob.stdout.encode('utf-8')).hexdigest(), sourceRows=int(self.query('SELECT COUNT(*) FROM article_source.posts')))
        self.save_manifest()
        return self

    def require_identity(self):
        directory, port = self.query('SELECT @@datadir, @@port').split('\t')
        if Path(directory).resolve() != (self.path / 'data').resolve() or int(port) != self.port:
            raise RuntimeError('MySQL identity does not match the owned data directory and port')

    def save_manifest(self):
        (self.path / 'manifest.json').write_text(json.dumps(self.manifest, indent=2) + '\n', encoding='utf-8')

    def close(self):
        if self.process is not None and self.process.poll() is None:
            self.require_identity()
            stopped = execute([self.admin, '--defaults-file=' + str(self.path / 'client.cnf'), 'shutdown'])
            if stopped.returncode:
                raise RuntimeError('Graceful shutdown failed; no unrelated process is terminated')
            self.process.wait(timeout=30)
        if hasattr(self, 'manifest'):
            self.manifest['state'] = 'STOPPED' if self.process is not None and self.process.returncode == 0 else 'FAILED_RETAINED'
            self.save_manifest()
        if hasattr(self, 'log'):
            self.log.close()


def verify(fixture):
    verify_workspace()
    backend = ROOT / 'samples/article-app/backend'
    maven = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
    if not maven:
        raise ValueError('An installed Maven is required')
    # Reuse the established Windows batch forwarding rules without user profiles.
    from article_sample import run
    report_directory=fixture.path/'reports'
    if report_directory.exists():
        raise RuntimeError('This fixture already has reports; inspect before retrying')
    run([maven, '-o', '-s', ROOT / 'scripts/maven-offline-settings.xml', '-Dmaven.repo.local=' + str(ROOT / 'backend/.m2-repository'),
         '-Darticle.mysql.config=' + str(fixture.path / 'application.properties'), '-Darticle.test.reports='+str(report_directory),
         '-DskipTests=false','-Dmaven.test.skip=false','-Duser.timezone=Pacific/Auckland', 'verify', '-l', fixture.path / 'maven.log'], backend)
    if not report_directory.is_dir():
        raise RuntimeError('No fresh reports were produced by this fixture run')
    reports = []
    for name in ['ArticleApiContractTest', 'ArticleDatabaseContractTest', 'MySqlEngineContractTest', 'ArticleBehaviorContractTest', 'ArticleSessionContractTest', 'ArticleLoginContractTest']:
        report=report_directory/('TEST-example.article.'+name+'.xml')
        if not report.is_file():
            raise RuntimeError('A required fresh report is missing')
        suite = ET.parse(report).getroot()
        row = {'class': name, **{key: int(suite.attrib[key]) for key in ['tests', 'failures', 'errors', 'skipped']}}
        properties={item.get('name'):item.get('value') for item in suite.findall('./properties/property')}
        if row['tests']<1 or row['failures'] or row['errors'] or row['skipped'] or properties.get('article.mysql.config')!=str(fixture.path/'application.properties'):
            raise RuntimeError('MySQL acceptance did not pass every required test')
        reports.append(row)
    result = {'scope': '10B1 owned article behavior and soft deletion; independent legacy migration and governed acceptance remain pending', 'serverVersion': fixture.manifest['serverVersion'], 'sourceCommit': PIN,
              'sourceSqlSha256': fixture.manifest['sourceSqlSha256'], 'sourceRows': fixture.manifest['sourceRows'], 'tests': reports, 'jvmZone': 'Pacific/Auckland', 'governanceDatabaseTouched': False}
    (fixture.path / 'evidence.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysqld', type=Path, required=True)
    parser.add_argument('--mysql', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    args = parser.parse_args()
    fixture = Fixture(args.destination, args.mysqld, args.mysql)
    try:
        fixture.start()
        result = verify(fixture)
    finally:
        fixture.close()
    print(json.dumps(result))


if __name__ == '__main__':
    main()
