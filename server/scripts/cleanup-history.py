"""One-time, resumable historical cleanup. Stop the backend before prepare/apply.

prepare creates a full SQL backup, copies target files and writes manifest.json.
apply requires that manifest, verifies backups, and deletes only its recorded IDs.
No schema migration invokes this tool. Credentials come from .env/environment.
Cloud cleanup additionally requires Python cryptography for AES-GCM decryption.
"""
import argparse
import base64
from datetime import datetime, timezone
import email.utils
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import urllib.parse
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
HISTORY = {'post', 'post_media', 'post_tag', 'post_topic', 'comment', 'comment_media',
           'comment_reaction', 'post_poll', 'post_poll_option', 'post_poll_vote',
           'user_like', 'user_favorite', 'upload_asset', 'post_draft', 'media_job'}
MEDIA_AREAS = ('posts', 'comments', 'downloads')


def atomic_json(path, value):
    temporary = path.with_suffix('.part')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def within(root, key):
    # Resolve symlinks/junctions too; never recursively remove an upload directory.
    if not key or '\\' in key or ':' in key or key.startswith('/') or '..' in key.split('/'):
        raise ValueError('Unsafe object key')
    root = Path(root).resolve()
    candidate = (root / key).resolve()
    if candidate == root or not candidate.is_relative_to(root):
        raise ValueError('Object escapes its configured storage directory')
    return candidate


