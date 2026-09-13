# hive-olympus-vscode

Olympus in VS Code. This brick has no code: it is one manifest that mounts
`hive-olympus.harness/addon-ctor` with `{:olympus/host "hive.vscode"}`. The
core (`hive.olympus`) renders the agent grid as one `:ui/show-panel` per tab.
`hive.vscode` exposes only `:vessel/target`, so the harness takes the
`:host-target` route: it lowers the ops through hive-vessel's standard registry
into `:json` natives and calls the target's `:vessel/execute!`. The hive-vscode
extension shows each tab as a webview panel.

```
resources/META-INF/hive-addons/hive-olympus-vscode.edn
```

Requires `hive.olympus` and `hive.vscode` mounted in the same hive.

## Test

hive-olympus is not yet published, so point at a sibling checkout:

```
clojure -Sdeps "$(cat local.deps.edn)" -M:test
```

with an untracked `local.deps.edn`:

```clojure
{:deps {io.github.hive-agi/hive-olympus {:local/root "../hive-olympus"}}}
```

## Real host

`dev/hive_olympus_vscode/real_host.clj` mounts the published `hive.vscode`, the
real `hive.olympus` core over a stub roster and this manifest in a fresh JVM,
then subscribes to the bridge's SSE stream the way the extension does:

```
clojure -Sdeps "$(cat local.deps.edn)" -M:real-host          # wire capture
clojure -Sdeps "$(cat local.deps.edn)" -M:real-host vscode   # + headless VS Code
```

The `vscode` run needs `xvfb-run`, `node`, and a sibling `../hive-vscode`
checkout with `npm install`, `out/extension.js` built and VS Code cached under
`.vscode-test/` (its own real-VS Code e2e leaves that behind).

MIT licensed.
