# Inventory Items

## Core Representation

An inventory item is split across multiple classes:

- `GItem`: the backing item widget and item model
- `WItem`: the visible inventory-cell widget that renders a `GItem`
- `ItemDrag`: a temporary `WItem` subclass used while an item is held by the cursor

Core files:

- `src/haven/GItem.java`
- `src/haven/WItem.java`
- `src/haven/ItemDrag.java`
- `src/haven/ItemInfo.java`

## What Lives On `GItem`

`GItem` is the authoritative client object for an item. It holds things like:

- the resource reference
- sprite data
- tooltip payloads
- stack count and meter state
- nested contents widgets
- cached `ItemInfo` metadata

If you want to know what the server says an item is, `GItem` is the place to start.

## What Lives On `WItem`

`WItem` is the visual shell around `GItem`.

It handles:

- drawing the item sprite in an inventory slot
- drawing overlays like quantity, quality, bars, armor, and wear
- tooltips
- mouse clicks and drag/drop gestures

This split matters because a lot of code that "looks like item code" is really widget code.

## Item Metadata

The main metadata pipeline is `ItemInfo`.

Raw tooltip payloads are turned into typed info objects through `ItemInfo.buildinfo(...)`, and later helper methods or callers extract things like:

- display name
- quantity/count
- fill level and contents
- quality
- wear
- armor

Important supporting files:

- `src/haven/ItemInfo.java`
- `src/haven/ItemData.java`
- `src/haven/QualityList.java`

## Native Client Behavior

Native Haven provides the basic model:

- `GItem` owns the resource, sprite, tooltip, and server-facing item state
- `WItem` draws that item in inventory UI
- `ItemDrag` handles held-item behavior
- `ItemInfo` is the generic tooltip and metadata system

## Ender Additions

This fork adds a lot of convenience and UI parsing on top of that:

- derived caches for name, quality, contents, and quantity in `GItem`
- quality, durability, armor, and timer overlays in `WItem`
- alchemy processing hooks in `src/me/ender/alchemy/AlchemyData.java`
- helper extraction and reflective parsing in `ItemInfo.java`
- utility helpers in `src/me/ender/ItemHelpers.java`

The baseline client mostly exposes item data. The fork aggressively interprets that data to drive overlays, filters, and automation.

## Gotchas

- `GItem` is the backing item, but `WItem` is what you often click or iterate over in UI trees.
- Many item details are inferred from tooltip formats, not from clean dedicated fields.
- Nested contents live as widgets under the item, so containers and stacked items are more dynamic than they first look.
