# Changelog

All notable changes to this project are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
