# Interactions And Actions

## Core Idea

Most interactions in this client are encoded as widget messages.

The basic path is:

1. a widget handles input
2. it calls `wdgmsg(...)`
3. `UI` maps the widget instance to a widget id
4. `RemoteUI` encodes the message for the server

Core files:

- `src/haven/Widget.java`
- `src/haven/UI.java`
- `src/haven/RemoteUI.java`
- `src/haven/MapView.java`
- `src/haven/MenuGrid.java`
- `src/haven/FlowerMenu.java`
- `src/haven/WItem.java`
- `src/haven/GameUI.java`

## Interaction Families

The important families are:

- map clicks through `MapView`
- action page clicks through `MenuGrid`
- flower-menu selection through `FlowerMenu`
- item interactions through `WItem`
- belt actions through `GameUI.BeltSlot`

## Example: Right-Clicking A Gob

1. `MapView` hit-tests the click.
2. `ClickData` describes what was hit.
3. `MapView` sends `wdgmsg("click", ...)` with map position and gob click arguments.
4. The server decides what that click means and may create a `FlowerMenu`.
5. Picking a menu entry sends `wdgmsg("cl", index, modflags)` back.

## Example: Triggering A Menu Action

`MenuGrid.PagButton.use(...)` sends either:

- `act` for action descriptors from the resource's `AButton` layer
- `use` for page id based actions

This can also include click context and map coordinates when the action is context-sensitive.

## Example: Automating `Pick`

The automation layer usually does not invent a new protocol. It reuses the same client pathways a player would use.

Typical flow:

1. automation picks a target gob
2. it calls a helper like `Gob.rclick(...)` or `MapView.click(...)`
3. the normal `click` message is sent
4. if needed, automation waits for a `FlowerMenu` and auto-chooses an entry

Relevant fork files:

- `src/auto/Actions.java`
- `src/auto/Bot.java`
- `src/auto/BotUtil.java`
- `src/auto/GobTarget.java`

## Native Client Behavior

Native Haven gives you the core interaction transport:

- `wdgmsg(...)` and `uimsg(...)`
- map click handling in `MapView`
- action pages in `MenuGrid`
- flower-menu selection in `FlowerMenu`
- item interaction messages in `WItem`

## Ender Additions

This fork adds several layers on top:

- custom action pages registered in `MenuGrid.initCustomPaginae()`
- local actions in `src/me/ender/CustomPagina.java` and `CustomPagButton.java`
- automation orchestration in `src/auto/**`
- flower-menu automation and `#Pick All` support in `FlowerMenu.java`
- reactor events in `src/haven/rx/Reactor.java`

One of the most useful mental distinctions is:

- native Haven gives you the interaction plumbing
- Ender decides to drive that plumbing automatically or expose more client-only entry points into it

## Gotchas

- The server owns the meaning of most messages; this repo only shows how the client packages them.
- `FlowerMenu` choice is index-based, so automation depends on stable menu ordering.
- Some custom menu pages are purely local client actions and never correspond to a server pagina.
