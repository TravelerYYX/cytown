"""Resource cleanup regression tests in a disposable MySQL database. Never changes the project database.
Set DB_USERNAME/DB_PASSWORD in the process environment. Uses existing local Java 21 and Maven.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mysql', default=r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
parser.add_argument('--port', type=int, default=3306)
parser.add_argument('--maven', default=r'D:/apache-maven-3.9.16/bin/mvn.cmd')
parser.add_argument('--tests', default='ResourceCleanupIntegrationTests,PostIntegrationTests,CommentIntegrationTests,UserDeletionTests,MediaIntegrationTests,NoticeApiIntegrationTests,MineEntryIntegrationTests,AppReleaseTests,HomeLayoutCacheTests,StorageFailureTests,FtpStorageTests,StorageDeletionAdapterTests,LevelIntegrationTests')
args = parser.parse_args()
env = os.environ.copy()
if not env.get('DB_USERNAME'):
    raise SystemExit('Set DB_USERNAME and DB_PASSWORD before running')
# Test-only values; do not load project .env credentials, providers or uploads.
env.update(JAVA_HOME=r'D:/JDK/jdk21', JWT_SECRET='resource-cleanup-isolated-test-secret-1234567890',
           REDIS_ENABLED='false', CT_RESOURCE_CLEANUP_TEST='true', CIYUANTOWN_ACCOUNT_TEST_DB='true',
           MYSQL_PWD=env.get('DB_PASSWORD',''), UPLOAD_DIR=str(ROOT/'target/resource-verifier-uploads'))
database = 'ciyuantown_account_test_cleanup_' + uuid.uuid4().hex[:12]
assert re.fullmatch(r'ciyuantown_account_test_cleanup_[0-9a-f]{12}',database)
client = [args.mysql,'--host=127.0.0.1','--port='+str(args.port),'--user='+env['DB_USERNAME'],'--default-character-set=utf8mb4','--batch']
def sql(value, selected=True):
    result=subprocess.run(client+([database] if selected else []),input=value,encoding='utf-8',capture_output=True,env=env)
    if result.returncode: raise RuntimeError(result.stderr)

def migration(name):
    value=(ROOT/'doc/sql'/name).read_text(encoding='utf-8-sig')
    return re.sub(r'^[ \t]*(?:USE[ \t]+[a-zA-Z0-9_]+;|CREATE DATABASE[^;\n]+;|SOURCE[ \t]+[^;\n]+\.sql;)[ \t]*$','',value,flags=re.M|re.I)

created=False
try:
    sql(f'CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',False)
    created=True
    first=['init.sql','p1-schema.sql','p1-seed.sql','p2-schema.sql','p3-schema.sql','p4-home-news.sql','p5-publish.sql','p6-profile.sql','p7-card-background.sql','p8-official-account.sql','p9-app-user-password.sql','p10-comments.sql','p11-user-account-management.sql','p11-home-feed-layout.sql']
    rest=sorted([p.name for p in (ROOT/'doc/sql').glob('p*.sql') if int(re.match(r'p(\d+)',p.name).group(1))>=12 and 'rollback' not in p.name],key=lambda name:int(re.match(r'p(\d+)',name).group(1)))
    for name in first+rest: sql(migration(name))
    sql("UPDATE system_config SET config_value='0' WHERE config_key='delete_resource_files_enabled';")
    sql(migration('p26-resource-cleanup.sql'))
    result=subprocess.run(client+[database,'--skip-column-names','-e',"SELECT config_value FROM system_config WHERE config_key='delete_resource_files_enabled'"],encoding='utf-8',capture_output=True,env=env,check=True)
    assert result.stdout.strip()=='0','Repeated migration overwrote switch'
    sql("UPDATE system_config SET config_value='1' WHERE config_key='delete_resource_files_enabled';")
    env['DB_URL']=f'jdbc:mysql://127.0.0.1:{args.port}/{database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
    env['SPRING_DATASOURCE_URL']=env['DB_URL']
    print('Migrated isolated database '+database,flush=True)
    subprocess.run([args.maven,'-q','-Dtest='+args.tests,'test'],cwd=ROOT,env=env,check=True)
finally:
    if created:
        sql(f'DROP DATABASE `{database}`;',False)
        print('Removed isolated database '+database,flush=True)
