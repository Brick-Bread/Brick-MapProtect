# Changelog

All notable changes to this project are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.0] - 2026-07-14

### Added
- **Explosion protection** (`protect-from-explosions`, default `true`): TNT,
  creepers, beds etc. no longer destroy original map blocks; player-placed
  blocks still blow up normally.
- **Piston protection** (`protect-from-pistons`, default `true`): pistons can
  no longer push/pull original map blocks out of position — the classic bypass
  for break protection. Pistons moving only player-placed blocks still work,
  and the tracking follows the moved blocks.
- **Entity-grief protection** (`protect-from-entity-grief`, default `true`):
  endermen, silverfish, falling original sand/gravel etc. can no longer alter
  original map blocks.
- **Bucket protection**: emptying a water/lava bucket directly onto an original
  block (tall grass, torches, waterlogging) is now denied for non-Creative
  players; buckets also respect `allow-placing: false`.
- **Autosave**: placed-block data is now saved every 5 minutes (write happens
  off the main thread), so a crash loses at most one interval instead of the
  whole session.

### Fixed
- **Doors, beds and double plants are now fully tracked.** Previously only the
  clicked half was recorded, so players couldn't break the other half of their
  own door/bed (multi-block places fire `BlockMultiPlaceEvent`).
- **Stale tracking entries.** Player-placed blocks destroyed by fire, liquids,
  explosions or entities are now removed from tracking, instead of leaving
  entries that grew the data file forever and could mark a future original
  block at that spot as breakable.
- Outdated config comment claiming "Ops can always place" — the exemption has
  been Creative mode (never op status) since 1.4.0.

### Changed
- **Performance:** placed blocks are stored as packed coordinates per world and
  world-protection checks are cached, so hot paths (liquid flow fires for every
  spreading water/lava block) no longer allocate a `"world:x:y:z"` string and a
  lowercased world name per event. The `placed-blocks.yml` format is unchanged.
- Startup arena reset (`clear-placed-on-restart`) processes blocks chunk by
  chunk instead of in random order, so each chunk loads only once.

## [1.4.1] - 2026-07-14

### Fixed
- **Opped players could still break original map blocks.** Breaking (and
  placing when `allow-placing` is false) was also exempted by the
  `mapprotect.bypass` permission, which staff/ops commonly inherit via a
  wildcard (`*`) permission. Exemption is now gated **solely** on Creative game
  mode; op status and permissions grant no override.

### Removed
- `mapprotect.bypass` permission — Creative mode is the single gate.

## [1.4.0] - 2026-07-13

### Changed
- **Breaking original map blocks is now gated on Creative game mode instead of
  op status.** Only players in Creative can break original blocks (and place
  when `allow-placing` is false); an opped player in Survival cannot. Use
  `/gamemode creative` to edit protected maps.
- `mapprotect.bypass` now defaults to `false` (was `op`) and serves as an
  explicit override for breaking/placing without Creative mode.
- Default `deny-break-message` updated to reference Creative mode.

## [1.3.0] - 2026-07-13

### Added
- **Arena reset** (`clear-placed-on-restart`, default `false`): when enabled,
  every player-placed block is removed (set to air) on server startup,
  restoring the map to its original state. Off by default, so builds persist.

## [1.2.0] - 2026-07-13

### Added
- **Liquid protection** (`protect-from-liquids`): flowing water and lava can no
  longer wash away or destroy original map blocks. Player-placed blocks and
  open-space flow are unaffected, so building still works.
- **Fire protection** (`protect-from-fire`): fire and lava can no longer burn
  original map blocks.

## [1.1.0] - 2026-07-13

### Added
- `protected-worlds` config: choose exactly which maps are protected, or use
  `*` for all worlds. Unlisted worlds are left untouched.
- `allow-placing` config: toggle whether regular players may place blocks on
  protected maps.
- `/mapprotect reload` command and `mapprotect.admin` permission for live
  config reloads.

### Changed
- Renamed permission namespace to `mapprotect.*`.
- Clearer, fully-commented `config.yml`.

## [1.0.0] - 2026-07-13

### Added
- Initial release: non-opped players cannot break pre-existing world blocks,
  but may place blocks and break blocks that players have placed.
- Placed-block tracking persisted to `placed-blocks.yml` across restarts.
