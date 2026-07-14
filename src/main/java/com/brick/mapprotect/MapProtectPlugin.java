package com.brick.mapprotect;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Brick-MapProtect — protects the pre-existing blocks of selected maps (worlds)
 * while letting players place and break their own blocks.
 *
 * Configurable behaviour (config.yml):
 *  - protected-worlds : which maps this plugin governs ("*" = all worlds).
 *  - allow-placing    : whether players may place blocks on those maps.
 *
 * Only players in Creative mode may break original map blocks (or place when
 * placing is disabled); op status and permissions grant no exemption. The set
 * of player-placed blocks is persisted to placed-blocks.yml so it survives
 * restarts, and is additionally saved every few minutes so a crash loses at
 * most one save interval.
 */
public final class MapProtectPlugin extends JavaPlugin implements Listener {

    /** Autosave interval for placed-block data, in ticks (5 minutes). */
    private static final long SAVE_INTERVAL_TICKS = 5L * 60L * 20L;

    /**
     * Player-placed block coordinates per world name, packed into longs
     * (26 bits x, 26 bits z, 12 bits y — same scheme as Block#getBlockKey).
     * Avoids building a "world:x:y:z" string on every event; the string form
     * is only used when (de)serialising placed-blocks.yml.
     */
    private final Map<String, Set<Long>> placedBlocks = new HashMap<>();

    /** Per-world protection decisions, cached so hot paths never lowercase. */
    private final Map<String, Boolean> protectedWorldCache = new HashMap<>();

    private File dataFile;

    /** True when placedBlocks changed since the last save. */
    private boolean dirty = false;

