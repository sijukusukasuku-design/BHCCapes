package id.bhc.capes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CapesPlugin extends JavaPlugin implements Listener, TabExecutor {

    private record CapeDef(String id, String name, NamespacedKey model, String permission) {}

    private static final class ActiveCape {
        ArmorStand stand;
        ItemStack item;
        float bodyYaw;
        double lastX, lastZ;
        boolean hidden;
    }

    private final Map<String, CapeDef> capes = new LinkedHashMap<>();
    private final Map<UUID, ActiveCape> active = new HashMap<>();

    private File dataFile;
    private YamlConfiguration data;

    private double offsetBack, offsetY, sneakOffsetY;
    private boolean showToOwner, hideWithElytra;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("cape");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        for (Player p : Bukkit.getOnlinePlayers()) equip(p);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(active.keySet())) removeCape(id);
    }

    private void loadSettings() {
        reloadConfig();
        capes.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("capes");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection c = sec.getConfigurationSection(id);
                if (c == null) continue;
                NamespacedKey model = NamespacedKey.fromString(c.getString("item-model", ""));
                if (model == null) {
                    getLogger().warning("Cape '" + id + "' dilewati: item-model tidak valid.");
                    continue;
                }
                capes.put(id.toLowerCase(Locale.ROOT),
                        new CapeDef(id.toLowerCase(Locale.ROOT), c.getString("name", id), model, c.getString("permission", "")));
            }
        }
        offsetBack = getConfig().getDouble("settings.offset-back", 0.17);
        offsetY = getConfig().getDouble("settings.offset-y", -0.8);
        sneakOffsetY = getConfig().getDouble("settings.sneak-offset-y", -0.2);
        showToOwner = getConfig().getBoolean("settings.show-to-owner", true);
        hideWithElytra = getConfig().getBoolean("settings.hide-with-elytra", true);
    }

    private String getSelected(UUID id) {
        return data.getString("players." + id);
    }

    private void setSelected(UUID id, String capeId) {
        data.set("players." + id, capeId);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Gagal menyimpan data.yml: " + e.getMessage());
        }
    }

    private boolean canUse(Player p, CapeDef def) {
        return def.permission().isEmpty() || p.hasPermission(def.permission());
    }

    private ItemStack buildItem(CapeDef def) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(def.model());
        meta.displayName(Component.text(def.name()));
        item.setItemMeta(meta);
        return item;
    }

    private void equip(Player p) {
        removeCape(p.getUniqueId());
        String id = getSelected(p.getUniqueId());
        if (id == null) return;
        CapeDef def = capes.get(id);
        if (def == null || !canUse(p, def)) return;

        ActiveCape c = new ActiveCape();
        c.item = buildItem(def);
        c.bodyYaw = p.getLocation().getYaw();
        c.lastX = p.getLocation().getX();
        c.lastZ = p.getLocation().getZ();

        Location loc = standLocation(p, c.bodyYaw);
        c.stand = p.getWorld().spawn(loc, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setBasePlate(false);
            s.setArms(false);
            s.setSmall(false);
            s.setPersistent(false);
            s.setCollidable(false);
            s.addScoreboardTag("bhc_cape");
            s.getEquipment().setHelmet(c.item);
        });
        if (!showToOwner) p.hideEntity(this, c.stand);
        active.put(p.getUniqueId(), c);
    }

    private void removeCape(UUID id) {
        ActiveCape c = active.remove(id);
        if (c != null && c.stand != null) c.stand.remove();
    }

    private Location standLocation(Player p, float yaw) {
        Location l = p.getLocation();
        double rad = Math.toRadians(yaw);
        double x = l.getX() + Math.sin(rad) * offsetBack;
        double z = l.getZ() - Math.cos(rad) * offsetBack;
        double y = l.getY() + offsetY + (p.isSneaking() ? sneakOffsetY : 0.0);
        return new Location(l.getWorld(), x, y, z, yaw, 0f);
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private void tick() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            ActiveCape c = active.get(id);
            Player p = Bukkit.getPlayer(id);
            if (c == null) continue;
            if (p == null || !p.isOnline()) {
                removeCape(id);
                continue;
            }
            if (!c.stand.isValid()) {
                equip(p);
                continue;
            }

            Location l = p.getLocation();
            double dx = l.getX() - c.lastX, dz = l.getZ() - c.lastZ;
            c.lastX = l.getX();
            c.lastZ = l.getZ();
            boolean moving = dx * dx + dz * dz > 0.0004;

            float head = l.getYaw();
            float diff = wrap(head - c.bodyYaw);
            if (moving) {
                c.bodyYaw += diff * 0.5f;
                diff = wrap(head - c.bodyYaw);
            }
            if (diff > 75f) diff = 75f;
            else if (diff < -75f) diff = -75f;
            c.bodyYaw = head - diff;

            ItemStack chest = p.getInventory().getChestplate();
            boolean hide = p.isDead() || p.isGliding() || p.isSwimming() || p.isSleeping()
                    || p.isInvisible() || p.getGameMode() == GameMode.SPECTATOR
                    || (hideWithElytra && chest != null && chest.getType() == Material.ELYTRA);
            if (hide != c.hidden) {
                c.hidden = hide;
                c.stand.getEquipment().setHelmet(hide ? null : c.item);
            }
            if (!hide) c.stand.teleport(standLocation(p, c.bodyYaw));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) equip(p);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeCape(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        removeCape(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) equip(p);
        }, 5L);
    }

    private static Component msg(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("capes.admin")) {
                sender.sendMessage(msg("Kamu tidak punya izin.", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (active.containsKey(p.getUniqueId())) equip(p);
            }
            sender.sendMessage(msg("Config cape di-reload.", NamedTextColor.GREEN));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Perintah ini hanya untuk pemain.");
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(msg("Pakai: /cape <nama> | /cape off | /cape list", NamedTextColor.YELLOW));
            return true;
        }

        String arg = args[0].toLowerCase(Locale.ROOT);
        if (arg.equals("list")) {
            List<String> names = new ArrayList<>();
            for (CapeDef d : capes.values()) if (canUse(p, d)) names.add(d.id());
            p.sendMessage(msg("Cape tersedia: " + (names.isEmpty() ? "-" : String.join(", ", names)), NamedTextColor.AQUA));
            return true;
        }
        if (arg.equals("off")) {
            setSelected(p.getUniqueId(), null);
            removeCape(p.getUniqueId());
            p.sendMessage(msg("Cape dilepas.", NamedTextColor.GRAY));
            return true;
        }

        CapeDef def = capes.get(arg);
        if (def == null) {
            p.sendMessage(msg("Cape '" + arg + "' tidak ada. Cek /cape list", NamedTextColor.RED));
            return true;
        }
        if (!canUse(p, def)) {
            p.sendMessage(msg("Kamu belum punya cape ini.", NamedTextColor.RED));
            return true;
        }
        setSelected(p.getUniqueId(), def.id());
        equip(p);
        p.sendMessage(msg("Cape dipakai: " + def.name(), NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> all = new ArrayList<>(List.of("off", "list"));
        if (sender.hasPermission("capes.admin")) all.add("reload");
        for (CapeDef d : capes.values()) {
            if (!(sender instanceof Player p) || canUse(p, d)) all.add(d.id());
        }
        for (String s : all) if (s.startsWith(prefix)) out.add(s);
        return out;
    }
  }
