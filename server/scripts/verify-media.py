"""Verify media storage, HLS, image variants and account regression tests in a disposable local MySQL database.

Requires the existing MySQL client and Maven; no Python dependencies.
Example: python scripts/verify-user-accounts.py --mysql PATH --maven PATH
Credentials are read from the backend .env or process environment, never printed.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import socket
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mysql', default=shutil.which('mysql'))
parser.add_argument('--maven', default=shutil.which('mvn'))
parser.add_argument('--mysqld', help='Start a disposable MySQL server with an empty test-only root password')
parser.add_argument('--cleanup-only',action='store_true',help='Run the isolated migration and cleanup verifier without repeating Java tests')
parser.add_argument('--tests',default='MediaIntegrationTests,ImageProcessServiceTests,PostIntegrationTests,CommentIntegrationTests,P2IntegrationTests,UserAccountManagementTests,JwtAudienceTests,AccountSessionTests')
args = parser.parse_args()
if not args.mysql or not args.maven:
    parser.error('Specify --mysql and --maven or add both to PATH')
env = os.environ.copy()
for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        env.setdefault(key.strip(), value.strip())
url = env.get('DB_URL', 'jdbc:mysql://127.0.0.1:3306/ciyuantown')
match = re.match(r'jdbc:mysql://(localhost|127\.0\.0\.1)(?::(\d+))?/', url)
if not match:
    raise SystemExit('This verifier only runs on a local MySQL server')
port = match.group(2) or '3306'
server = None
if args.mysqld:
    env['DB_USERNAME'] = 'root'
    env['DB_PASSWORD'] = ''
    env['JWT_SECRET'] = 'account-test-signing-key-only-never-for-production-1234567890'
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = str(listener.getsockname()[1])
    data = ROOT / 'target' / ('account-mysql-' + uuid.uuid4().hex[:12])
    data.mkdir(parents=True)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    subprocess.run([args.mysqld, '--no-defaults', '--initialize-insecure', '--datadir=' + str(data)],
                   check=True, capture_output=True, creationflags=flags)
    log = (data / 'server.log').open('w', encoding='utf-8')
    server = subprocess.Popen([args.mysqld, '--no-defaults', '--datadir=' + str(data),
        '--port=' + port, '--bind-address=127.0.0.1', '--mysqlx=OFF', '--console'],
        stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
    ready = False
    for _ in range(100):
        if server.poll() is not None: break
        with socket.socket() as probe:
            probe.settimeout(.1)
            if probe.connect_ex(('127.0.0.1', int(port))) == 0:
                ready = True; break
        time.sleep(.2)
    if not ready:
        server.terminate(); server.wait(timeout=15); log.close()
        raise SystemExit('Disposable MySQL failed to start; inspect ' + str(data / 'server.log'))
if not env.get('DB_USERNAME'):
    raise SystemExit('Set DB_USERNAME/DB_PASSWORD or use --mysqld for an isolated server')
env['MYSQL_PWD'] = env.get('DB_PASSWORD', '')
database = 'ciyuantown_account_test_' + uuid.uuid4().hex[:12]
assert re.fullmatch(r'ciyuantown_account_test_[0-9a-f]{12}', database)
client = [args.mysql, '--host=127.0.0.1', '--port=' + port,
          '--user=' + env['DB_USERNAME'], '--default-character-set=utf8mb4', '--batch', '--skip-column-names']

def sql(script, in_database=True, expect_success=True):
    result = subprocess.run(client + ([database] if in_database else []), input=script,
                            encoding='utf-8', capture_output=True, env=env)
    if expect_success and result.returncode:
        raise RuntimeError(result.stderr)
    return result

def source(name):
    content = (ROOT / 'doc/sql' / name).read_text(encoding='utf-8-sig')
    # Never let an old migration's USE/SOURCE switch into the project database.
    content = re.sub(r'^[ \t]*(?:USE[ \t]+[a-zA-Z0-9_]+;|CREATE DATABASE[^;\n]+;|SOURCE[ \t]+[^;\n]+\.sql;)[ \t]*$', '', content, flags=re.M | re.I)
    return content

created = False
try:
    sql(f'CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;', False)
    created = True
    for name in ['init.sql', 'p1-schema.sql', 'p2-schema.sql', 'p3-schema.sql', 'p4-home-news.sql',
                 'p5-publish.sql', 'p6-profile.sql', 'p7-card-background.sql', 'p8-official-account.sql',
                 'p9-app-user-password.sql', 'p10-comments.sql']:
        sql(source(name))
    sql("""
      INSERT INTO admin_user(username,password,nickname,user_type) VALUES('cyt9001','migration-fixture','官方迁移',1);
      INSERT INTO app_user(qq_number,email,nickname) VALUES('cyt9001','cyt9001@official.local','社区迁移'),('91234567890','91234567890@qq.com','普通迁移');
      INSERT INTO admin_user(username,password,nickname,user_type) VALUES('91234567890','migration-fixture','冲突夹具',1);
    """)
    migration = source('p11-user-account-management.sql')
    result = sql(migration, expect_success=False)
    assert result.returncode != 0 and 'conflict' in (result.stdout + result.stderr).lower()
    assert sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='username'").stdout.strip() == '0'
    sql("DELETE FROM admin_user WHERE username='91234567890'")
    sql(migration)
    sql(source('p12-media-storage.sql'))
    sql(source('p14-user-soft-delete.sql'))
    sql(source('p13-channel-admin-only.sql'))
    assert sql("SELECT COUNT(*) FROM app_user u JOIN admin_user a ON u.official_admin_id=a.id WHERE a.username='cyt9001' AND u.qq_number IS NULL").stdout.strip() == '1'
    assert sql("SELECT COUNT(*) FROM login_account WHERE account IN ('cyt9001','91234567890')").stdout.strip() == '2'
    print('Migration verified: conflicts stop before DDL; fixed official linkage and legacy QQ registry preserved.', flush=True)
    env['CIYUANTOWN_ACCOUNT_TEST_DB'] = 'true'
    env['DB_URL'] = f'jdbc:mysql://127.0.0.1:{port}/{database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
    env['SPRING_DATASOURCE_URL'] = env['DB_URL']
    env['REDIS_ENABLED'] = 'false'
    if not args.cleanup_only:
        result = subprocess.run([args.maven, '-q', '-Dtest='+args.tests, 'test'], cwd=ROOT, env=env)
        if result.returncode:
            raise SystemExit(result.returncode)
    from importlib.util import spec_from_file_location, module_from_spec
    cleanup_spec=spec_from_file_location('verify_cleanup', ROOT/'scripts/verify-history-cleanup.py')
    cleanup_module=module_from_spec(cleanup_spec);cleanup_spec.loader.exec_module(cleanup_module)
    cleanup_module.verify(args.mysql,str(Path(args.mysql).with_name('mysqldump.exe' if os.name=='nt' else 'mysqldump')),env)
finally:
    if created:
        # Drop only the exact, generated temporary database; never the project database.
        assert re.fullmatch(r'ciyuantown_account_test_[0-9a-f]{12}', database)
        sql(f'DROP DATABASE `{database}`;', False)
        print('Disposable test database removed.', flush=True)
    if server is not None:
        server.terminate()
        server.wait(timeout=30)
        log.close()
        print('Disposable MySQL server stopped.', flush=True)
