# Publishing Waypy on Modrinth

A manual checklist for creating the Waypy project on [modrinth.com](https://modrinth.com) and uploading a version. Everything you need to paste lives in this folder (`modrinth/`).

| File | Use |
|---|---|
| `summary.txt` | the short summary |
| `description.md` | the project body (Description tab) |
| `icon.png` | the project icon, 512×512 |
| `gallery/*.png` | the gallery images, 1280×720 |
| `../dist/waypy-1.0.0-beta.1.jar` | the jar to upload |

## 1. Before you start

- [ ] Log in to modrinth.com as **tunimamluk** (link the GitHub account if you like).
- [ ] Make sure the GitHub repository `tunimamluk/Waypy` is public, so the source and issue links work. Modrinth reviewers check them.
- [ ] Build the jar (`./gradlew build`) or use the committed `dist/waypy-1.0.0-beta.1.jar`.

## 2. Create the project

Click **+** (top right) → **Create a project**.

| Field | Value |
|---|---|
| Name | `Waypy` |
| URL (slug) | `waypy`. If it is taken, use `waypy-waypoints` |
| Visibility | **Public** (it is only shown after the review; you can also pick Unlisted first) |
| Summary | the text of `summary.txt`: `Client-side waypoints with coloured pins, beacon beams and block highlights. Clumps, groups, presets and layouts.` |

Click **Create project**. You land on the project's settings with a draft project.

## 3. Project settings

### General
- [ ] **Icon**: upload `modrinth/icon.png`.
- [ ] Name and summary: already set.
- [ ] **Environment** (in newer Modrinth this is per version, in older layouts under General): **Client-side: Required**, **Server-side: Unsupported**. (If the form only offers a single choice, pick **Client-side only**.)

### Description
- [ ] Paste the whole of `modrinth/description.md` into the description editor and check the preview (tables, code spans, links).

### Tags
- [ ] Project type: **Mod**.
- [ ] Categories: **Utility** and **Transportation**.
  - Utility: Waypy is a quality-of-life tool.
  - Transportation: Modrinth groups navigation aids (waypoints, minimaps) here; waypoints exist to help you get back to places.
  - Do not add others (it adds no blocks, no game mechanics, no decoration).
- [ ] Featured category (if asked): **Utility**.

### License
- [ ] License: **MIT License** (SPDX `MIT`). The license file is `LICENSE` at the root of the repository.

### Links
| Field | Value |
|---|---|
| Source code | `https://github.com/tunimamluk/Waypy` |
| Issue tracker | `https://github.com/tunimamluk/Waypy/issues` |
| Wiki | leave empty (the description covers it) |
| Discord | leave empty |

### Gallery
Upload each file in `modrinth/gallery/` (Gallery tab → **Upload an image**). Use the titles and descriptions below; mark the first one as **Featured**.

| File | Title | Description |
|---|---|---|
| `01-world-pins-and-beams.png` | Pins, beams and highlights | Waypoints as coloured pins with block icons and labels, beacon beams and highlighted blocks. |
| `02-clumps.png` | Clumps | Paint several blocks into one waypoint by holding N and walking; each clump is drawn as one merged highlight. |
| `03-group-near-and-far.png` | Groups combine from afar | Left: up close you see each waypoint of a group. Right: from farther away the group shows as one pin with its name and member count. |
| `04-menu-editor.png` | The menu | Edit name, colour, position, style, logo, opacity and text in the M menu. |
| `05-menu-group.png` | Groups in the menu | Groups are listed with their waypoints and have their own name, colour and logo. |
| `06-presets-popup.png` | Presets | Press H on a waypoint and a number key to apply a saved look. |
| `07-layouts-popup.png` | Layout presets | Stamp a whole saved group down somewhere else. |
| `08-settings.png` | Settings | Render distance, icon size, labels, group combine distance and more. |


## 4. Upload the version

Versions tab → **Create a version**.

| Field | Value |
|---|---|
| File | `dist/waypy-1.0.0-beta.1.jar` (the main jar; not a `-sources.jar`). Set it as the **primary** file |
| Version number | `1.0.0-beta.1` |
| Version title | `Waypy 1.0.0-beta.1` |
| Release channel | **Beta** |
| Loaders | **Fabric** |
| Game versions | **26.2** |
| Environment (if asked here) | Client-side: Required, Server-side: Unsupported |
| Dependencies | add project **Fabric API** (`fabric-api`) as **Required**; no specific version needed |
| Changelog | see below |

**Why Beta and not Release:** Waypy works and is covered by an automated in-game test, but it has not yet been played widely by other people on different setups (other mods, other servers, other graphics drivers). The Beta channel tells players that, and launchers that hide betas by default won't push it to everyone. Once a few people have used it without trouble, publish the next version (for example `1.0.0`) on the **Release** channel.

Suggested changelog:

```
First public release.

- Waypoints with coloured pins, beacon beams or block highlights (N / Shift+N)
- Clumps: hold N and walk to paint several blocks into one waypoint; walk back to erase
- Undo deletions for 15 seconds
- Groups that combine into one pin from afar
- Presets (H) and layout presets
- Hide everything with Y; settings for render distance, icon size, labels and more
```

Modrinth fills in the loader and game version from `fabric.mod.json` when it can; check that they read **Fabric** and **26.2** before saving.

## 5. Submit for review

- [ ] Check the project page once more (icon, summary, description, gallery, links, license, version).
- [ ] Click **Submit for review**.

Modrinth staff review every new project before it becomes public. This usually takes from a few hours to a few days. They may ask for changes (for example a clearer description or a working source link); you get a notification and can resubmit. After approval the project is live at `https://modrinth.com/mod/waypy` (or the fallback slug).

## 6. After approval

- [ ] Put the real Modrinth link into the README's Download section if the slug is not `waypy`.
- [ ] Later versions: Versions tab → Create a version, with the new jar from `dist/`, the next version number and a changelog. Only new projects are reviewed; new versions go live at once.