class Cleanup:
    def __init__(self, args):
        self.args = args
        self.env = os.environ.copy()
        for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                self.env.setdefault(key.strip(), value.strip())
        match = re.match(r'jdbc:mysql://([^/:?]+)(?::(\d+))?/([a-zA-Z0-9_]+)', self.env.get('DB_URL', 'jdbc:mysql://127.0.0.1:3306/ciyuantown'))
        if not match or not self.env.get('DB_USERNAME'):
            raise ValueError('Configure DB_URL and DB_USERNAME')
        self.host, self.port, self.database = match.group(1), match.group(2) or '3306', match.group(3)
        self.env['MYSQL_PWD'] = self.env.get('DB_PASSWORD', '')
        self.client = [args.mysql, '--host=' + self.host, '--port=' + self.port,
                       '--user=' + self.env['DB_USERNAME'], '--default-character-set=utf8mb4',
                       '--batch', '--raw', '--skip-column-names', self.database]
        upload = Path(self.env.get('UPLOAD_DIR', './uploads'))
        self.upload = (upload if upload.is_absolute() else ROOT / upload).resolve()
        self.tables = {row['table_name'] for row in self.query(
            "SELECT JSON_OBJECT('table_name',table_name) FROM information_schema.tables WHERE table_schema=DATABASE()")}
        self.columns = {}
        for row in self.query("SELECT JSON_OBJECT('table_name',table_name,'column_name',column_name) "
                              "FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY ordinal_position"):
            self.columns.setdefault(row['table_name'], []).append(row['column_name'])

    def sql(self, statement):
        result = subprocess.run(self.client, input=statement, capture_output=True,
                                encoding='utf-8', env=self.env, check=True)
        return result.stdout

    def query(self, statement):
        return [json.loads(line) for line in self.sql(statement).splitlines() if line]

    def rows(self, table):
        if table not in self.tables:
            return []
        fields = ','.join("'%s',`%s`" % (column, column) for column in self.columns[table])
        return self.query('SELECT JSON_OBJECT(' + fields + ') FROM `' + table + '`')

    def retained_text(self):
        # Search every retained table, including JSON blocks and configuration values.
        retained = self.tables - HISTORY - {'media_object', 'media_storage_config'}
        return '\n'.join(json.dumps(self.rows(table), ensure_ascii=False) for table in sorted(retained))

    def profile(self, storage_id):
        return next(json.loads(row['config_json']) for row in self.rows('media_storage_config') if row['id'] == storage_id)

    def local_path(self, item):
        if item.get('objectId'):
            config = self.profile(item['storageId'])
            if config['provider'] != 'local':
                return None
            return within(config['localDir'], item['key'])
        return within(self.upload, item['key'])

    def prepare(self, folder):
        folder = folder.resolve()
        if folder.is_relative_to(self.upload):
            raise ValueError('Backup must be outside the upload directory')
        folder.mkdir(parents=True, exist_ok=False)
        (folder / 'files').mkdir()
        snapshot = {table: self.rows(table) for table in sorted(HISTORY & self.tables)}
        retained = self.retained_text()
        text = json.dumps(snapshot, ensure_ascii=False)
        candidates = {}
        objects=self.rows('media_object')
        for row in objects:
            key = row['object_key']
            if key.split('/')[0] in MEDIA_AREAS or row['public_url'] in text:
                candidates[row['public_url']] = dict(url=row['public_url'], key=key, objectId=row['id'], storageId=row['storage_id'])
        # Known media-only directories also contain old unregistered derivatives/orphans.
        for area in MEDIA_AREAS:
            directory = self.upload / area
            if directory.exists():
                for path in directory.rglob('*'):
                    if path.is_file():
                        key = path.relative_to(self.upload).as_posix()
                        within(self.upload, key)
                        url = '/uploads/' + key
                        candidates.setdefault(url, dict(url=url, key=key))
        for url in re.findall(r'/uploads/[^\s"<>\\]+', text):
            url = urllib.parse.unquote(url.replace('\\/', '/'))
            if url.startswith('/uploads/storage/'):
                continue
            key = url.removeprefix('/uploads/')
            within(self.upload, key)
            candidates.setdefault(url, dict(url=url, key=key))
        items, preserved = [], []
        for item in candidates.values():
            if item['url'] in retained:
                preserved.append(item['url'])
                continue
            # A retained local object may use the storage URL for the same physical file.
            source = self.local_path(item)
            aliases = [row['public_url'] for row in objects if row['object_key'] == item['key']]
            if any(alias in retained for alias in aliases):
                preserved.append(item['url'])
                continue
            name = hashlib.sha256(item['url'].encode()).hexdigest()
            target = folder / 'files' / name
            item['backup'] = 'files/' + name
            if source is not None:
                item['path'] = str(source)
                if source.exists():
                    shutil.copy2(source, target)
                else:
                    item['missing'] = True
            else:
                urllib.request.urlretrieve(item['url'], target)
            if target.exists():
                item['sha256'] = digest(target)
                item['bytes'] = target.stat().st_size
            items.append(item)
        dump = [self.args.mysqldump, '--host=' + self.host, '--port=' + self.port,
                '--user=' + self.env['DB_USERNAME'], '--single-transaction', '--no-tablespaces',
                '--skip-lock-tables', '--hex-blob', '--default-character-set=utf8mb4', self.database]
        with (folder / 'database.sql').open('wb') as output:
            subprocess.run(dump, stdout=output, stderr=subprocess.PIPE, env=self.env, check=True)
        manifest = dict(version=1, database=self.database, host=self.host, port=self.port,
                        created=datetime.now(timezone.utc).isoformat(), uploadRoot=str(self.upload),
                        sqlSha256=digest(folder / 'database.sql'), snapshot=snapshot,
                        objects=items, preserved=preserved)
        atomic_json(folder / 'manifest.json', manifest)
        print(json.dumps(dict(manifest=str(folder / 'manifest.json'), counts={t: len(r) for t,r in snapshot.items()},
                              objects=len(items), preserved=len(preserved)), ensure_ascii=False))

    def decrypt(self, value):
        if not value:
            return ''
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        iv, body = value.split(':')
        key = hashlib.sha256(self.env.get('MEDIA_CONFIG_KEY', self.env['JWT_SECRET']).encode()).digest()
        return AESGCM(key).decrypt(base64.b64decode(iv), base64.b64decode(body), None).decode()

    def cloud_delete(self, item):
        config = self.profile(item['storageId'])
        access, secret = self.decrypt(config['accessKey']), self.decrypt(config['secretKey'])
        if config['provider'] == 'oss':
            date = email.utils.formatdate(usegmt=True)
            resource = '/' + config['bucket'] + '/' + item['key']
            signature = base64.b64encode(hmac.new(secret.encode(), ('DELETE\n\n\n' + date + '\n' + resource).encode(), hashlib.sha1).digest()).decode()
            endpoint = urllib.parse.urlsplit(config['endpoint'])
            url = endpoint.scheme + '://' + config['bucket'] + '.' + endpoint.netloc + '/' + urllib.parse.quote(item['key'], safe='/')
            request = urllib.request.Request(url, method='DELETE', headers={'Date':date, 'Authorization':'OSS ' + access + ':' + signature})
        elif config['provider'] == 'qiniu':
            encoded = base64.urlsafe_b64encode((config['bucket'] + ':' + item['key']).encode()).decode()
            route = '/delete/' + encoded
            signature = base64.urlsafe_b64encode(hmac.new(secret.encode(), (route + '\n').encode(), hashlib.sha1).digest()).decode()
            request = urllib.request.Request('https://rs.qiniuapi.com' + route, data=b'', method='POST', headers={'Authorization':'QBox ' + access + ':' + signature})
        else:
            raise ValueError('Unsupported storage provider')
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                response.read()
        except urllib.error.HTTPError as error:
            if error.code not in (404, 612):
                raise

    def clear_redis(self):
        if self.env.get('REDIS_ENABLED', 'true').lower() == 'false':
            return
        with socket.create_connection((self.env.get('REDIS_HOST', '127.0.0.1'), int(self.env.get('REDIS_PORT', '6379'))), timeout=5) as connection:
            stream = connection.makefile('rb')
            def read():
                prefix, line = stream.read(1), stream.readline().rstrip(b'\r\n')
                if prefix == b'-': raise RuntimeError('Redis command failed')
                if prefix == b'*': return [read() for _ in range(int(line))]
                if prefix == b'$':
                    if int(line) < 0: return None
                    value = stream.read(int(line)); stream.read(2); return value
                return line
            def command(*parts):
                data = [str(part).encode() if not isinstance(part, bytes) else part for part in parts]
                connection.sendall(b'*%d\r\n' % len(data) + b''.join(b'$%d\r\n' % len(p) + p + b'\r\n' for p in data))
                return read()
            if self.env.get('REDIS_PASSWORD'): command('AUTH', self.env['REDIS_PASSWORD'])
            command('SELECT', self.env.get('REDIS_DATABASE', '0'))
            for pattern in ('ct:feed:*', 'ct:post-list:*', 'ct:post-detail:*', 'ct:home-config:*', 'ct:like:*', 'ct:fav:*', 'ct:user:profile:*'):
                cursor = b'0'
                while True:
                    cursor, keys = command('SCAN', cursor, 'MATCH', pattern, 'COUNT', 200)
                    if keys: command('DEL', *keys)
                    if cursor == b'0': break

    def apply(self, manifest_path):
        folder = manifest_path.resolve().parent
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        if manifest['version'] != 1 or (manifest['database'],manifest['host'],manifest['port']) != (self.database,self.host,self.port):
            raise ValueError('Manifest database does not match the configured database')
        if manifest['uploadRoot'] != str(self.upload) or digest(folder / 'database.sql') != manifest['sqlSha256']:
            raise ValueError('Backup verification failed')
        progress_path = folder / 'progress.json'
        progress = json.loads(progress_path.read_text(encoding='utf-8')) if progress_path.exists() else dict(files=[], database=False, redis=False)
        retained = self.retained_text()
        # Recheck every backup and every live source before any deletion occurs.
        for item in manifest['objects']:
            if item['url'] in retained:
                raise ValueError('A retained resource now references a cleanup object; prepare a new manifest')
            if item.get('missing'): continue
            backup = within(folder, item['backup'])
            if digest(backup) != item['sha256']: raise ValueError('File backup verification failed')
            source = self.local_path(item)
            if source is not None and str(source) != item['path']: raise ValueError('Storage directory changed')
            if source is not None and source.exists() and digest(source) != item['sha256']:
                raise ValueError('Source changed after backup; prepare a new manifest')
        if not progress['database']:
            # Stop if any snapshotted row changed since prepare; newly created rows are not deleted.
            for table, records in manifest['snapshot'].items():
                keys = ['id'] if 'id' in self.columns[table] else (['asset_id'] if table == 'media_job' else [key for key in self.columns[table] if key.endswith('_id')])
                identity = lambda row: tuple(row[key] for key in keys)
                current = {identity(row): row for row in self.rows(table)}
                if any(identity(row) in current and current[identity(row)] != row for row in records):
                    raise ValueError('Historical records changed after backup; prepare a new manifest')
            posts = [str(row['id']) for row in manifest['snapshot'].get('post', [])]
            statement = ['START TRANSACTION;']
            if posts:
                statement.append('UPDATE notice SET post_id=NULL WHERE post_id IN (' + ','.join(posts) + ');')
            # Children first. Every delete uses IDs from the manifest, never all current rows.
            for table in ('post_poll_vote','post_poll_option','post_poll','comment_reaction','comment_media','comment',
                          'post_topic','post_tag','post_media','user_like','user_favorite','post','media_job','post_draft','upload_asset'):
                records = manifest['snapshot'].get(table, [])
                if not records: continue
                if 'id' in self.columns[table]:
                    conditions = ['id=' + str(int(row['id'])) for row in records]
                elif table == 'media_job': conditions = ['asset_id=' + str(int(row['asset_id'])) for row in records]
                else:
                    conditions = [' AND '.join('`'+key+'`='+str(int(row[key])) for key in self.columns[table] if key.endswith('_id')) for row in records]
                for start in range(0, len(conditions), 200):
                    statement.append('DELETE FROM `' + table + '` WHERE ' + ' OR '.join('('+v+')' for v in conditions[start:start+200]) + ';')
            statement.append('UPDATE topic t SET post_count=(SELECT COUNT(*) FROM post_topic p WHERE p.topic_id=t.id);') if 'post_count' in self.columns.get('topic', []) else None
            statement.append('UPDATE notice n SET post_id=NULL WHERE post_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM post p WHERE p.id=n.post_id);')
            statement.append('COMMIT;')
            self.sql('\n'.join(statement))
            progress['database'] = True
            atomic_json(progress_path, progress)
        for item in manifest['objects']:
            if item['url'] in progress['files']: continue
            source = self.local_path(item)
            if source is not None:
                source.unlink(missing_ok=True)
            elif not item.get('missing'):
                self.cloud_delete(item)
            if item.get('objectId'):
                object_id = item['objectId']
                if not re.fullmatch('[a-f0-9-]{36}',object_id): raise ValueError('Invalid object ID')
                self.sql("DELETE FROM media_object WHERE id='" + object_id + "';")
            progress['files'].append(item['url'])
            atomic_json(progress_path, progress)
        if not progress['redis']:
            self.clear_redis()
            progress['redis'] = True
            atomic_json(progress_path, progress)
        print('Historical cleanup complete. Backups and resumable progress: ' + str(folder))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'apply'])
    parser.add_argument('path', type=Path, help='New backup directory for prepare; manifest.json for apply')
    parser.add_argument('--mysql', default=shutil.which('mysql'))
    parser.add_argument('--mysqldump', default=shutil.which('mysqldump'))
    args = parser.parse_args()
    if not args.mysql or (args.action == 'prepare' and not args.mysqldump): parser.error('Specify MySQL executable paths')
    cleanup = Cleanup(args)
    cleanup.prepare(args.path) if args.action == 'prepare' else cleanup.apply(args.path)


if __name__ == '__main__': main()
