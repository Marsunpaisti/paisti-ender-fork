# Inspect Overlay Debug Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing inspect tooltip so normal inspect stays short while `Shift` inspect shows richer gob debugging data.

**Architecture:** Keep the existing inspect plumbing unchanged and concentrate the change in `Gob.inspect(boolean full)`. Add small private formatting helpers in `Gob.java` to render `sdt` bytes/bits plus shallow summaries of gob attributes and overlays, then reuse the existing `full` flag from inspect mode to gate the extra output.

**Tech Stack:** Java 11, Ant build (`build.xml`), existing Haven client tooltip/rendering code

---

## File Structure

- Modify: `src/haven/Gob.java`
  Responsibility: preserve the current short inspect output and append richer debug lines for `Shift` inspect using existing gob state.
- Verify: `build.xml`
  Responsibility: compile verification via `ant clean-code bin -buildfile build.xml`.
- Verify: in-game inspect mode
  Responsibility: manual smoke test for hover tooltip behavior because this repo has no automated UI test target for inspect tooltips.

### Task 1: Expand `Gob.inspect(boolean full)`

**Files:**
- Modify: `src/haven/Gob.java:1146-1155`
- Modify: `src/haven/Gob.java:1487-1503`

- [ ] **Step 1: Replace the single-string `Shift` inspect branch with a line-builder and helper calls**

```java
public String inspect(boolean full) {
    String info = String.format("%s [%d]", resid(), sdt());
    if(!full) {return info;}

    List<String> lines = new ArrayList<>();
    lines.add(info);
    lines.add(String.format("id: %d", id));

    int sdt = sdt();
    lines.add(String.format("sdt-dec: %d", sdt));
    lines.add(String.format("sdt-hex: 0x%s", Integer.toHexString(sdt)));
    lines.add(String.format("sdt-bytes: %s", inspectSdtBytes()));
    lines.add(String.format("sdt-bits: %s", inspectSdtBits(sdt)));

    String attribs = inspectAttribs();
    if(attribs != null) {
        lines.add(String.format("attribs: %s", attribs));
    }

    String overlays = inspectOverlays();
    if(overlays != null) {
        lines.add(String.format("overlays: %s", overlays));
    }

    String mats = CustomizeVarMat.formatMaterials(this);
    if(mats != null) {
        lines.add(mats);
    }

    return String.join("\n", lines);
}
```

- [ ] **Step 2: Add a raw-`sdt` byte formatter beside `sdt()` / `sdtm()`**

```java
private String inspectSdtBytes() {
    Message sdt = sdtm();
    if(sdt == null || sdt.eom()) {
        return "none";
    }

    byte[] data = sdt.bytes();
    StringBuilder buf = new StringBuilder();
    for(int i = 0; i < data.length; i++) {
        if(i > 0) {
            buf.append(' ');
        }
        buf.append(String.format("%02x", data[i] & 0xff));
    }
    return buf.toString();
}
```

- [ ] **Step 3: Add an `sdt` bit-string formatter for quick flag inspection**

```java
private static String inspectSdtBits(int sdt) {
    return Integer.toBinaryString(sdt);
}
```

- [ ] **Step 4: Add a shallow attribute summary helper using the existing gob attribute map**

```java
private String inspectAttribs() {
    Map<Class<? extends GAttrib>, GAttrib> attrs = cloneattrs();
    if(attrs.isEmpty()) {
        return "none";
    }

    List<String> names = new ArrayList<>();
    for(Class<? extends GAttrib> cl : attrs.keySet()) {
        names.add(cl.getSimpleName());
    }
    Collections.sort(names);
    return String.join(", ", names);
}
```

- [ ] **Step 5: Add a shallow overlay summary helper using overlay resource ids where available**

```java
private String inspectOverlays() {
    List<String> names = new ArrayList<>();
    synchronized(ols) {
        for(Overlay ol : ols) {
            String name = null;
            if(ol.res != null) {
                try {
                    name = ol.res.get().name;
                } catch(Loading ignored) {
                    name = "loading";
                }
            }
            if(name == null) {
                name = String.format("overlay#%d", ol.id);
            }
            names.add(name);
        }
    }
    if(names.isEmpty()) {
        return "none";
    }
    Collections.sort(names);
    return String.join(", ", names);
}
```

- [ ] **Step 6: Build the client to verify the one-file change compiles**

Run: `ant clean-code bin -buildfile build.xml`
Expected: `BUILD SUCCESSFUL`

### Task 2: Manual Inspect Smoke Test

**Files:**
- Verify: `src/me/ender/CustomCursors.java`
- Verify: `src/haven/MapView.java`
- Verify: runtime inspect tooltip behavior in client

- [ ] **Step 1: Launch the client and enter the game with inspect mode available**

Run: `java -jar bin/hafen.jar`
Expected: client launches successfully

- [ ] **Step 2: Verify normal inspect remains short**

Manual check:

```text
1. Enable Inspect mode from the menu or keybind.
2. Hover any gob without holding Shift.
3. Confirm the tooltip still shows only: <resid> [<sdt>]
```

Expected: no extra debug lines appear without `Shift`

- [ ] **Step 3: Verify `Shift` inspect shows the expanded debug payload**

Manual check:

```text
1. Keep Inspect mode enabled.
2. Hold Shift while hovering a gob.
3. Confirm the tooltip includes:
   - id:
   - sdt-dec:
   - sdt-hex:
   - sdt-bytes:
   - sdt-bits:
   - attribs:
   - overlays:
```

Expected: the expanded tooltip appears only while `Shift` is held

- [ ] **Step 4: Verify partial-loading safety and tile behavior**

Manual check:

```text
1. Hover several different gobs, including ones with overlays/equipment if available.
2. Hover terrain tiles while inspect mode is active.
3. Confirm there are no tooltip crashes and tile inspect behavior is unchanged.
```

Expected: gob tooltips remain stable and tile inspect still shows tile data only

- [ ] **Step 5: Commit**

```bash
git add src/haven/Gob.java
git commit -m "feat: expand shift inspect tooltip for gob debugging"
```

Expected: commit created only after build and manual verification succeed

## Self-Review

### Spec coverage

- Preserve short non-`Shift` inspect: covered in Task 1 Step 1 and Task 2 Step 2
- Add id / `sdt` decimal / hex / raw bytes / bits: covered in Task 1 Steps 1-3 and Task 2 Step 3
- Add attribute and overlay summaries: covered in Task 1 Steps 4-5 and Task 2 Step 3
- Preserve material dump: covered in Task 1 Step 1
- Keep tile inspect unchanged and tolerate loading: covered in Task 2 Step 4

No spec gaps found.

### Placeholder scan

- No `TODO`, `TBD`, or deferred implementation markers remain.
- Each code-changing step includes the concrete code to add.
- Each verification step includes an exact command or explicit manual procedure.

### Type consistency

- Helper names are consistent across the plan: `inspectSdtBytes`, `inspectSdtBits`, `inspectAttribs`, `inspectOverlays`
- `Gob.inspect(boolean full)` remains the sole entry point for inspect tooltip string generation
- All helper methods operate on existing `Gob` fields and methods: `id`, `sdt()`, `sdtm()`, `cloneattrs()`, `ols`
