# Debug Menu

*[中文版 / Chinese](README.md)*

A standalone debugging toolkit for Minecraft Fabric. It collects debug toggles, HUD overlays, and player behavior logging into one scrollable menu, and exposes an API so other mods can plug their own debug toggles into the same screen.

- Minecraft: 1.20.4 (default) / 1.20.1 (single source tree, target picked at build time)
- Fabric Loader: >= 0.15.0 (requires Fabric API)
- Java: 17
- Environment: client + server
- Author: liuzeen1234 (liuzeen1234@qq.com)
- License: MIT

## Features

### Unified debug menu

Open the menu with a configurable keybind (unbound by default, set it under Options → Controls → Debug Menu). The screen reads every registered debug toggle and groups them by mod ID, with scrolling when the list overflows. If nothing is registered, only the built-in HUD settings entry is shown.


### Simulate a key tap: `/click <key>`

This client command simulates one press and release after chat closes. Examples: `/click M` opens the debug menu with its default binding, `/click K` triggers the action currently bound to K, `/click F5` switches perspective, and `/click SPACE` taps space. Key names are case-insensitive with autocomplete and follow current key bindings. Only single keyboard keys are supported; combinations, held keys, mouse input, and OS shortcuts are excluded. Migrated from AI Helper; AI Helper is not required.

### HUD overlays

- **Entity health** (top-right): shows the name and health of the entity under your crosshair as `[name][current/max]`. Non-living entities render as `[name][-/-]`. Detailed NBT display can be enabled; the client requests entity NBT from the server and caches the response. Trace distance is configurable (1–256, default 128).
- **Held item info** (top-left): shows the main-hand item name and stack count. Advanced mode adds durability and the full set of NBT tags, rendered at reduced scale with automatic line wrapping.

### Live player behavior log

Once enabled, player actions are written through the `DebugMenu` logger:

- Combat and status: attacks, damage taken, death, hunger changes
- Movement and pose: jumping, movement, sprint / sneak / swim / fly transitions
- Items and interaction: dropping items, hotbar switching, item use, block right-click, block breaking
- Client input: key presses, mouse clicks and scroll, screen open/close

### List loaded mods: `/showmods`

Type the command in chat to print the mods loaded in the current instance, one per line:

- `/showmods`: lists only **feature mods** (green) and **inactive mods**, hiding libraries/built-ins.
- `/showmods all`: lists **everything**, including greyed-out library/built-in entries.

Each line is `[tag] display name [mod id]`:

- **Display name**: prefers the name registered via `DebugMenuApi.setModDisplayName`; when none is registered, it falls back to the mod name Fabric detects (`name` in `fabric.mod.json`). The mod id is always appended for precise identification.
- **Categories** (tags are rendered in Chinese in-game):
  - **Feature mod** (green, no prefix) — a regular functional mod.
  - **Library/built-in** (grey, `[库]`) — the loader, Java, Minecraft, the Fabric API family (`fabric-*`) and similar base libraries; hidden by default, shown only with `/showmods all`.
  - **Inactive** (gold, `[未生效·仅XX端]`) — the environment declared in the metadata does not match the current side (e.g. a server-only mod on the client), so it is listed but does nothing on this side, kept distinct from loaded feature mods.

Entries are ordered "feature → library → inactive", then by mod id, with a count summary on the last line. This is a client-side command (`fabric-command-api-v2`), usable in both single-player and multiplayer.

Both forms also scan the `mods/` folder in the game directory and report jars that exist on disk but are **not active** (the runtime mod list never shows these):

- **Disabled** (light purple, `[已禁用]`) — jars whose filename ends in `.disabled`, manually turned off.
- **Not loaded** (red, `[未加载]`) — a plain `.jar` whose id is not in the runtime loaded set: usually a load failure, a missing dependency, or a non-Fabric jar.

The scan reads `fabric.mod.json` inside each jar for its name, version and id (`[tag] name version [id] (filename)`); when the mod name can't be resolved (non-Fabric jar / no `fabric.mod.json` / parse failure), it falls back to the top-level Java package name inferred from the jar's `.class` entries (e.g. `com.example`, marked `(包名)`), and only shows the bare filename when even that can't be determined. The section is omitted when there are no disabled/unloaded jars. (Tags are rendered in Chinese in-game.)

### Detect shader packs: `/showshaders`

