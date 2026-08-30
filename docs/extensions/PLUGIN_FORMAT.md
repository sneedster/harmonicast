# Loadable plugin format

Harmonicast plugins are trusted, server-side extensions. Install only code you
control or have audited. They are loaded from the persistent plugin directory
after the server restarts.

## Repository layout

```text
my-plugin/
  harmonicast-plugin.json
  package.json
  package-lock.json
  server/index.js
```

`package-lock.json` is required. Harmonicast runs `npm ci --omit=dev
--ignore-scripts` during installation, so publish runnable JavaScript and put
runtime dependencies in `dependencies`, not `devDependencies`.

## Manifest

Create `harmonicast-plugin.json` at the repository root:

```json
{
  "id": "example-source",
  "displayName": "Example music source",
  "apiVersion": 1,
  "entry": "server/index.js",
  "settings": [
    { "key": "baseUrl", "label": "Service URL", "type": "url" },
    { "key": "apiKey", "label": "API key", "type": "password", "secret": true }
  ]
}
```

`id` must contain 3–64 lowercase letters, digits, or hyphens and start with a
letter. `entry` must be a relative path inside the plugin. The only supported
`apiVersion` is currently `1`.

Supported setting types are `text`, `url`, `password`, `boolean`, and `number`.
Mark secrets with `"secret": true`; Harmonicast renders them as write-only
password fields and does not return their values to clients.

## Entry module

The entry module must use ESM and export a default factory function:

```js
export default function createPlugin() {
  return {};
}
```

At the current loader milestone Harmonicast validates and imports this factory
at startup. The music-source lifecycle host API and router registration are the
next contract milestone; do not rely on undeclared server internals or direct
SQLite access. Keeping the factory side-effect free makes upgrades and failed
plugin isolation safe.

## Installation

In host-only Settings, enter a plain GitHub repository URL and a fixed tag or
commit. Harmonicast downloads the matching GitHub archive, records its SHA-256
checksum, validates the manifest, and atomically installs it. Private GitHub
repositories need `HARMONICAST_PLUGIN_SOURCE_TOKEN` supplied only to the server
container. Restart Harmonicast after a successful installation.
