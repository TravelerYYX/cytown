"""Restore full-frame local automatic video covers; dry run unless --apply is passed.

Original covers stay on disk. A manifest records old URLs/versions for rollback.
Credentials come from .env/environment, as in the existing maintenance scripts.
"""
import argparse
import importlib.util
import json
import math
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mysql', required=True)
parser.add_argument('--apply', action='store_true')
parser.add_argument('--backup', type=Path)
args = parser.parse_args()
if args.apply and not args.backup:
    parser.error('--apply requires --backup for the rollback manifest')
spec = importlib.util.spec_from_file_location('history', ROOT / 'scripts/cleanup-history.py')
history = importlib.util.module_from_spec(spec)
spec.loader.exec_module(history)
client = history.Cleanup(argparse.Namespace(mysql=args.mysql, mysqldump=None))


def sql_text(value):
    return "CONVERT(0x" + str(value).encode('utf-8').hex() + " USING utf8mb4) COLLATE utf8mb4_unicode_ci" if value else "''"


def dimensions(path):
    result = subprocess.run([client.env['FFPROBE_PATH'], '-v', 'error', '-select_streams', 'v:0',
                             '-show_entries', 'stream=width,height:stream_side_data=rotation:stream_tags=rotate',
                             '-of', 'json', str(path)], check=True, capture_output=True, text=True, timeout=15)
    stream = json.loads(result.stdout)['streams'][0]
    width, height = stream['width'], stream['height']
    rotation = float(stream.get('tags', {}).get('rotate', 0))
    for side in stream.get('side_data_list', []):
        rotation = float(side.get('rotation', rotation))
    return (height, width) if round(abs(rotation) / 90) % 2 else (width, height)


objects = {row['public_url']: row for row in client.rows('media_object')}
settings = client.query("SELECT JSON_OBJECT('value',config_value) FROM system_config WHERE config_key='media_processing'")
images = json.loads(settings[0]['value'])['images'] if settings else {'coverMaxEdge': 1280, 'coverQuality': 78}
repairs = []
for asset in client.query("SELECT JSON_OBJECT('id',id,'originalUrl',original_url,'coverUrl',cover_url,"
                          "'time',cover_time_ms,'crop',crop_json,'version',resource_version) "
                          "FROM upload_asset WHERE media_type='video' AND (crop_json IS NULL OR crop_json='')"):
    source, poster = objects.get(asset['originalUrl']), objects.get(asset['coverUrl'])
    if not source or not poster:
        continue
    profile = client.profile(source['storage_id'])
    if profile['provider'] != 'local' or poster['storage_id'] != source['storage_id']:
        continue
    original = history.within(profile['localDir'], source['object_key'])
    old_cover = history.within(profile['localDir'], poster['object_key'])
    width, height = dimensions(original)
    cover_width, cover_height = dimensions(old_cover)
    if math.isclose(width / height, cover_width / cover_height, rel_tol=.005):
        continue
    version = str(uuid.uuid4())
    key = str(Path(source['object_key']).parent.as_posix()) + '/cover-full-' + version + '.webp'
    target = history.within(profile['localDir'], key)
    new_url = source['public_url'].rsplit('/', 1)[0] + '/' + target.name
    repairs.append({**asset, 'source': str(original), 'oldFile': str(old_cover), 'newFile': str(target),
                    'newUrl': new_url, 'newVersion': version, 'key': key, 'storageId': source['storage_id'],
                    'width': width, 'height': height, 'oldWidth': cover_width, 'oldHeight': cover_height})

if args.apply:
    args.backup.mkdir(parents=True, exist_ok=False)
    manifest = args.backup / 'manifest.json'
    history.atomic_json(manifest, {'repairs': repairs})
    for repair in repairs:
        factor = min(1, images['coverMaxEdge'] / max(repair['width'], repair['height']))
        width, height = max(2, round(repair['width'] * factor)), max(2, round(repair['height'] * factor))
        subprocess.run([client.env['FFMPEG_PATH'], '-v', 'error', '-ss', str((repair['time'] or 0) / 1000),
                        '-i', repair['source'], '-vf', f'scale={width}:{height},setsar=1', '-frames:v', '1',
                        '-quality', str(images['coverQuality']), repair['newFile']], check=True, capture_output=True, timeout=45)
        actual = dimensions(Path(repair['newFile']))
        if not math.isclose(actual[0] / actual[1], repair['width'] / repair['height'], rel_tol=.005):
            raise RuntimeError('Generated cover ratio mismatch')
        size = Path(repair['newFile']).stat().st_size
        statement = f"""START TRANSACTION;
UPDATE upload_asset SET cover_url={sql_text(repair['newUrl'])},resource_version={sql_text(repair['newVersion'])}
 WHERE id={int(repair['id'])} AND resource_version={sql_text(repair['version'])};
SET @cover_repaired=ROW_COUNT();
INSERT INTO media_object(id,storage_id,object_key,public_url,content_type,size_bytes)
 SELECT {sql_text(str(uuid.uuid4()))},{sql_text(repair['storageId'])},{sql_text(repair['key'])},
 {sql_text(repair['newUrl'])},'image/webp',{size} WHERE @cover_repaired=1;
COMMIT; SELECT @cover_repaired;"""
        try:
            result = client.sql(statement).strip()
        except subprocess.CalledProcessError as error:
            raise RuntimeError(error.stderr.strip()) from None
        if result != '1':
            raise RuntimeError('Asset changed concurrently; cover was not applied')
        repair['applied'] = True
        history.atomic_json(manifest, {'repairs': repairs})
    rollback = '\n'.join(f"UPDATE upload_asset SET cover_url={sql_text(r['coverUrl'])},resource_version={sql_text(r['version'])} WHERE id={int(r['id'])} AND resource_version={sql_text(r['newVersion'])};" for r in repairs)
    (args.backup / 'rollback.sql').write_text(rollback, encoding='utf-8')
print(json.dumps({'applied': args.apply, 'repairs': [{'id': r['id'], 'oldSize': [r['oldWidth'], r['oldHeight']],
                  'videoSize': [r['width'], r['height']]} for r in repairs], 'backup': str(args.backup) if args.backup else None}, indent=2))
