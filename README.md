# SideDash

A second-screen dashboard for games. Open it in your tablet's browser and see your inventory, stats, map and recipes while you play. Inspired by sim-racing dashboards, but for non-racing games.

First game: **Minecraft 1.21.1 (NeoForge)**, built for Create.

![In game: Create goggles on the press](docs/game-goggles.jpg)
![Same moment on the tablet](docs/tablet-overview.png)

| Recipes (JEI) | World map with waypoints |
|---|---|
| ![Recipes](docs/tablet-recipes.png) | ![Map](docs/tablet-map.jpg) |

![Demo scene](docs/game-scene.jpg)

## What's on the tablet

- **Vitals**: health with absorption, hunger, saturation, armor, air, XP. Active effects with timers.
- **Inventory**: real item icons (same render as in game), durability, enchant glow. Tap an item to see its full tooltip. Double-tap a hotbar slot to select it in game.
- **World map**: every chunk you explore is saved, like a world-map mod. Pan with a finger, pinch to zoom smoothly, fullscreen. Tap to add a waypoint or teleport there. Teleport is a normal `/tp` command, so it needs cheats or op.
- **Looking at**: the block or mob under your crosshair. For Create blocks (and any addon that supports engineer's goggles) it shows the goggle info: speed, stress, fluids and so on.
- **Recipes (JEI)**: search any item, then see "how to make" and "used in". Recipes are drawn by JEI itself, so every Create and addon recipe type is supported.
- The UI follows the game's language (Russian or English).

## How it works

SideDash has two parts:

- **SideDash Hub**: a small Windows app you install once. It lives in the tray, shows a QR code for the tablet, and serves everything on your LAN (`http://<pc-ip>:25580/<key>/`). The random key keeps other devices on the network out.
- **Game adapters**: one per game. They run a localhost-only server and register with the hub through a file in `%LOCALAPPDATA%\SideDash\sources`. Each adapter ships its own dashboard page, and the hub proxies the tablet to it. For Minecraft the adapter is the SideDash mod (client-only: singleplayer and any server).

## How to use

1. Start **SideDashHub.exe**. Allow it in Windows Firewall when asked (private networks).
2. Scan the QR code from the hub window with your tablet (same Wi-Fi).
3. Start Minecraft with the mod and join a world. The tablet switches to the game by itself. `/sidedash` in chat shows the address again.

## Modpack (CurseForge)

`modpack/` builds a CurseForge import zip: Create 6, about 20 popular Create addons, JEI, Jade, Xaero's Minimap and World Map, plus Sodium, ModernFix and FerriteCore for performance.

```
python modpack/resolve.py            # pick latest 1.21.1 NeoForge versions + dependencies -> manifest.json
./gradlew build
python modpack/pack.py               # -> modpack/SideDash-Create-Pack-<ver>.zip
```

In CurseForge: **Create Custom Profile → Import** and select the zip. Pre-built zips are on the Releases page.

Left out on purpose:

- Create Aeronautics and Create Stuff 'N Additions: their CurseForge ids can't be resolved automatically yet.
- Bits 'n' Bobs: it refuses to load next to TFMG 1.2.0.

## Development

- JDK 21. `./gradlew runClient` starts a dev client; put test mods in `run/mods`.
- `./gradlew runClient -PquickWorld=<save folder>` loads a world directly.
- Code map:
  - `hub/`: .NET 9 tray app (Kestrel + WinForms): LAN server, QR, adapter registry, reverse proxy.
  - `WebServer`: the mod's tiny localhost HTTP server, java.base only. Live data goes over Server-Sent Events.
  - `GameData`: all game reads, always run on the client thread.
  - `Render`: offscreen render to PNG, used for icons and recipes.
  - `SideDashJei`: optional JEI bridge.
  - Dashboard page: `src/main/resources/web/index.html`.

## Roadmap

- More games: Baldur's Gate 3 (journal and inventory through Script Extender), Green Hell (stats through a BepInEx mod).
