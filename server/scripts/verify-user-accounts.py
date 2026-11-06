"""Verify P11 and account integration tests in a disposable local MySQL database.

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
parser.add_argument('--tests', default='ChannelPublishAccessTests,UserAccountManagementTests,JwtAudienceTests,AccountSessionTests,UserDeletionTests,PostAuthorNicknameTests',
                    help='Comma-separated Maven test classes to run in the disposable database')
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
    channel_migration=source('p13-channel-admin-only.sql')
    sql(channel_migration)
    assert sql('SELECT COUNT(*) FROM home_channel WHERE admin_only=TRUE').stdout.strip() == '0'
    sql('UPDATE home_channel SET admin_only=TRUE WHERE id=(SELECT id FROM (SELECT MIN(id) AS id FROM home_channel) seed)')
    sql(channel_migration)
    assert sql('SELECT COUNT(*) FROM home_channel WHERE admin_only=TRUE').stdout.strip() == '1'
    sql('UPDATE home_channel SET admin_only=FALSE')
    sql(source('p14-user-soft-delete.sql'))
    sql(source('p15-mine-entry.sql'))
    sql(source('p21-entry-target.sql'))
    scope_migration = source('p22-channel-feed-scope.sql')
    sql(scope_migration)
    assert sql("SELECT CONCAT(show_all_posts,':',allow_publish) FROM home_channel WHERE name='发现' LIMIT 1").stdout.strip() == '1:0'
    assert sql("SELECT COUNT(*) FROM home_channel WHERE name<>'发现' AND allow_publish=FALSE").stdout.strip() == '0'
    sql("UPDATE home_channel SET show_all_posts=FALSE,allow_publish=TRUE WHERE name='发现'")
    sql(scope_migration)
    assert sql("SELECT CONCAT(show_all_posts,':',allow_publish) FROM home_channel WHERE name='发现' LIMIT 1").stdout.strip() == '0:1'
    sql("UPDATE home_channel SET show_all_posts=TRUE,allow_publish=FALSE WHERE name='发现'")
    print('Channel feed migration verified: discovery seeded; existing permissions and later admin edits preserved.', flush=True)
    sql(source('p18-avatar-frame.sql'))
    sql("UPDATE app_user SET avatar_frame='star-sea' WHERE qq_number='91234567890'")
    sql(source('p18-avatar-frame.sql'))
    assert sql("SELECT avatar_frame FROM app_user WHERE qq_number='91234567890'").stdout.strip() == 'star-sea'
    sql("UPDATE app_user SET level=5 WHERE qq_number='91234567890'")
    level_migration=source('p16-community-level.sql')
    sql(level_migration)
    assert sql("SELECT experience FROM app_user WHERE qq_number='91234567890'").stdout.strip() == '580'
    sql("UPDATE app_user SET experience=777 WHERE qq_number='91234567890'")
    sql(level_migration)
    assert sql("SELECT experience FROM app_user WHERE qq_number='91234567890'").stdout.strip() == '777'
    config_migration=source('p17-community-level-config.sql')
    sql(config_migration)
    assert sql("SELECT JSON_LENGTH(config_json,'$.levels') FROM community_level_config WHERE id=1").stdout.strip() == '60'
    sql("UPDATE community_level_config SET version=2 WHERE id=1")
    sql(config_migration)
    assert sql("SELECT version FROM community_level_config WHERE id=1").stdout.strip() == '2'
    sql("UPDATE community_level_config SET version=0 WHERE id=1")
    sql("UPDATE app_user SET last_login_time='2026-10-20 23:59:59' WHERE qq_number='91234567890'")
    activity_migration=source('p19-user-activity.sql')
    sql(activity_migration)
    assert sql("SELECT CONCAT(active_days,':',last_active_date) FROM app_user WHERE qq_number='91234567890'").stdout.strip() == '1:2026-10-20'
    sql("UPDATE app_user SET active_days=3,last_active_date='2026-10-22' WHERE qq_number='91234567890'")
    sql(activity_migration)
    assert sql("SELECT CONCAT(active_days,':',last_active_date) FROM app_user WHERE qq_number='91234567890'").stdout.strip() == '3:2026-10-22'
    print('Activity migration verified: known login day seeded; reruns preserve accumulated days.', flush=True)
    adjustment_migration = source('p20-experience-adjustment.sql')
    sql("INSERT INTO user_level_ledger(user_id,action,event_key,amount,earned_date) SELECT id,'comment','migration-reward-fixture',2,CURRENT_DATE FROM app_user WHERE qq_number='91234567890'")
    sql(adjustment_migration)
    assert sql("SELECT CONCAT(amount,':',IF(reason IS NULL,'legacy','bad')) FROM user_level_ledger WHERE event_key='migration-reward-fixture'").stdout.strip() == '2:legacy'
    sql("INSERT INTO user_level_ledger(user_id,action,event_key,amount,earned_date,operator_id,reason,before_experience,after_experience,before_level,after_level) SELECT id,'admin_adjust','migration-adjustment-fixture',5,CURRENT_DATE,1,'migration fixture',772,777,5,5 FROM app_user WHERE qq_number='91234567890'")
    sql(adjustment_migration)
    assert sql("SELECT CONCAT(amount,':',reason,':',before_experience,':',after_experience) FROM user_level_ledger WHERE event_key='migration-adjustment-fixture'").stdout.strip() == '5:migration fixture:772:777'
    assert sql("SELECT experience FROM app_user WHERE qq_number='91234567890'").stdout.strip() == '777'
    sql("DELETE FROM user_level_ledger WHERE event_key IN ('migration-adjustment-fixture','migration-reward-fixture')")
    print('Adjustment migration verified: reruns preserve audit records and user balances.', flush=True)
    print('Level migration verified: legacy level retained; reruns preserve experience and saved config.', flush=True)
    assert sql('SELECT COUNT(*) FROM app_user WHERE deleted=TRUE').stdout.strip() == '0'
    assert sql('SELECT COUNT(*) FROM admin_user WHERE deleted=TRUE').stdout.strip() == '0'
    print('Channel migration verified: default off; rerun preserves restrictions.', flush=True)
    assert sql("SELECT COUNT(*) FROM app_user u JOIN admin_user a ON u.official_admin_id=a.id WHERE a.username='cyt9001' AND u.qq_number IS NULL").stdout.strip() == '1'
    assert sql("SELECT COUNT(*) FROM login_account WHERE account IN ('cyt9001','91234567890')").stdout.strip() == '2'
    print('Migration verified: conflicts stop before DDL; fixed official linkage and legacy QQ registry preserved.', flush=True)
    sql(source('p23-checkin.sql'))
    sql(source('p23-checkin.sql'))
    assert sql("SELECT JSON_LENGTH(config_json, '$.days') FROM checkin_config WHERE id=1").stdout.strip() == '31'
    print('Check-in migration verified: 31-day template initialized and rerun preserved.', flush=True)
    for name in ['p24-app-update.sql','p25-notice-delivery.sql','p26-resource-cleanup.sql','p27-chat.sql','p28-chat-admin.sql','p29-chat-reply.sql']:
        sql(source(name))
    sql(source('p30-user-profile-location.sql'))
    sql(source('p30-user-profile-location.sql'))
    sql("""
      INSERT INTO app_user(email,nickname) VALUES('social-history-owner@test.local','历史接收人'),('social-history-actor@test.local','历史互动者');
      SET @history_owner=(SELECT id FROM app_user WHERE email='social-history-owner@test.local');
      SET @history_actor=(SELECT id FROM app_user WHERE email='social-history-actor@test.local');
      INSERT INTO post(author_id,author_nickname,title,status,content_blocks,create_time)
        VALUES(@history_owner,'历史接收人','历史帖子',1,NULL,'2000-01-01');
      SET @history_post=LAST_INSERT_ID();
      INSERT INTO `comment`(post_id,author_id,author_nickname,content,create_time)
        VALUES(@history_post,@history_actor,'历史互动者','历史评论','2000-01-01');
      SET @history_comment=LAST_INSERT_ID();
      INSERT INTO user_like(user_id,post_id,create_time) VALUES(@history_actor,@history_post,'2000-01-01');
      INSERT INTO comment_reaction(comment_id,user_id,reaction,create_time) VALUES(@history_comment,@history_owner,'like','2000-01-01');
      INSERT INTO user_follow(follower_id,followee_id,create_time) VALUES(@history_actor,@history_owner,'2000-01-01');
      INSERT INTO post(author_id,author_nickname,title,status,content_blocks,create_time)
        VALUES(@history_actor,'历史互动者','历史提及',1,JSON_ARRAY(JSON_OBJECT('type','paragraph','runs',JSON_ARRAY(JSON_OBJECT('type','mention','userId',CAST(@history_owner AS CHAR))))),'2000-01-01');
    """)
    sql(source('p31-social-inbox.sql'))
    social_count = sql('SELECT COUNT(*) FROM social_notification').stdout.strip()
    sql(source('p31-social-inbox.sql'))
    assert sql('SELECT COUNT(*) FROM social_notification').stdout.strip() == social_count
    assert sql('SELECT COUNT(*) FROM social_notification WHERE read_at IS NULL').stdout.strip() == '0'
    assert sql("SELECT COUNT(DISTINCT type) FROM social_notification WHERE actor_id IN (SELECT id FROM app_user WHERE email LIKE 'social-history-%@test.local')").stdout.strip() == '5'
    print('Social inbox migration verified: repeatable backfill is silent.', flush=True)
    env['CIYUANTOWN_ACCOUNT_TEST_DB'] = 'true'
    env['DB_URL'] = f'jdbc:mysql://127.0.0.1:{port}/{database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
    env['SPRING_DATASOURCE_URL'] = env['DB_URL']
    env['REDIS_ENABLED'] = 'false'
    result = subprocess.run([args.maven, '-q', '-Dtest=' + args.tests, 'test'], cwd=ROOT, env=env)
    if result.returncode:
        raise SystemExit(result.returncode)
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
