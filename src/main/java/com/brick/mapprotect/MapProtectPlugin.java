package com.brick.mapprotect;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Material;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Brick-MapProtect — protects the pre-existing blocks of selected maps (worlds)
 * from non-opped players, while letting them place and break their own blocks.
 *
 * Configurable behaviour (config.yml):
 *  - protected-worlds : which maps this plugin governs ("*" = all worlds).
 *  - allow-placing    : whether non-opped players may place blocks on those maps.
 *
 * Opped players (or holders of the "mapprotect.bypass" permission) are never
 * restricted. The set of player-placed blocks is persisted to placed-blocks.yml
 * so it survives restarts.
 */
public final class MapProtectPlugin extends JavaPlugin implements Listener {

    /** Locations of blocks placed by players, encoded as "world:x:y:z". */
    private final Set<String> placedBlocks = new HashSet<>();

    private File dataFile;

    // Cached config values (refreshed on enable and on /mapprotect reload).
    private Set<String> protectedWorlds = new HashSet<>();
    private boolean protectAllWorlds = false;
    private boolean allowPlacing = true;
    private boolean protectFromLiquids = true;
    private boolean protectFromFire = true;
    private String denyBreakMessage;
    private String denyPlaceMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "placed-blocks.yml");
        loadSettings();
        loadPlacedBlocks();
        getServer().getPluginManager().registerEvents(this, this);
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
        denyBreakMessage = cfg.getString("deny-break-message",
                "&cYou must be opped to break blocks in this map.");
        denyPlaceMessage = cfg.getString("deny-place-message",
                "&cBlock placing is disabled on this map.");
    }

    /** True if the plugin governs the given world. */
    private boolean isProtectedWorld(String worldName) {
        return protectAllWorlds || protectedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private boolean isExempt(Player player) {
        return player.isOp() || player.hasPermission("mapprotect.bypass");
    }

    // ---- Event handling ---------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        String worldName = block.getWorld().getName();

        // Worlds this plugin doesn't govern are left completely alone.
        if (!isProtectedWorld(worldName)) {
            return;
        }

        Player player = event.getPlayer();
        if (isExempt(player)) {
            placedBlocks.add(key(block.getLocation()));
            return;
        }

        // Placing disabled for regular players on protected maps.
        if (!allowPlacing) {
            event.setCancelled(true);
            player.sendMessage(color(denyPlaceMessage));
            return;
        }

        placedBlocks.add(key(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String worldName = block.getWorld().getName();

        // Worlds this plugin doesn't govern are left completely alone.
        if (!isProtectedWorld(worldName)) {
            return;
        }

        Player player = event.getPlayer();
        String k = key(block.getLocation());

        // Ops / bypass may break anything, including pre-existing map blocks.
        if (isExempt(player)) {
            placedBlocks.remove(k);
            return;
        }

        if (placedBlocks.contains(k)) {
            // Player-placed block — allow the break and forget it.
            placedBlocks.remove(k);
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
        if (!isProtectedWorld(to.getWorld().getName())) {
            return;
        }

        Material type = to.getType();
        // Empty / liquid destinations are fine — the flow destroys nothing.
        if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
            return;
        }

        // A real block sits here. Protect it unless a player placed it.
        if (!placedBlocks.contains(key(to.getLocation()))) {
            event.setCancelled(true);
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
        if (!isProtectedWorld(block.getWorld().getName())) {
            return;
        }
        if (!placedBlocks.contains(key(block.getLocation()))) {
            event.setCancelled(true);
        }
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
        List<String> stored = data.getStringList("placed");
        placedBlocks.addAll(stored);
        getLogger().info("Loaded " + placedBlocks.size() + " tracked placed blocks.");
    }

    private void savePlacedBlocks() {
        FileConfiguration data = new YamlConfiguration();
        data.set("placed", new ArrayList<>(placedBlocks));
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save placed-blocks.yml: " + e.getMessage());
        }
    }

    // ---- Helpers ----------------------------------------------------------

    /** Encodes a block location as "world:x:y:z". */
    private String key(Location loc) {
        return loc.getWorld().getName()
                + ":" + loc.getBlockX()
                + ":" + loc.getBlockY()
                + ":" + loc.getBlockZ();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
