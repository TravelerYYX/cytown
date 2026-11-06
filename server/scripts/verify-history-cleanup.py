"""Exercise the cleanup tool only against the verifier's disposable database."""
import argparse
import importlib.util
import os
from pathlib import Path
import tempfile
import uuid

spec = importlib.util.spec_from_file_location('history', Path(__file__).with_name('cleanup-history.py'))
history = importlib.util.module_from_spec(spec)
spec.loader.exec_module(history)


def verify(mysql, mysqldump, env):
    assert 'ciyuantown_account_test_' in env['DB_URL']
    previous = os.environ.copy()
    os.environ.update(env)
    try:
        with tempfile.TemporaryDirectory(prefix='ct-cleanup-') as temporary:
            root = Path(temporary)
            uploads = root / 'uploads'
            for area in ('posts','app-avatar','config'):
                (uploads / area).mkdir(parents=True)
            os.environ['UPLOAD_DIR'] = str(uploads)
            files = {'posts/old.png':b'old-media','posts/shared.png':b'operational-media',
                     'app-avatar/kept.png':b'avatar','config/kept.png':b'admin-resource'}
            for name, body in files.items(): (uploads / name).write_bytes(body)
            cleanup = history.Cleanup(argparse.Namespace(mysql=mysql,mysqldump=mysqldump))
            cleanup.sql("INSERT INTO system_config(config_key,config_value) VALUES('history_fixture','/uploads/posts/shared.png')")
            owner=cleanup.rows('app_user')[0]['id']
            cleanup.sql(f"""
              INSERT INTO post(author_id,author_nickname,title,mode,status) VALUES({owner},'cleanup-fixture','old-post','image',1);
              SET @post_id=LAST_INSERT_ID();
              INSERT INTO upload_asset(owner_id,media_type,url) VALUES({owner},'image','/uploads/posts/old.png');
              SET @asset_id=LAST_INSERT_ID();
              INSERT INTO post_media(post_id,asset_id,type,layout,aspect_ratio,url) VALUES(@post_id,@asset_id,'image','square',1,'/uploads/posts/old.png');
              INSERT INTO comment(post_id,author_nickname,content) VALUES(@post_id,'cleanup-fixture','old-comment');
              INSERT INTO user_like(user_id,post_id) VALUES({owner},@post_id);
              INSERT INTO user_favorite(user_id,post_id) VALUES({owner},@post_id);
              INSERT INTO post_draft(author_id,mode,payload) VALUES({owner},'image',JSON_OBJECT('assetIds',JSON_ARRAY(@asset_id)));
            """)
            before_users = cleanup.rows('app_user')
            folder = root / 'backup'
            cleanup.prepare(folder)
            manifest = folder / 'manifest.json'
            old = uploads / 'posts/old.png'
            old.write_bytes(b'changed-after-prepare')
            try:
                cleanup.apply(manifest)
                raise AssertionError('Changed source was deleted')
            except ValueError: pass
            assert old.exists()
            old.write_bytes(files['posts/old.png'])
            # New rows created after the manifest must survive.
            cleanup.sql("INSERT INTO post(author_nickname,title,mode,status) VALUES('new-fixture','keep-after-snapshot','post',0)")
            post_id = cleanup.query("SELECT JSON_OBJECT('id',MAX(id)) FROM post")[0]['id']
            def unavailable(): raise RuntimeError('simulated Redis outage after database/file cleanup')
            cleanup.clear_redis = unavailable
            try:
                cleanup.apply(manifest)
                raise AssertionError('Simulated interruption was ignored')
            except RuntimeError: pass
            cleanup.clear_redis = lambda: None
            cleanup.apply(manifest)
            cleanup.apply(manifest)
            assert cleanup.rows('app_user') == before_users
            assert cleanup.query('SELECT JSON_OBJECT(\'id\',id) FROM post') == [{'id':post_id}]
            assert not cleanup.rows('upload_asset') and not cleanup.rows('post_draft')
            assert not old.exists()
            for name in ('posts/shared.png','app-avatar/kept.png','config/kept.png'):
                assert (uploads / name).read_bytes() == files[name]
            for key in ('../escape','/absolute','C:/outside','nested/../../outside'):
                try: history.within(uploads,key); raise AssertionError('Unsafe path accepted')
                except ValueError: pass
            print('Cleanup verified: backups, changed-file guard, retained references, new rows, restart/resume and safe paths.')
    finally:
        os.environ.clear()
        os.environ.update(previous)
