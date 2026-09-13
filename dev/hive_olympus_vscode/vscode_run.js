// Launch the cached VS Code with the hive-vscode extension (read-only, from the
// checkout at HIVE_VSCODE_REPO) and run vscode_suite.js in its extension host.
// Driven by dev/hive_olympus_vscode/real_host.clj, which supplies the env.
// SPDX-License-Identifier: MIT
const fs = require('fs');
const path = require('path');

const repo = process.env.HIVE_VSCODE_REPO;
const work = process.env.HIVE_OLYMPUS_WORKDIR;
const { runTests } = require(path.join(repo, 'node_modules/@vscode/test-electron'));

function cachedVersion(cache) {
  const hit = fs.readdirSync(cache).find((d) => /^vscode-linux-x64-\d/.test(d));
  if (!hit) throw new Error('no cached VS Code under ' + cache);
  return hit.replace('vscode-linux-x64-', '');
}

async function main() {
  const cachePath = path.join(repo, '.vscode-test');
  const workspace = path.join(work, 'ws');
  fs.mkdirSync(workspace, { recursive: true });
  try {
    await runTests({
      version: cachedVersion(cachePath),
      cachePath,
      extensionDevelopmentPath: repo,
      extensionTestsPath: path.resolve(__dirname, 'vscode_suite.js'),
      extensionTestsEnv: {
        XDG_RUNTIME_DIR: process.env.XDG_RUNTIME_DIR,
        HIVE_OLYMPUS_OPENED: process.env.HIVE_OLYMPUS_OPENED,
        HIVE_OLYMPUS_RESULT: process.env.HIVE_OLYMPUS_RESULT,
      },
      launchArgs: [
        workspace,
        '--user-data-dir', path.join(work, 'user'),
        '--extensions-dir', path.join(work, 'extensions'),
        '--disable-extensions', '--disable-workspace-trust',
        '--skip-welcome', '--skip-release-notes', '--disable-gpu',
      ],
    });
    process.exit(0);
  } catch (err) {
    console.error('olympus vscode run failed:', err);
    process.exit(1);
  }
}

main();