Scans the `shaderpacks/` folder in the game directory, prints the detected shader packs one per line, and marks the currently enabled one:

- Walks the `.zip` files and subfolders in the directory; an entry containing a `shaders/` directory is treated as a shader pack, tagged `[光影]` (light purple); otherwise `[非光影]` (grey, likely a stray file).
- Shader packs have no standard metadata name, so the display name is the **file/folder name** (`.zip` suffix stripped); folder-form packs are additionally marked `(文件夹)`.
- The enabled shader is read from Iris (`config/iris.properties`) or OptiFine (`optionsshaders.txt`); the matching entry is prefixed with a green `[启用中]`, and a `当前启用: xxx` (currently enabled) line is appended. When none is set it shows "no shader enabled"; when the config names one that isn't found in the folder, it warns accordingly.
- It respects the shader **master toggle**: in Iris the `shaderPack=` key only records the *last selected* pack and is not cleared when shaders are globally disabled, so the check also reads `enableShaders` — a value of `false` is always treated as "not enabled", avoiding a "disabled but shown as enabled" bug. OptiFine's off states (`(internal)` / `OFF` / `none`) are likewise treated as not enabled.
- It first checks whether a **shader loader is present**: shaders can only be active when Iris (or Oculus) / OptiFine is actually loaded at runtime. If no loader is present (e.g. Iris disabled as `.jar.disabled`), the residual `shaderPack=` in the config is ignored, no pack is marked enabled, and the footer notes "no shader loader detected, shaders won't apply; residual selection in config: X".
- It detects **load/compile failures**: when Iris is installed, it reflectively calls the Iris public API (`IrisApi.getInstance().isShaderPackInUse()`) to check whether a shader is *actually* in use — that method returns false when a pack fails to compile. If the config selects a pack and the file exists but Iris reports it isn't in use, that line is marked red `[未生效·可能加载失败]` (inactive, likely load failure), with a footer noting "selected X, but Iris reports it isn't active (check logs/latest.log)". Reflection avoids a hard dependency on Iris. OptiFine has no equivalent API, so when a loader is present but runtime state can't be queried, it falls back to treating the selected pack as enabled.

Also a client-side command (`fabric-command-api-v2`). (Tags are rendered in Chinese in-game.)

### Persistent configuration

Toggle states and HUD settings are stored in `config/debug-menu.json` and saved immediately on change, so they survive a restart.

## API for other mod developers

Register a toggle during your mod's initialization and the debug menu builds the UI for it automatically:

```java
DebugMenuApi.register(new DebugToggleEntry(
    "my-mod",                    // owning mod ID (used for grouping)
    "my-mod:feature_debug",      // unique key
    "Feature Debug",             // display name in the menu
    () -> myDebugEnabled,        // getter
    v -> { myDebugEnabled = v; saveConfig(); }   // setter
));
```

Other available methods:

- `DebugMenuApi.registerAll(Collection<DebugToggleEntry>)` — register in bulk
- `DebugMenuApi.isEnabled(String key)` — query a toggle from your own code
- `DebugMenuApi.getEntries()` / `getEntriesByMod()` / `getEntry(key)` — read registered entries

The registry is backed by a `CopyOnWriteArrayList`, so reads are safe across threads.

### Custom group display name

The menu groups entries by `modId` and uses `modId` as the group header. To show a friendlier title, register a display name once during init:

```java
DebugMenuApi.setModDisplayName("my-mod", "My Mod");
```

- Applies to all entries under that `modId` (boolean toggles / numeric sliders / multi-state switches); call it just once.
- When not set, the header falls back to `modId`, so it is fully backward compatible.
- Grouping, collapsing and lookups still key off `modId`; changing the display name does not affect them.
- Passing `null` or a blank string clears the registered name (falls back to `modId`); `getModDisplayName(modId)` reads the current name (returns `modId` when unset).

### Numeric entry (slider, with server sync)

When you need an integer value constrained by min/max/step, register a `DebugValueEntry` and the menu renders it as a slider:

```java
DebugMenuApi.registerValue(new DebugValueEntry(
    "my-mod",                // owning mod ID
    "my-mod:spawn_rate",     // unique key
    "Spawn Rate",            // display name
    0, 100,                  // min / max (inclusive)
    () -> spawnRate,         // getter
    v -> { spawnRate = v; saveConfig(); }   // setter (value is clamped to [min, max] internally)
));
```

