package me.liam.echoBoxAFK;

import ch.njol.skript.variables.Variables;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;

public final class Main extends JavaPlugin implements Listener {

    private final Set<Player> afkPlayers = new HashSet<>();
    private final Map<Player, Long> afkTimers = new HashMap<>();

    private int rewardQuantity;
    private long rewardInterval;
    private Map<String, Double> rankMultipliers;
    private Map<String, String> rankPermissions;

    private String reward_message;
    private String item_name_reward;

    private String filledColor;
    private String emptyColor;
    private String bracketColor;

    private String time_left_color;
    private String seconds_and_minutes_colors;
    private String line_color;

    private Location corner1;
    private Location corner2;

    private boolean isWorldGuardEnabled;
    private String worldGuardRegion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        loadConfigValues();
        checkAfkPlayers();
        startRewardTask();

        isWorldGuardEnabled = Objects.requireNonNull(getConfig().getString("options.worldguard-api")).equalsIgnoreCase("enabled");

        if (isWorldGuardEnabled) {
            Plugin wgPlugin = getServer().getPluginManager().getPlugin("WorldGuard");
            if (wgPlugin instanceof WorldGuardPlugin) {
                logInfo(ChatColor.GREEN + "Started AFK system...");
                logInfo("WorldGuard Enabled? > True");
                logInfo("Changing To Normal Afk Region? > False");
                logInfo("WorldGuard Region? > " + worldGuardRegion);
            } else {
                logInfo(ChatColor.GREEN + "Started AFK system...");
                logWarning("WorldGuard Enabled? > False");
                logInfo("Changing To Normal Afk Region? > True");
                logWarning("Couldn't find WorldGuard, disabling API crossing...");
                isWorldGuardEnabled = false;
            }
        } else {
            logInfo(ChatColor.GREEN + "Started AFK system...");
            logWarning("WorldGuard Enabled? > False");
            logInfo("Changing To Normal Afk Region? > True");
        }
    }

    public void logInfo(String message) {
        Bukkit.getLogger().info(message);
    }

    public void logWarning(String message) {
        Bukkit.getLogger().warning(message);
    }

    public void loadConfigValues() {
        this.rewardQuantity = getConfig().getInt("general.amount", 1);
        this.rewardInterval = getConfig().getLong("general.int_seconds", 90);

        String worldName = getConfig().getString("afk_region.corner1.world", "world");
        this.corner1 = new Location(Bukkit.getWorld(worldName),
                getConfig().getDouble("afk_region.corner1.x", 760),
                getConfig().getDouble("afk_region.corner1.y", 60),
                getConfig().getDouble("afk_region.corner1.z", -74));

        this.corner2 = new Location(Bukkit.getWorld(worldName),
                getConfig().getDouble("afk_region.corner2.x", 750),
                getConfig().getDouble("afk_region.corner2.y", 68),
                getConfig().getDouble("afk_region.corner2.z", -90));

        this.rankMultipliers = new HashMap<>();
        if (getConfig().isConfigurationSection("multipliers")) {
            for (String rank : Objects.requireNonNull(getConfig().getConfigurationSection("multipliers")).getKeys(false)) {
                double multiplier = getConfig().getDouble("multipliers." + rank, 1.0);
                this.rankMultipliers.put(rank, multiplier);
            }
        }

        this.rankPermissions = new HashMap<>();
        if (getConfig().isConfigurationSection("permissions")) {
            for (String rank : Objects.requireNonNull(getConfig().getConfigurationSection("permissions")).getKeys(false)) {
                String permission = getConfig().getString("permissions." + rank);
                this.rankPermissions.put(rank, permission);
            }
        }

        this.filledColor = getConfig().getString("progress_bar.filled_color", "&2");
        this.emptyColor = getConfig().getString("progress_bar.empty_color", "&7");
        this.bracketColor = getConfig().getString("progress_bar.bracket_color", "&8");
        this.worldGuardRegion = getConfig().getString("options.worldguard-region", "null");

        this.time_left_color = getConfig().getString("action_bar.time_left_color", "&a");
        this.seconds_and_minutes_colors = getConfig().getString("action_bar.seconds_and_minutes_color", "&a");
        this.line_color = getConfig().getString("action_bar.line_color", "&a");

        this.reward_message = getConfig().getString("messages.received_reward", "&eYou received AFK Vouchers.");
        this.item_name_reward = getConfig().getString("messages.item_name_reward", "&eAFK Voucher");
    }

    private void startRewardTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : afkPlayers) {
                    if (player.isOnline() && isAfk(player)) {
                        long afkStartTime = afkTimers.get(player);
                        long elapsed = System.currentTimeMillis() - afkStartTime;
                        long totalRewardTime = rewardInterval * 1000;

                        if (elapsed >= totalRewardTime) {
                            Bukkit.getLogger().info("Granting reward to: " + player.getName());
                            grantReward(player);
                            afkTimers.put(player, System.currentTimeMillis());
                        } else {
                            notifyTimeRemaining(player);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    private void notifyTimeRemaining(Player player) {
        long timeSpent = afkTimers.getOrDefault(player, System.currentTimeMillis());
        long elapsed = System.currentTimeMillis() - timeSpent;

        long totalRewardTime = rewardInterval * 1000;

        double percentageElapsed = (double) elapsed / totalRewardTime * 100;
        percentageElapsed = Math.min(Math.max(percentageElapsed, 0), 100);

        int filledBlocks = (int) (percentageElapsed / 100 * 12);
        int emptyBlocks = 12 - filledBlocks;

        StringBuilder progressBar = new StringBuilder(bracketColor + "[");

        for (int i = 0; i < filledBlocks; i++) {
            progressBar.append(filledColor).append("|");
        }
        for (int i = 0; i < emptyBlocks; i++) {
            progressBar.append(emptyColor).append("|");
        }

        progressBar.append(bracketColor).append("]");

        String mainTitle = ChatColor.translateAlternateColorCodes('&', progressBar.toString());
        String percentText = ChatColor.GREEN + String.valueOf((int) percentageElapsed) + "%";

        long secondsLeft = (totalRewardTime - elapsed) / 1000;

        String actionBarMessage;
        if (secondsLeft >= 60) {
            long minutes = secondsLeft / 60;
            long seconds = secondsLeft % 60;
            actionBarMessage = ChatColor.translateAlternateColorCodes('&', time_left_color + "Time Left " + line_color + "| " + seconds_and_minutes_colors + minutes + " minute/s, " + seconds + " seconds");
        } else {
            actionBarMessage = ChatColor.translateAlternateColorCodes('&', time_left_color + "Time Left " + line_color + "| " + seconds_and_minutes_colors + secondsLeft + seconds_and_minutes_colors + " seconds");
        }

        boolean rewardReady = elapsed >= totalRewardTime;

        if (rewardReady) {
            afkTimers.put(player, System.currentTimeMillis());
        } else {
            player.sendTitle(mainTitle, percentText, 0, 70, 20);
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(actionBarMessage));
        }
    }

    private void grantReward(Player player) {
        if (player.isOp()) {
            int totalRewardAmount = rewardQuantity;
            String highestRank = null;

            if (player.hasPermission(rankPermissions.get("custom"))) {
                highestRank = "custom";
            } else if (player.hasPermission(rankPermissions.get("echo"))) {
                highestRank = "echo";
            } else if (player.hasPermission(rankPermissions.get("mvp+"))) {
                highestRank = "mvp+";
            } else if (player.hasPermission(rankPermissions.get("mvp"))) {
                highestRank = "mvp";
            } else if (player.hasPermission(rankPermissions.get("vip"))) {
                highestRank = "vip";
            }

            if (highestRank != null) {
                totalRewardAmount += rankMultipliers.getOrDefault(highestRank, 0.0).intValue();
            }

            for (int i = 0; i < totalRewardAmount; i++) {
                player.getInventory().addItem(getAfkVoucher());
            }

            String rewardMessage = ChatColor.translateAlternateColorCodes('&',
                    reward_message
                            .replace("{amount}", String.valueOf(totalRewardAmount))
                            .replace("{item}", item_name_reward));
            player.sendMessage(rewardMessage);
        }
    }

    public ItemStack getAfkVoucher() {
        String afkVoucherItem = getConfig().getString("errors.skript_variable_for_item", "MATERIAL_DIAMOND");

        Object skriptVoucher = Variables.getVariable(afkVoucherItem, null, false);

        if (skriptVoucher instanceof ItemStack) {
            return (ItemStack) skriptVoucher;
        } else {
            String fallback = getConfig().getString("errors.skript_variable_not_found_fallback_item", "DIAMOND");
            Material fallbackMaterial = Material.getMaterial(fallback.toUpperCase());

            if (fallbackMaterial != null) {
                return new ItemStack(fallbackMaterial, 1);
            } else {
                Bukkit.getLogger().warning("Fallback material '" + fallback + "' is not valid. Returning default configuration item..");
                return new ItemStack(Material.DIAMOND, 1);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isAfk(player)) {
            applyInvisibility(player);
        } else {
            removeInvisibility(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isAfk(player)) {
            afkPlayers.remove(player);
            afkTimers.remove(player);
            removeInvisibility(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isInAfkRegion(player)) {
            if (!isAfk(player)) {
                afkPlayers.add(player);
                afkTimers.put(player, System.currentTimeMillis());
                applyInvisibility(player);
                notifyTimeRemaining(player);
            }
        } else if (isAfk(player)) {
            afkPlayers.remove(player);
            afkTimers.remove(player);
            removeInvisibility(player);
            player.resetTitle();
        }
    }

    private void checkAfkPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isInAfkRegion(player) && !isAfk(player)) {
                afkPlayers.add(player);
                afkTimers.put(player, System.currentTimeMillis());
                applyInvisibility(player);
            }
        }
    }

    private boolean isInAfkRegion(Player player) {
        return isWorldGuardEnabled ? isInRegion(player, worldGuardRegion) : isInCustomAfkRegion(player);
    }

    public boolean isInRegion(Player p, String worldGuardRegion) {
        com.sk89q.worldguard.protection.regions.RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        com.sk89q.worldguard.protection.regions.RegionQuery query = container.createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(p.getLocation()));
        for (ProtectedRegion pr : set) {
            if (pr.getId().equalsIgnoreCase(worldGuardRegion)) return true;
        }
        return false;
    }

    private boolean isInCustomAfkRegion(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();

        if (world == null || !world.equals(this.corner1.getWorld())) {
            return false;
        }

        double xMin = Math.min(this.corner1.getX(), this.corner2.getX());
        double xMax = Math.max(this.corner1.getX(), this.corner2.getX());
        double yMin = Math.min(this.corner1.getY(), this.corner2.getY());
        double yMax = Math.max(this.corner1.getY(), this.corner2.getY());
        double zMin = Math.min(this.corner1.getZ(), this.corner2.getZ());
        double zMax = Math.max(this.corner1.getZ(), this.corner2.getZ());

        return loc.getX() >= xMin && loc.getX() <= xMax &&
                loc.getY() >= yMin && loc.getY() <= yMax &&
                loc.getZ() >= zMin && loc.getZ() <= zMax;
    }

    private boolean isAfk(Player player) {
        return afkPlayers.contains(player);
    }

    private void applyInvisibility(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));
    }

    private void removeInvisibility(Player player) {
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
    }
}
