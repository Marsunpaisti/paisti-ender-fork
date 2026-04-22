# Auto-reload Study Items / Curiosities

## Goal

Add a client-side feature that automatically refills the study inventory when a curiosity finishes, without forcing the player to manually drag the next curio into the study window.

## Recommended Behavior

Use a conservative first version:

- Add a single toggle: `Auto-reload study items`
- Only pull replacement curios from `maininv`
- Only reload when the study inventory has both:
  - enough free attention
  - enough free slot space for the candidate item size
- Do not use the global `Bot` pipeline for this feature
- Throttle checks from `GameUI.tick()` instead of tying logic to the visible study window

Why this is the safer baseline:

- `StudyWnd` is just a mirror window; it can be hidden, so it is the wrong place to own automation
- the repo already centralizes long-lived client state in `GameUI`
- `Bot` is singleton-based and would interfere with other automation tasks like drink refill or sorting

## Existing Integration Points

### Study / curio UI

- `src/haven/GameUI.java`
  - `studywnd` is created in `addchild(..., "chr")`
  - `toggleStudy()` only shows/hides the extra study window
  - `tick(double dt)` is the cleanest place for a throttled background controller
- `src/haven/SAttrWnd.java`
  - `addchild(..., "study")` receives the actual study inventory widget from the server
  - it already forwards that inventory into `studywnd.setStudy(...)`
- `src/haven/StudyWnd.java`
  - currently mirrors the study inventory through `InventoryProxy`
  - it does not retain the raw `Inventory` in a way other code can use

### Curio data

- `src/haven/resutil/Curiosity.java`
  - exposes LP, attention weight (`mw`), XP cost, and remaining time
- `src/haven/WItem.java`
  - already caches `Curiosity` info through `curio`
  - exposes `take()` and item size via `lsz`
- `src/haven/GItem.java`
  - updates `meterUpdated` on tooltip changes and progress changes

### Inventory manipulation

- `src/haven/Inventory.java`
  - `findPlaceFor(Coord size)` can locate a free slot for a candidate curio
  - `children(WItem.class)` is enough to scan current contents
- `src/auto/BotUtil.java`
  - `waitHeldChanged(gui)` is usable even if you do not use `Bot`
- `src/auto/InvHelper.java`
  - shows the existing pattern for `take()` then `putBack()` operations

### Config / settings

- `src/haven/CFG.java`
  - this repo already expects new persisted toggles to live here
- `src/haven/OptWnd.java`
  - curio-related settings already live together in the item/curio section

## Recommended Structure

### 1. Add a new config flag

In `src/haven/CFG.java`, add:

```java
public static final CFG<Boolean> AUTO_RELOAD_STUDY = new CFG<>("ui.auto_reload_study", false);
```

Then expose it in `src/haven/OptWnd.java` next to:

- `REAL_TIME_CURIO`
- `SHOW_CURIO_REMAINING_TT`
- `SHOW_CURIO_REMAINING_METER`
- `SHOW_CURIO_LPH`

Suggested label:

```text
Auto-reload study items
```

Optional tooltip:

```text
Automatically moves fitting curiosities from inventory into the study window when space opens up.
```

## 2. Keep the automation outside `StudyWnd`

Create a small controller class, preferably under `src/auto/`, for example:

- `src/auto/StudyReloader.java`

Reasoning:

- it is feature code specific to the fork
- it avoids adding automation logic deep inside `src/haven/**`
- it keeps upstream merge pressure lower

The class should own only:

- a `GameUI` reference
- a light throttle timer
- maybe a simple in-progress boolean to prevent re-entry during `take/drop`

## 3. Expose the raw study inventory once

`SAttrWnd.addchild(..., "study")` already has the real study `Inventory`.

Make that inventory reachable by the reloader with one minimal hook:

- easiest option: update `StudyWnd` to store the raw `Inventory` passed into `setStudy(...)` and expose a getter
- alternate option: store it directly on `GameUI`

Recommended minimal change:

```java
private Inventory backingStudy;

public void setStudy(Inventory inventory) {
    backingStudy = inventory;
    ...
}

public Inventory backingStudy() {
    return backingStudy;
}
```

That keeps the existing study-window behavior unchanged while giving the controller a stable handle.

## 4. Run the controller from `GameUI.tick()`

Wire the controller from `GameUI` instead of from `StudyWnd` or `SAttrWnd`.

Why:

- the character sheet and extra study window can both be hidden
- `GameUI.tick()` already exists as the general background loop
- this avoids relying on draw-time behavior

Suggested pattern:

```java
private final StudyReloader studyReloader = new StudyReloader(this);

public void tick(double dt) {
    super.tick(dt);
    ...
    studyReloader.tick(dt);
}
```

Throttle checks to something like every `0.25` to `0.5` seconds. There is no need to evaluate on every frame.

## 5. Detect when reload is needed

Do not try to trigger from "meter reached 100%" alone. That is brittle and easy to desync.

A safer rule is:

- if study inventory exists
- and cursor/hand is empty
- and auto-reload is enabled
- and there is free study capacity
- then try to place one fitting replacement curio

"Free study capacity" should mean both:

### Free attention

