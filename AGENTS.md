## Your responses

- Offer constructive criticism that identifies flaws and oversights.
- Always ask for more details and liberally search the web or code as needed to provide a good answer or implementation.
  Do not state your assumptions like they're facts.
- User should not have to ask you for your opinion explicitly. Always evaluate what the user is asking you to do, and
  voice your concerns before proceeding if you don’t think it's a good idea. If possible, propose a better solution, but
  you can voice concerns even without one. Still evaluate whether your original approach was better. The user may be
  missing important context. If there was a solid reasoning you suggested that approach, push back with reasoning
  instead of silently complying.

## MCP Servers
- Use the intellij-index MCP 

## Build And Run
- Use JDK 11. `build.xml` compiles with `source/target/release=11`, and CI uses Temurin 11 in `.github/workflows/build.yml`.
- This repo is Ant-only.
- Main commands:
  - `ant bin -buildfile build.xml` builds the distributable client in `bin/`
  - `ant run -buildfile build.xml` builds `bin/` and launches it
  - `ant clean-code bin -buildfile build.xml` recompiles code without wiping downloaded native deps
  - `ant clean -buildfile build.xml` also deletes `lib/ext`, so the next build redownloads JOGL/LWJGL/Steamworks jars
- `build/hafen.jar` is not the normal play target. Use `bin/hafen.jar`; `bin/` is where the official-server config, `client-res.jar`, and downloaded native jars are assembled.
- Keep `encoding="UTF-8"` on both `javac` tasks in `build.xml`. This source tree contains non-ASCII Java sources, and Windows builds break without explicit UTF-8.
- If you change `resources/src/**` or packaged data under `etc/*.json*`, rerun full `ant bin`; those files are bundled into `build/client-res.jar`/`bin/client-res.jar`.
- There is no real automated test suite wired into Ant or CI. The only CI verification is `ant bin`.

## Repo Boundaries
- `src/haven/**` is still the vanilla Haven client core and the main surface for upstream Loftar merges.
- Fork-specific code is concentrated in:
  - `src/me/ender/**`: Ender UI/features/helpers
  - `src/auto/**`: automation and bot actions
  - `src/integrations/mapv4/**`: mapper upload/tracking integration
  - `resources/src/local/paginae/add/**`: custom action pages/icons/help text
  - `etc/*.json*`: data-driven overlays, automark/radar config, alchemy/treatment datasets
- `src/haven/MainFrame.java` is still the desktop entrypoint.
- `src/haven/CFG.java` is the central registry for fork-specific persisted settings in `config.json`. Add new toggles here instead of inventing separate config storage.
- `src/haven/MenuGrid.java:initCustomPaginae()` is the registration point for most custom menu actions. If a feature should appear under the in-game menu, wire it there and add the matching resource under `resources/src/local/paginae/add/**`.
- `src/me/ender/WindowDetector.java` is where the fork patches specific windows at runtime (table controls, prospecting, charter/portal windows, animal windows).

## What This Fork Adds Beyond Vanilla
- Automation actions in `src/auto/**` plus `MenuGrid.initCustomPaginae()`: pick/pick-all, fuel ovens/smelters, aggro helpers, mount horse, refill drinks, equip presets, flower-menu automation, inventory sorting.
- Ender UI/features in `src/me/ender/**`: alchemy database/tracking windows, quest helper, gob info overlays, extra inventory helpers, minimap extras, animal window actions, combat/stat widgets.
- Mapper integration in `src/integrations/mapv4/MappingClient.java` backed by `CFG.AUTOMAP_*` settings.
- Data-driven gameplay overlays and knowledge packs under `etc/`: `radar.json`, `gob_radius.json`, `gob_contents.json5`, `gob_path.json`, `automark.json5`, `tile_highlight.json`, `treatments.json5`, `ingredients/effects/elixirs/combos` datasets.
- Local resources under `resources/src/local/` add custom Automation/Info/Equip menu entries and help pages that vanilla does not ship.

## IntelliJ
- Checked-in run configs live in `.idea/runConfigurations/hafen.xml` and `steam.xml`. Both build `ant bin` before launch.
- Use project SDK 11. The run configs are set to inherit the project JDK; if IntelliJ says it cannot resolve an SDK, re-select the project SDK rather than editing Ant first.
- `hafen.iml` excludes `build/`, `bin/`, and generated resource directories, so inspect packaged output from the filesystem, not as module sources.

## Verification
- Minimum useful build check: `ant bin -buildfile build.xml`
- Minimum useful runtime smoke test: `java -jar bin/hafen.jar`
- `src/haven/test/**` contains helper/test harness classes, but there is no Ant target or CI job that runs them.
