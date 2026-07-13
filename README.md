<h1 align="center">🧱 Brick-MapProtect</h1>

<p align="center">
  <b>Lock down your maps. Let players build freely.</b><br>
  A lightweight Paper/Spigot plugin that stops regular players from tearing up your
  original map, while still letting them place and break their own blocks.
</p>

<p align="center">
  <img alt="Minecraft" src="https://img.shields.io/badge/Minecraft-1.21.x-62B47A">
  <img alt="Paper" src="https://img.shields.io/badge/API-Paper%2FSpigot-blue">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-orange">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-lightgrey">
</p>

---

## ✨ What it does

Regular (non-opped) players can **never break the original blocks of a protected map** —
but everything they build themselves is fair game. Perfect for minigame lobbies, parkour
maps, hub worlds, and gen-raiding arenas where the terrain must stay intact.

| Action (on a protected map) | Op / Bypass | Player · `allow-placing: true` | Player · `allow-placing: false` |
|-----------------------------|:-----------:|:------------------------------:|:-------------------------------:|
| Break an **original** map block | ✅ | ❌ | ❌ |
| **Place** a block | ✅ | ✅ | ❌ |
| Break a **player-placed** block | ✅ | ✅ | ✅ |

Worlds that aren't in your protected list are left completely untouched.

**Anti-grief extras** (on by default, configurable): flowing **water & lava** can't
wash away or destroy original map blocks, and **fire/lava can't burn** them either.

## 🚀 Installation

1. Download `Brick-MapProtect-<version>.jar` from the [Releases](../../releases) page.
2. Drop it into your server's `plugins/` folder.
3. Restart the server (or run `/reload confirm`).
4. Edit `plugins/Brick-MapProtect/config.yml`, then run `/mapprotect reload`.

> **Requires:** a Paper or Spigot server running **Minecraft 1.21.x** on **Java 21**.

## ⚙️ Configuration

```yaml
# Which maps (worlds) should be protected?
#   - List exact world folder names, or use "*" for every world.
#   - Worlds not listed are left completely untouched.
protected-worlds:
  - world
  - world_nether
  - world_the_end

# Can regular players place blocks on protected maps?
#   true  = players can place (and later break their own blocks)
#   false = players cannot place any blocks (ops always can)
allow-placing: true

# Stop flowing water/lava from destroying original map blocks
protect-from-liquids: true
# Stop fire/lava from burning original map blocks
protect-from-fire: true

# Wipe all player-placed blocks on restart (arena reset)?
#   true = map is restored to original state each startup
#   false = player builds persist across restarts (default)
clear-placed-on-restart: false

# Messages (supports & colour codes)
deny-break-message: "&cYou must be opped to break blocks in this map."
deny-place-message: "&cBlock placing is disabled on this map."
```

## 🎮 Commands & Permissions

| Command | Description | Permission |
|---------|-------------|------------|
| `/mapprotect reload` | Reload the config without restarting | `mapprotect.admin` |

| Permission | Description | Default |
|------------|-------------|---------|
| `mapprotect.bypass` | Break/place anything, including original map blocks | `op` |
| `mapprotect.admin`  | Use `/mapprotect` commands | `op` |

## 🔧 How it works

Every block a player places is recorded by its exact location
(`world:x:y:z`) and persisted to `placed-blocks.yml`, so the data survives
restarts. When someone tries to break a block, the plugin allows it only if
that location is a known player-placed block (or the player is exempt).
Anything else is part of the original map and is protected.

## 🏗️ Building from source

```bash
mvn clean package
# → target/Brick-MapProtect-1.3.0.jar
```

Requires JDK 21 and Maven 3.9+.

## 📄 License

Released under the [MIT License](LICENSE).