Compute current attention usage the same way `SAttrWnd.StudyInfo.upd()` and `StudyWnd.StudyInfo.upd()` already do:

- iterate study inventory items
- read `Curiosity` info
- sum `mw`
- compare against `ui.sess.glob.getcattr("int").comp`

### Free slot geometry

Use `study.findPlaceFor(candidate.lsz)`.

This matters because some curios are larger than `1x1`, so "free attention" alone is not enough.

## 6. Pick a conservative candidate policy

This is the part most likely to cause annoying behavior if it is too clever.

Recommended first-pass policy:

- only scan `gui.maininv`
- only consider items with `witem.curio.get() != null`
- require `candidateCurio.mw <= freeAttention`
- require `study.findPlaceFor(witem.lsz) != null`
- sort by:
  1. same resource name as the just-finished curio, if you choose to track that
  2. then highest quality
  3. then stable name/resource fallback

If you do not want to track the finished-item identity yet, the even simpler version is:

- pick the first fitting curio from inventory in deterministic order

That is less smart, but much less error-prone.

## 7. Move exactly one item per pass

Do not try to fully refill all free study slots in one burst on the first implementation.

Safer approach:

- find one candidate
- `candidate.take()`
- wait for the held item to appear
- `study.wdgmsg("drop", slot)`
- wait for the held item to clear
- stop until the next throttled tick

This keeps recovery simple if the server rejects a move or the inventory changed underneath you.

Pseudo-code:

```java
public void tick(double dt) {
    if(!CFG.AUTO_RELOAD_STUDY.get()) return;
    if(reloading) return;
    if(!throttleReady(dt)) return;

    Inventory study = gui.studywnd != null ? gui.studywnd.backingStudy() : null;
    Inventory main = gui.maininv;

    if(study == null || main == null) return;
    if(gui.hand() != null || gui.cursor != null) return;

    int freeAttention = computeFreeAttention(study, gui);
    if(freeAttention <= 0) return;

    WItem candidate = findCandidate(main, study, freeAttention);
    if(candidate == null) return;

    Coord drop = study.findPlaceFor(candidate.lsz);
    if(drop == null) return;

    reloading = true;
    try {
        candidate.take();
        if(!BotUtil.waitHeldChanged(gui)) return;
        study.wdgmsg("drop", drop);
        BotUtil.waitHeldChanged(gui);
    } finally {
        reloading = false;
    }
}
```

## 8. Avoid the `Bot` singleton here

This is an important design constraint.

`src/auto/Bot.java` keeps one global current bot and cancels prior automation when a new one starts. If auto-reload uses `Bot.process(...)`, it can randomly interrupt:

- drink refill
- inventory sorting
- flower-menu automation
- other client automation

For this feature, direct `take/drop` plus `heldNotifier` waiting is the better design.

## 9. Edge cases to handle explicitly

- Character sheet not initialized yet: do nothing
- Study inventory hidden: still work, because automation should use the backing inventory, not visibility
- Player already holding something: do nothing
- Hidden cursor item: still treat as occupied by checking `gui.hand()`, not only cursor image state
- No fitting candidate in inventory: do nothing
- Candidate fits attention but not slot geometry: skip it
- Candidate fits slots but not attention: skip it
- Tooltip/info still loading: catch `Loading` and skip that item for this pass
- Study inventory locked via `LOCK_STUDY`: decide whether this should pause automation or only block manual dragging

The last point is worth deciding up front. A reasonable first choice is:

- `LOCK_STUDY` affects manual interaction only
- `AUTO_RELOAD_STUDY` is the actual automation toggle

## 10. Nice-to-have follow-ups, not required for v1

- Add a second option for source scope:
  - `main inventory only`
  - `inventory + belt + pouches`
- Add a replacement mode:
  - `first fitting`
  - `same type first`
  - `best LP/H first`
- Add a short user message when no refill is possible after a completion event
- Add inventory change listeners to `Inventory` if you want an event-driven version later instead of throttled polling

## Suggested Implementation Order

1. Add `CFG.AUTO_RELOAD_STUDY`
2. Add the option to `OptWnd`
3. Expose the raw study `Inventory`
4. Add `auto/StudyReloader.java`
5. Call it from `GameUI.tick()` with throttling
6. Implement `computeFreeAttention(...)`
7. Implement `findCandidate(...)`
8. Implement one-item `take/drop` reload logic
9. Build and smoke-test

## Verification Checklist

Build:

```bash
ant bin -buildfile build.xml
```

Manual smoke cases:

1. Fill study with mixed curios and enable the new toggle
2. Leave one replacement curio in `maininv`
3. Wait for one studied curio to finish
4. Confirm the replacement is moved into study automatically
5. Confirm nothing happens if cursor already holds an item
6. Confirm nothing happens if only non-fitting curios remain
7. Confirm large curios are skipped when only small gaps are open
8. Confirm other automation features still work while auto-reload is enabled

## Recommended First Cut

If you want the smallest correct change, implement this feature as:

- one new config toggle
- one new controller class under `src/auto/`
- one tiny hook in `GameUI.tick()`
- one tiny accessor for the backing study inventory

That gives you a usable v1 without spreading automation logic across multiple UI classes.
