# PluginV2 Options And Persistence Design

**Goal:** Add immediate enable or disable checkboxes for loaded `pluginv2` plugins in the Options window, and persist plugin enablement in a way that cleanly extends to future typed plugin settings.

## Scope

This design only covers `pluginv2` enablement controls and the persistence plumbing they depend on.

In scope:

- rendering one options checkbox per loaded, valid, non-hidden `pluginv2` plugin
- persisting plugin enabled state in `config.json`
- making checkbox clicks immediately call `PluginService.syncActivePlugins()`
- validating plugin metadata early during plugin load
- reserving a config namespace that future reflected plugin settings can reuse
- removing the legacy plugin framework as the final implementation step after the new `pluginv2` options flow is in place

Out of scope:

- implementing the future reflected plugin config interface and generated widgets
- world-specific or character-specific plugin config scopes
- external plugin discovery or install/uninstall flows

## Current Behavior

`PluginService` eagerly loads built-in plugins on `UI` startup and then starts or stops them via `syncActivePlugins()`.

- `src/haven/PaistiServices.java`
- `src/paisti/pluginv2/PluginService.java`

However, plugin enablement is not actually persisted yet. `PluginService.isPluginEnabledInConfig(...)` currently returns `@PluginDescription.enabledByDefault()` directly.

The Options window still populates the `Plugins` panel through the legacy plugin system:

- `src/haven/OptWnd.java`
- `src/paisti/plugin/PluginManager.java`

## Chosen Approach

Keep `PluginService` as the lifecycle authority, add a small typed persistence helper backed by `haven.CFG`, extend the existing `Plugins` panel with a new `pluginv2` section, and remove the legacy plugin framework only after the replacement flow is working.

Why this approach:

- it is the smallest correct change for the requested feature
- it avoids inventing a second persistence system outside `CFG`
- it preserves one source of truth for plugin enabled state
- it leaves a clean namespace for future reflected plugin config items
- it supports a safer staged migration before deleting the legacy plugin framework

Rejected alternatives:

- `Map<String, Boolean>` blob under one config key: works short term, but becomes the wrong shape once plugins add typed settings
- direct JSON handling outside `CFG`: duplicates config logic and drifts from repo conventions
- delaying apply until window close: does not match the requested immediate sync behavior

## Persistence Model

Persist plugin settings globally in `config.json` under the `pluginv2` namespace.

Paths:

- `pluginv2.<configName>.enabled`
- reserved for future use: `pluginv2.<configName>.config.<keyName>`

The enable flag uses:

- persisted value when present
- `@PluginDescription.enabledByDefault()` as the default when no value has been written yet

This keeps enablement compatible with future typed plugin config without needing a migration in key layout.

## Metadata Rules

`PluginService.loadPlugins(...)` validates plugin metadata before a plugin instance is added to `loadedPlugins`.

A plugin is rejected if:

- `@PluginDescription` is missing
- `configName()` is blank
- another loaded plugin already uses the same `configName()`

Rationale:

- blank `configName` makes persistence impossible to address safely
- duplicate `configName` causes two plugins to share one persisted state path
- validating early keeps invalid plugins out of the UI and out of later config logic

Validation failure behavior:

- log a clear error to stderr
- skip loading that plugin entirely

## Runtime Responsibilities

### PluginService

`PluginService` remains responsible for deciding which loaded plugins should be active.

Changes:

- validate plugin metadata during `loadPlugins(...)`
- read persisted enablement in `isPluginEnabledInConfig(...)`
- continue using `syncActivePlugins()` as the only mechanism that starts and stops plugins based on desired state

`OptWnd` should not decide whether a plugin is active beyond writing the user intent and requesting a sync.

### Plugin Config Helper

Add a small helper around `haven.CFG` that creates plugin-scoped entries from a `configName` and key.

Required behavior now:

- read `enabled` for a plugin with a provided default
- write `enabled` for a plugin

Desired shape for extension later:

- the same helper can create typed entries under `pluginv2.<configName>.config.<keyName>`

