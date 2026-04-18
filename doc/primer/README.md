# Haven Client Primer

This directory is a developer-oriented primer for this fork of the Haven & Hearth client.

The most important framing for this repository is:

- `src/haven/**` is the native Haven client core and the main upstream merge surface.
- This fork still patches a lot of behavior directly inside `src/haven/**`.
- Fork-specific subsystems are most concentrated in `src/me/ender/**`, `src/auto/**`, `src/integrations/mapv4/**`, `resources/src/local/**`, and `etc/*.json*`.

That means you should read most topics as:

1. native Haven mechanism
2. fork hooks added on top

## Suggested Reading Order

1. `01-gobs.md`
2. `02-inventory-items.md`
3. `03-inventories.md`
4. `04-interactions.md`
5. `05-map-and-coordinates.md`
6. `06-windows-and-widgets.md`
7. `07-netcode.md`
8. `08-additional-concepts.md`

## Mental Model

At a high level, the client works like this:

1. `Bootstrap` and `AuthClient` log in and start a `Session`.
2. `Session` owns the live game state in `Glob`.
3. `Glob` owns `OCache` for gobs and `MCache` for terrain/map data.
4. `RemoteUI` turns server UI messages into `Widget` creation and `uimsg(...)` calls.
5. `GameUI`, `MapView`, `MenuGrid`, `Window`, `Inventory`, `GItem`, and related widgets present that state and send `wdgmsg(...)` back to the server.
6. Ender additions hook into that baseline with automation, overlays, custom menu pages, enhanced inventories, patched windows, and mapper integration.

## Native vs Ender

Throughout these docs:

- "Native" means the baseline Haven client architecture or the upstream-origin mechanism.
- "Ender" means code added by this fork, even when it lives inside a native file under `src/haven/**`.

That distinction matters because many debugging questions are really "is this how Haven normally works, or is this fork behavior layered on top?"