Key conventions:

- **Register on both sides**: the same `key` must be registered **once on the client and once on the server**. The client entry drives the UI (local slider display, sends packets); the server entry runs on the server main thread when a sync packet arrives. The two are matched by the shared `key`.
- **Side tagging**: each entry is tagged with `DebugValueEntry.Side` (`CLIENT` / `SERVER` / `BOTH`). In single-player the client and integrated server share one JVM, so side filtering avoids double-rendering the UI and updating the wrong object on write-back. Use `BOTH` only when both getters/setters point at the **same state**.
- **Permission**: before applying a client value, the server runs a permission check that defaults to requiring permission level `>= 2`. Override it by passing a custom `BiPredicate<ServerPlayerEntity, Integer>` to the full constructor.
- **Options**: the full constructor supports a custom step (`step > 0`) and a unit suffix (e.g. `"blocks"`, `"%"`).
- Other methods: `registerAllValues(...)` to bulk register, `getValueEntry(key)` / `getValueEntry(key, side)` to query, `getValueEntriesByMod(side)` to group by mod (filtered by side and de-duplicated).

### Conditional / nested toggles

A toggle can be shown only when a condition holds, letting you build a "parent toggle → child option" hierarchy. Pass a visibility predicate to `DebugToggleEntry`:

```java
// Shown only while the parent boolean toggle my-mod:feature is on
DebugMenuApi.register(new DebugToggleEntry(
    "my-mod", "my-mod:detail", "Detail Sub-option",
    () -> detailOn, v -> { detailOn = v; save(); },
    DebugMenuApi.visibleWhenEnabled("my-mod:feature")));

// Shown only while the parent multi-state switch my-mod:mode is "Advanced" or "Expert"
DebugMenuApi.register(new DebugToggleEntry(
    "my-mod", "my-mod:expert_opt", "Expert Option",
    () -> expertOn, v -> { expertOn = v; save(); },
    DebugMenuApi.visibleWhenOption("my-mod:mode", "Advanced", "Expert")));
```

- `visibleWhenEnabled(parentKey)`: visible while the parent boolean toggle is on; a missing parent key counts as off.
- `visibleWhenOption(parentKey, states...)`: visible while the parent multi-state switch's current state matches one of `states`; a missing key or non-matching state hides it.

### Multi-state switch (custom state names)

Besides on/off boolean toggles, you can register a switch with **multiple states whose names are fully custom** (e.g. a language selector). The menu renders it as a button that cycles through the states on click:

```java
DebugMenuApi.registerOption(new DebugOptionEntry(
    "my-mod",                              // owning mod ID (used for grouping)
    "my-mod:language",                     // unique key
    "Language",                            // display name
    java.util.List.of("English", "简体中文", "日本語"),  // state names (order = cycle order)
    () -> currentLanguage,                 // getter: return the current state name
    v -> { currentLanguage = v; saveConfig(); }        // setter: store the new state name
));
```

Notes:

- State is identified by **name (String)**. The `getter` should return one of the listed states; if it returns an invalid name (or `null`), the menu falls back to the first state instead of crashing.
- A multi-state switch is a **client-side** concept (like boolean toggles): state changes only on the client, with no server sync.
- Other methods: `registerAllOptions(...)` to bulk register, `getSelectedOption(String key)` to query the current state name, and `getOptionEntries()` / `getOptionEntriesByMod()` / `getOptionEntry(key)` to read registered entries.

## Building

The default target comes from `default_mc` in `gradle.properties` (currently **1.20.4**), so plain commands just work:

```powershell
./gradlew build
./gradlew runClient
```

Switch targets with `-Pmc` (quote it in PowerShell):

```powershell
./gradlew build "-Pmc=1.20.1"
./gradlew runClient "-Pmc=1.20.1"
```

Artifacts land in `build/libs/` with the game version in the file name, e.g. `debug-menu-mc1.20.4-1.0.0.jar`.

> Compilation is pinned to JDK 17 (see `org.gradle.java.home` in `gradle.properties` and the toolchain in `build.gradle`). If the JDK 17 path differs on another machine, edit that one line in `gradle.properties`.

Yarn mappings and Fabric API versions per target live in the `supportedVersions` map in `build.gradle`; adding a new target is one entry.

## License

Released under the [MIT License](LICENSE).

```
MIT License

Copyright (c) 2026 liuzeen1234

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