    // Cached config values (refreshed on enable and on /mapprotect reload).
    private Set<String> protectedWorlds = new HashSet<>();
    private boolean protectAllWorlds = false;
    private boolean allowPlacing = true;
    private boolean protectFromLiquids = true;
    private boolean protectFromFire = true;
    private boolean protectFromExplosions = true;
    private boolean protectFromPistons = true;
    private boolean protectFromEntityGrief = true;
    private boolean clearPlacedOnRestart = false;
    private String denyBreakMessage;
    private String denyPlaceMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "placed-blocks.yml");
        loadSettings();
        loadPlacedBlocks();

        // Optional arena-reset: wipe every player-placed block on startup.
        if (clearPlacedOnRestart && !placedBlocks.isEmpty()) {
            int removed = clearPlacedBlocks();
            getLogger().info("clear-placed-on-restart: removed " + removed
                    + " player-placed block(s), map restored to original state.");
        }

        getServer().getPluginManager().registerEvents(this, this);

        // Autosave so a crash doesn't lose the whole session's tracking data.
        // Snapshot on the main thread, write the file off-thread.
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!dirty) {
                return;
            }
            dirty = false;
            List<String> snapshot = encodePlacedBlocks();
            getServer().getScheduler().runTaskAsynchronously(this, () -> writeData(snapshot));
        }, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);

        getLogger().info("Brick-MapProtect enabled — protecting "
                + (protectAllWorlds ? "all worlds" : protectedWorlds.size() + " map(s)")
                + "; placing " + (allowPlacing ? "allowed" : "disabled") + ".");
    }

    @Override
    public void onDisable() {
        savePlacedBlocks();
        getLogger().info("Brick-MapProtect disabled — placed-block data saved.");
    }

    // ---- Config -----------------------------------------------------------

    private void loadSettings() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        protectedWorlds.clear();
        protectedWorldCache.clear();
        protectAllWorlds = false;
        for (String w : cfg.getStringList("protected-worlds")) {
            if (w.equals("*")) {
                protectAllWorlds = true;
            } else {
                protectedWorlds.add(w.toLowerCase(Locale.ROOT));
            }
        }

        allowPlacing = cfg.getBoolean("allow-placing", true);
        protectFromLiquids = cfg.getBoolean("protect-from-liquids", true);
        protectFromFire = cfg.getBoolean("protect-from-fire", true);
        protectFromExplosions = cfg.getBoolean("protect-from-explosions", true);
        protectFromPistons = cfg.getBoolean("protect-from-pistons", true);
        protectFromEntityGrief = cfg.getBoolean("protect-from-entity-grief", true);
        clearPlacedOnRestart = cfg.getBoolean("clear-placed-on-restart", false);
        denyBreakMessage = cfg.getString("deny-break-message",
                "&cYou must be in creative mode to break blocks in this map.");
        denyPlaceMessage = cfg.getString("deny-place-message",
                "&cBlock placing is disabled on this map.");
    }

    /** True if the plugin governs the given world. */
    private boolean isProtectedWorld(World world) {
        return protectedWorldCache.computeIfAbsent(world.getName(),
                name -> protectAllWorlds || protectedWorlds.contains(name.toLowerCase(Locale.ROOT)));
    }

    private boolean isExempt(Player player) {
        // Only players in Creative mode may break/place original map blocks.
        // Op status and permissions do NOT count — many servers grant staff a
        // wildcard ("*") permission, which would silently let opped players
        // break protected blocks. Creative mode is the single gate.
        return player.getGameMode() == GameMode.CREATIVE;
    }

    // ---- Placed-block tracking --------------------------------------------

    /** Packs block coordinates into a long (like Block#getBlockKey). */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 38);
    }

    private static int unpackY(long key) {
        return (int) ((key << 52) >> 52);
    }

    private static int unpackZ(long key) {
        return (int) ((key << 26) >> 38);
    }

    private void track(Block block) {
        placedBlocks.computeIfAbsent(block.getWorld().getName(), n -> new HashSet<>())
                .add(pack(block.getX(), block.getY(), block.getZ()));
        dirty = true;
    }

    private void untrack(Block block) {
        Set<Long> set = placedBlocks.get(block.getWorld().getName());
        if (set != null && set.remove(pack(block.getX(), block.getY(), block.getZ()))) {
            dirty = true;
        }
    }

    private boolean isPlaced(Block block) {
        Set<Long> set = placedBlocks.get(block.getWorld().getName());
        return set != null && set.contains(pack(block.getX(), block.getY(), block.getZ()));
    }

    // ---- Event handling ---------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();

        // Worlds this plugin doesn't govern are left completely alone.
        if (!isProtectedWorld(block.getWorld())) {
            return;
        }

        Player player = event.getPlayer();
        if (!isExempt(player) && !allowPlacing) {
            // Placing disabled for regular players on protected maps.
            event.setCancelled(true);
            player.sendMessage(color(denyPlaceMessage));
            return;
        }

        // Doors, beds and double plants fire BlockMultiPlaceEvent — track
        // every position, otherwise the second half counts as "original"
        // and the placer couldn't break their own door.
        if (event instanceof BlockMultiPlaceEvent) {
            for (BlockState state : ((BlockMultiPlaceEvent) event).getReplacedBlockStates()) {
                track(state.getBlock());
            }
        } else {
            track(block);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Worlds this plugin doesn't govern are left completely alone.
        if (!isProtectedWorld(block.getWorld())) {
            return;
        }

        Player player = event.getPlayer();

        // Creative-mode players may break anything, including pre-existing map blocks.
        if (isExempt(player)) {
            untrack(block);
            return;
        }

        if (isPlaced(block)) {
            // Player-placed block — allow the break and forget it.
            untrack(block);
            return;
        }

        // Pre-existing map block and player is not exempt — deny.
        event.setCancelled(true);
        player.sendMessage(color(denyBreakMessage));
    }

    /**
     * Stops flowing water/lava from washing away or destroying original map
     * blocks (e.g. torches, redstone, plants). Liquid flowing into empty space
     * or into a player-placed block is still allowed, so building works.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        if (!protectFromLiquids) {
            return;
        }
        Block to = event.getToBlock();
        if (!isProtectedWorld(to.getWorld())) {
            return;
        }

        Material type = to.getType();
        // Empty / liquid destinations are fine — the flow destroys nothing.
        if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
            return;
        }

        if (isPlaced(to)) {
            // Player-placed block gets washed away — stop tracking the spot.
            untrack(to);
            return;
        }

        // A pre-existing map block sits here — protect it.
        event.setCancelled(true);
    }

    /**
     * Stops a bucket from being emptied onto an original map block (e.g. water
     * placed directly into tall grass or a torch destroys it without any
     * BlockFromToEvent). Creative players are exempt as usual.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        if (!isProtectedWorld(block.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        if (isExempt(player)) {
            return;
        }
        if (!allowPlacing) {
            event.setCancelled(true);
            player.sendMessage(color(denyPlaceMessage));
            return;
        }
        if (!protectFromLiquids) {
            return;
        }
        Material type = block.getType();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
            return;
        }
        // Liquid would replace or waterlog an original map block — deny.
        if (!isPlaced(block)) {
            event.setCancelled(true);
            player.sendMessage(color(denyBreakMessage));
        }
    }

    /**
     * Stops fire (including lava-started fire) from burning original map
     * blocks. Player-placed blocks may still burn.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!protectFromFire) {
            return;
        }
        Block block = event.getBlock();
        if (!isProtectedWorld(block.getWorld())) {
            return;
        }
        if (isPlaced(block)) {
            // Player-placed block burns away — stop tracking the spot.
            untrack(block);
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Stops explosions (TNT, creepers, beds, etc.) from destroying original
     * map blocks. Player-placed blocks still blow up normally.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.getEntity().getWorld(), event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.getBlock().getWorld(), event.blockList());
    }

    private void filterExplosion(World world, List<Block> blocks) {
        if (!protectFromExplosions || blocks.isEmpty() || !isProtectedWorld(world)) {
            return;
        }
        blocks.removeIf(block -> {
            if (isPlaced(block)) {
                untrack(block);
                return false;
            }
            return true; // original map block — keep it out of the explosion
        });
    }

    /**
     * Stops pistons from pushing or pulling original map blocks out of
     * position — the classic bypass for break protection. Pistons moving only
     * player-placed blocks still work, and the tracked positions follow the
     * blocks. Disable protect-from-pistons if your map relies on piston
     * contraptions that move original blocks.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePiston(event.getBlock().getWorld(), event.getBlocks(), event.getDirection(),
                event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        // getDirection() is the piston's facing; retracted blocks move the
        // opposite way (toward the piston).
        handlePiston(event.getBlock().getWorld(), event.getBlocks(),
                event.getDirection().getOppositeFace(), event);
    }

    private void handlePiston(World world, List<Block> moved, BlockFace motion,
                              org.bukkit.event.Cancellable event) {
        if (!protectFromPistons || moved.isEmpty() || !isProtectedWorld(world)) {
            return;
        }
        for (Block block : moved) {
            if (!isPlaced(block)) {
                event.setCancelled(true);
                return;
            }
        }
        // Every moved block is player-placed — let it move and re-track the
        // new positions (untrack all first so overlapping shifts don't clash).
        for (Block block : moved) {
            untrack(block);
        }
        for (Block block : moved) {
            track(block.getRelative(motion));
        }
    }

    /**
     * Stops entity griefing (endermen stealing blocks, silverfish burrowing,
     * original sand/gravel being dropped, etc.). Falling blocks landing and
     * entities changing player-placed blocks are still allowed.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!protectFromEntityGrief) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType().isAir()) {
            // Something appears in empty space (falling block lands,
            // enderman puts a block down) — nothing original is lost.
            return;
        }
        if (!isProtectedWorld(block.getWorld())) {
            return;
        }
        if (isPlaced(block)) {
            if (event.getTo().isAir()) {
                // Player-placed block is removed by the entity — forget it.
                untrack(block);
            }
            return;
        }
        event.setCancelled(true);
    }

    // ---- Commands ---------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mapprotect")) {
            return false;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mapprotect.admin")) {
                sender.sendMessage(color("&cYou don't have permission to do that."));
                return true;
            }
            loadSettings();
            sender.sendMessage(color("&aBrick-MapProtect config reloaded. Protecting "
                    + (protectAllWorlds ? "all worlds" : protectedWorlds.size() + " map(s)")
                    + "; placing " + (allowPlacing ? "allowed" : "disabled") + "."));
            return true;
        }
        sender.sendMessage(color("&e/mapprotect reload &7— reload the config"));
        return true;
    }

    // ---- Persistence ------------------------------------------------------

    private void loadPlacedBlocks() {
        if (!dataFile.exists()) {
            return;
        }
        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        int count = 0;
        for (String k : data.getStringList("placed")) {
            // key format: "world:x:y:z" — split from the right so world names
            // containing ':' still parse correctly.
            int i3 = k.lastIndexOf(':');
            int i2 = i3 > 0 ? k.lastIndexOf(':', i3 - 1) : -1;
            int i1 = i2 > 0 ? k.lastIndexOf(':', i2 - 1) : -1;
            if (i1 <= 0) {
                continue;
            }
            try {
                int x = Integer.parseInt(k.substring(i1 + 1, i2));
                int y = Integer.parseInt(k.substring(i2 + 1, i3));
                int z = Integer.parseInt(k.substring(i3 + 1));
                placedBlocks.computeIfAbsent(k.substring(0, i1), n -> new HashSet<>())
                        .add(pack(x, y, z));
                count++;
            } catch (NumberFormatException ignored) {
                // Malformed entry — skip it.
            }
        }
        getLogger().info("Loaded " + count + " tracked placed blocks.");
    }

    /** Serialises the tracked blocks to the on-disk "world:x:y:z" format. */
    private List<String> encodePlacedBlocks() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Set<Long>> entry : placedBlocks.entrySet()) {
            String world = entry.getKey();
            for (long key : entry.getValue()) {
                out.add(world + ":" + unpackX(key) + ":" + unpackY(key) + ":" + unpackZ(key));
            }
        }
        return out;
    }

    private void savePlacedBlocks() {
        dirty = false;
        writeData(encodePlacedBlocks());
    }

    private void writeData(List<String> encoded) {
        FileConfiguration data = new YamlConfiguration();
        data.set("placed", encoded);
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save placed-blocks.yml: " + e.getMessage());
        }
    }

    /**
     * Removes every tracked player-placed block from the world (sets it to
     * air), then clears the tracking set and persists the empty state.
     * Returns the number of blocks removed. Locations whose world isn't
     * loaded are simply dropped from tracking.
     */
    private int clearPlacedBlocks() {
        int removed = 0;
        for (Map.Entry<String, Set<Long>> entry : placedBlocks.entrySet()) {
            World world = getServer().getWorld(entry.getKey());
            if (world == null) {
                continue;
            }
            // Sort by chunk so each chunk is only loaded once, not revisited
            // in random HashSet order.
            List<Long> keys = new ArrayList<>(entry.getValue());
            keys.sort((a, b) -> {
                int ca = (unpackX(a) >> 4) * 31 + (unpackZ(a) >> 4);
                int cb = (unpackX(b) >> 4) * 31 + (unpackZ(b) >> 4);
                return Integer.compare(ca, cb);
            });
            for (long key : keys) {
                world.getBlockAt(unpackX(key), unpackY(key), unpackZ(key))
                        .setType(Material.AIR, false);
                removed++;
            }
        }
        placedBlocks.clear();
        savePlacedBlocks();
        return removed;
    }

    // ---- Helpers ----------------------------------------------------------

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
