# Inventories

## Core Representation

The native inventory grid is `haven.Inventory`.

Core files:

- `src/haven/Inventory.java`
- `src/haven/ExtInventory.java`
- `src/haven/Equipory.java`
- `src/haven/InventoryProxy.java`
- `src/haven/DTarget.java`

`Inventory` is a widget that owns a grid of slots and creates `WItem` views for `GItem` children.

## How The Grid Works

`Inventory` tracks slot dimensions in `isz` and keeps a `Map<GItem, WItem>` so it can map backing items to visible widgets.

When a `GItem` child is added:

1. the inventory receives the new child widget
2. it creates a `WItem`
3. it places that `WItem` at the correct slot position

That means inventories are both:

- containers of backing item widgets
- containers of visible item widgets

## Drag And Drop

`Inventory` implements `DTarget`, and most movement is done through widget messages such as `drop`, `take`, `transfer`, and `itemact`.

Related containers like `Equipory` and `ISBox` participate in the same general model even though their layouts differ.

## The Biggest Fork Change

In this fork, the server-facing `inv` widget factory does not give you a bare `Inventory`. It gives you an `ExtInventory` wrapper.

That wrapper owns a real `Inventory` but adds fork UI around it.

Practical result:

- the server still thinks it created an inventory
- the client often shows an enhanced grouped inventory instead

## Native Client Behavior

Native Haven provides:

- `Inventory` as the slot grid
- `WItem` creation and placement
- slot masks and placement logic
- `Equipory` for equipment slots
- generic drag/drop contracts via `DTarget`

## Ender Additions

The fork adds:

- `ExtInventory` grouped and expanded inventory view
- sorting helpers and auto-drop integrations
- recursive stack/container handling for grouped displays
- batch operations and same-item helpers in `Inventory.java`

Relevant fork-oriented files:

- `src/haven/ExtInventory.java`
- `src/auto/InvHelper.java`
- `src/auto/InventorySorter.java`

## Gotchas

- Not every runtime `inv` widget is literally an `Inventory`; this fork wraps it.
- A container may expose nested subinventories through item contents widgets, not only through top-level windows.
- Code that scans inventory trees needs to be clear about whether it wants `Inventory`, `ExtInventory`, `GItem`, or `WItem`.
