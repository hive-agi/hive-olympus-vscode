// Runs inside the VS Code extension host. Waits for the hive bridge, for both
// Olympus tabs to open as webview panels, records them, then waits for the
// JVM to shrink the roster so olympus/tab-2 closes, and records again.
// SPDX-License-Identifier: MIT
const fs = require('fs');
const vscode = require('vscode');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(pred, what, ms) {
  const deadline = Date.now() + ms;
  while (!(await pred())) {
    if (Date.now() > deadline) throw new Error('timed out waiting for ' + what);
    await sleep(200);
  }
}

function snapshot(api) {
  const webviewTabs = vscode.window.tabGroups.all
    .flatMap((g) => g.tabs)
    .filter((t) => t.input instanceof vscode.TabInputWebview)
    .map((t) => ({ label: t.label, viewType: t.input.viewType }));
  return { panels: api.openPanels().sort(), webviewTabs, status: api.status() };
}

exports.run = async function run() {
  const ext = vscode.extensions.getExtension('hive-agi.hive-vscode');
  if (!ext) throw new Error('hive-agi.hive-vscode extension not found');
  const api = await ext.activate();
  await waitFor(() => api.status().status === 'connected', 'bridge connection', 60000);

  const both = ['olympus/tab-1', 'olympus/tab-2'];
  await waitFor(() => both.every((id) => api.openPanels().includes(id)), 'both Olympus tabs', 60000);
  await waitFor(() => snapshot(api).webviewTabs.length >= 2, 'two webview tabs', 30000);
  const opened = snapshot(api);
  fs.writeFileSync(process.env.HIVE_OLYMPUS_OPENED, JSON.stringify(opened));

  await waitFor(() => !api.openPanels().includes('olympus/tab-2'), 'olympus/tab-2 to close', 60000);
  await sleep(500);
  const result = { opened, after: snapshot(api), vscodeVersion: vscode.version };
  fs.writeFileSync(process.env.HIVE_OLYMPUS_RESULT, JSON.stringify(result));
};