This helper should own the config path construction so `OptWnd` and `PluginService` do not duplicate string assembly.

### OptWnd

Keep the existing legacy plugin population call for now and append a separate `pluginv2` section below it in the same `Plugins` panel during the migration step.

The new section should:

- enumerate `ui.pluginService().getLoadedPlugins()`
- filter to `hidden = false`
- rely on prior load-time validation so only valid plugins are rendered
- sort by plugin display name for stable ordering
- render one checkbox per plugin using the plugin display name
- optionally render description text beneath each plugin when non-empty

Immediate toggle behavior:

1. persist the new boolean value
2. call `ui.pluginService().syncActivePlugins()`

There is no delayed apply button and no extra confirmation step.

## Migration End State

The final implementation step after the new `pluginv2` options flow is working and verified is to remove the old plugin framework.

That removal includes legacy types and integration points such as:

- `PluginManager`
- `PluginContext`
- legacy plugin option population hooks
- any other old plugin-framework classes that are no longer needed once `pluginv2` fully owns plugin lifecycle and options

The expectation is a staged rollout:

1. add `pluginv2` persistence and options toggles
2. verify the new flow works
3. remove the legacy plugin framework as the cleanup step

## UI Behavior

The checkbox reflects persisted user intent, not current runtime success.

That means:

- if a default-on plugin has never been configured, the checkbox appears checked
- if a plugin was explicitly disabled earlier, the checkbox appears unchecked
- if plugin startup fails after the user enables it, the checkbox remains checked because the persisted intent is still enabled

This avoids hiding startup failures by coupling the checkbox directly to transient runtime state.

## Error Handling

- Invalid plugin metadata prevents the plugin from loading and from appearing in the options UI
- Persist the checkbox value before calling `syncActivePlugins()` so runtime failures do not lose the user’s choice
- If startup fails during sync, keep the current `PluginService.startPlugin(...)` behavior that logs the failure, attempts shutdown cleanup, and does not leave the plugin in `activePlugins`
- If the `pluginv2` section has no visible plugins, the panel should remain valid and simply omit plugin checkboxes

## Files Expected To Change

- `src/paisti/pluginv2/PluginService.java`
- `src/haven/OptWnd.java`
- `src/haven/CFG.java` or a new small `haven` helper class that exposes plugin-scoped `CFG` creation

Possible supporting test file:

- `src/haven/test/PluginV2ServiceSelfTest.java`

## Testing Strategy

Add focused coverage around config resolution before changing production behavior.

Minimum useful cases:

1. missing persisted state falls back to `enabledByDefault()`
2. persisted `false` disables a default-on plugin
3. persisted `true` enables a default-off plugin
4. blank `configName` causes the plugin to be rejected during load
5. duplicate `configName` causes the later plugin to be rejected during load

Because there is no robust UI test harness here, verify the UI wiring manually after build:

1. open Options -> Plugins
2. during migration, confirm legacy plugin options still render until the cleanup step
3. confirm a `Plugin V2` section appears
4. confirm hidden plugins are absent
5. confirm toggling a checkbox updates plugin runtime state immediately
6. restart the client and confirm the checkbox state persists
7. after cleanup, confirm the legacy plugin section is gone and the new plugin flow still works

Minimum build verification:

- `ant bin -buildfile build.xml`

## Risks

- mixing legacy and `pluginv2` controls in one panel may be visually noisy while both systems coexist
- plugin startup or shutdown side effects can now happen while the Options window is open
- future class or annotation changes could still break persisted config if `configName` is edited carelessly

## Mitigations

- label the new section clearly as `Plugin V2`
- keep lifecycle logic centralized in `PluginService`
- require explicit, non-blank `configName` and reject duplicates at load time
- reserve the future `config` namespace now so later typed settings do not require rethinking storage

## Open Decisions Resolved

- show hidden plugins in options: no
- apply toggles immediately: yes
- support character-specific config now: no
- fallback blank `configName` to class name: no
- keep legacy plugin options visible during transition: yes, then remove the old framework as the final step
