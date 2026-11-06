const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const platform = process.argv[2];
if (!['h5', 'app'].includes(platform)) {
  throw new Error('Expected h5 or app build platform');
}
const root = path.resolve(__dirname, '..');
const result = spawnSync(process.execPath, [
  require.resolve('@dcloudio/vite-plugin-uni/bin/uni.js'),
  'build',
  ...(platform === 'app' ? ['-p', 'app'] : []),
  ...process.argv.slice(3),
], {
  cwd: root,
  env: { ...process.env, UNI_INPUT_DIR: root },
  stdio: 'inherit',
});
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status || 1);

// Development-mode uni builds omit static resources from dist/build.
fs.cpSync(path.join(root, 'static'), path.join(root, 'dist/build', platform, 'static'), {
  recursive: true,
});
console.log(`Static assets included in ${platform} build.`);
