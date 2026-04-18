# Additional Concepts Worth Learning Next

These are the next concepts I would recommend to a developer after the core seven topics.

## 1. `Session`, `Glob`, `OCache`, And `MCache`

Why it matters:

- this is the real runtime backbone of the client
- most systems ultimately consume state from here

Key files:

- `src/haven/Session.java`
- `src/haven/Glob.java`
- `src/haven/OCache.java`
- `src/haven/MCache.java`

Native vs Ender:

- native Haven defines these as the main live state containers
- Ender adds observers and extra consumers, but it does not replace the basic structure

## 2. Resources, Sprites, And Published Code

Why it matters:

- resource loading explains where actions, tooltips, sprites, pages, and a lot of behavior come from
- many client systems are resource-driven rather than hardcoded

Key files:

- `src/haven/Resource.java`
- `src/haven/Sprite.java`
- `src/haven/GSprite.java`

Native vs Ender:

- native Haven defines the resource and sprite architecture
- Ender adds custom resource layers and local resources under `resources/src/local/**`

## 3. Render Tree And Visual State

Why it matters:

- if you want to understand overlays, draw order, state inheritance, or why a gob is visible, this is the next layer down

Key files:

- `src/haven/render/RenderTree.java`
- `src/haven/render/Pipe.java`
- `src/haven/MapView.java`
- `src/haven/Sprite.java`

Native vs Ender:

- native Haven defines the rendering framework
- Ender mostly injects more nodes, overlays, markers, and alternate visual states into that framework

## 4. Settings And Reactive Config

Why it matters:

- a lot of fork behavior is toggled by `CFG`
- many visual refreshes and helper systems are driven by config observers

Key files:

- `src/haven/CFG.java`
- `config.json`

Native vs Ender:

- `CFG` is fork-specific infrastructure in this repository's current architecture
- it is the main place to add new persistent fork settings instead of inventing ad hoc storage

## 5. Custom Paginae And Local Actions

Why it matters:

- this explains how fork features appear in the in-game action menu
- many "where is this feature wired in?" questions land here

Key files:

- `src/haven/MenuGrid.java`
- `src/me/ender/CustomPagina.java`
- `src/me/ender/CustomPagButton.java`
- `src/me/ender/CustomPaginaAction.java`
- `resources/src/local/paginae/add/**`

Native vs Ender:

- native Haven supplies resource-driven action pages
- Ender injects local pages through `MenuGrid.initCustomPaginae()`

## 6. Bot And Automation Framework

Why it matters:

- it explains how client automation is structured instead of being scattered one-off hacks

Key files:

- `src/auto/Bot.java`
- `src/auto/Actions.java`
- `src/auto/BotUtil.java`
- `src/auto/GobTarget.java`

Native vs Ender:

- this is overwhelmingly fork code
- it generally reuses native interaction plumbing instead of replacing it

## 7. Window Patching And Runtime UI Rewriting

Why it matters:

- it explains why some server-created windows do not behave like plain upstream windows in this fork

Key files:

- `src/me/ender/WindowDetector.java`
- `src/haven/Window.java`
- `src/haven/WindowX.java`
- `src/haven/DecoX.java`

Native vs Ender:

- native Haven gives you the base window protocol
- Ender swaps and extends windows dynamically

## 8. Data-Driven Knowledge Packs In `etc/`

Why it matters:

- many overlays and helper systems are not hardcoded
- gameplay behavior is often driven by JSON or JSON5 datasets

Examples:

- `etc/radar.json`
- `etc/gob_radius.json`
- `etc/gob_contents.json5`
- `etc/automark.json5`
- `etc/tile_highlight.json`
- `etc/treatments.json5`

Native vs Ender:

- this is fork territory
- it is one of the main reasons fork behavior can look "magical" until you realize the data files are part of the feature

## Recommended Next Reading Order

1. `Session` and `Glob`
2. `Resource`, `Sprite`, and `GSprite`
3. `CFG`
4. `MenuGrid.initCustomPaginae()`
5. `auto/Actions.java`
6. `me/ender/WindowDetector.java`
