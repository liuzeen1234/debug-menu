# Debug Menu

*[中文版 / Chinese](README.md)*

A standalone debugging toolkit for Minecraft Fabric. It collects debug toggles, HUD overlays, and player behavior logging into one scrollable menu, and exposes an API so other mods can plug their own debug toggles into the same screen.

- Minecraft: 1.20.1 (default) / 1.20.4 (single source tree, target picked at build time)
- Fabric Loader: >= 0.15.0 (requires Fabric API)
- Java: 17
- Environment: client + server
- Author: liuzeen1234 (liuzeen1234@qq.com)
- License: MIT

## Features

### Unified debug menu

Open the menu with a configurable keybind (unbound by default, set it under Options → Controls → Debug Menu). The screen reads every registered debug toggle and groups them by mod ID, with scrolling when the list overflows. If nothing is registered, only the built-in HUD settings entry is shown.

### HUD overlays

- **Entity health** (top-right): shows the name and health of the entity under your crosshair as `[name][current/max]`. Non-living entities render as `[name][-/-]`. Detailed NBT display can be enabled; the client requests entity NBT from the server and caches the response. Trace distance is configurable (1–256, default 128).
- **Held item info** (top-left): shows the main-hand item name and stack count. Advanced mode adds durability and the full set of NBT tags, rendered at reduced scale with automatic line wrapping.

### Live player behavior log

Once enabled, player actions are written through the `DebugMenu` logger:

- Combat and status: attacks, damage taken, death, hunger changes
- Movement and pose: jumping, movement, sprint / sneak / swim / fly transitions
- Items and interaction: dropping items, hotbar switching, item use, block right-click, block breaking
- Client input: key presses, mouse clicks and scroll, screen open/close

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

The default target comes from `default_mc` in `gradle.properties` (currently **1.20.1**), so plain commands just work:

```powershell
./gradlew build
./gradlew runClient
```

Switch targets with `-Pmc` (quote it in PowerShell):

```powershell
./gradlew build "-Pmc=1.20.4"
./gradlew runClient "-Pmc=1.20.4"
```

Artifacts land in `build/libs/` with the game version in the file name, e.g. `debug-menu-mc1.20.1-1.0.0.jar`.

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
