package com.mercadonegro;

import io.lumine.mythic.bukkit.MythicBukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuctionManager {

    public static final int CENTER_SLOT = 13;
    private static final long HARD_MAX = 1_000_000_000_000_000L; // < 2^53: seguro en double (Vault)

    private final MercadoNegroPlugin plugin;
    private final File dataFile;

    // Ajustes
    private ZoneId zone = ZoneOffset.ofHours(-6);
    private DayOfWeek day = DayOfWeek.FRIDAY;
    private int hour = 18, minute = 0, durationSeconds = 300, bidTimeoutSeconds = 30;
    private long maxBid = HARD_MAX;
    private boolean clearPrepared = true;
    private String worldName = "SpawnNuevo";

    // Estado persistente
    private String preparedItem;
    private long preparedBase;
    private String lastRunDate = "";
    private boolean active;
    private boolean mini; // true = mini mercado (independiente del evento del viernes)
    private String activeItem;
    private long basePrice, endMillis, topBid;
    private UUID leader;
    private String leaderName;
    private final Map<UUID, List<String>> pendingItems = new HashMap<>();
    private final Map<UUID, Long> pendingRefunds = new HashMap<>();

    // Estado en memoria
    private final Map<UUID, Long> bidMode = new ConcurrentHashMap<>(); // jugador -> expiración
    private Inventory inventory;
    private ItemStack baseItem;
    private BukkitTask ticker;

    // Modo prueba (simulación sin dinero real ni persistencia)
    private boolean test;
    private CommandSender testSender;
    private int previewSeconds = 60;
    private int miniSeconds = 180;
    private final List<BukkitTask> testTasks = new ArrayList<>();

    public AuctionManager(MercadoNegroPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------ ajustes / persistencia

    public void reloadSettings() {
        FileConfiguration c = plugin.getConfig();
        worldName = c.getString("world", "SpawnNuevo");
        try {
            zone = ZoneId.of(c.getString("timezone", "UTC-06:00"));
        } catch (DateTimeException e) {
            plugin.getLogger().warning("timezone inválida, usando UTC-06:00");
            zone = ZoneOffset.ofHours(-6);
        }
        try {
            day = DayOfWeek.valueOf(c.getString("schedule.day", "FRIDAY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            day = DayOfWeek.FRIDAY;
        }
        hour = Math.max(0, Math.min(23, c.getInt("schedule.hour", 18)));
        minute = Math.max(0, Math.min(59, c.getInt("schedule.minute", 0)));
        durationSeconds = Math.max(10, c.getInt("duration-seconds", 300));
        bidTimeoutSeconds = Math.max(5, c.getInt("bid-timeout-seconds", 30));
        maxBid = Math.max(1, Math.min(c.getLong("max-bid", HARD_MAX), HARD_MAX));
        clearPrepared = c.getBoolean("clear-prepared-after-auction", true);
        previewSeconds = Math.max(15, Math.min(600, c.getInt("preview-duration-seconds", 60)));
        miniSeconds = Math.max(30, Math.min(1800, c.getInt("mini-duration-seconds", 180)));
    }

    public void load() {
        reloadSettings();
        YamlConfiguration d = YamlConfiguration.loadConfiguration(dataFile);
        preparedItem = d.getString("prepared.item");
        preparedBase = d.getLong("prepared.base-price");
        lastRunDate = d.getString("last-run", "");
        active = d.getBoolean("auction.active");
        if (active) {
            activeItem = d.getString("auction.item");
            mini = d.getBoolean("auction.mini");
            basePrice = d.getLong("auction.base-price");
            endMillis = d.getLong("auction.end-millis");
            topBid = d.getLong("auction.top-bid");
            leaderName = d.getString("auction.leader-name");
            String l = d.getString("auction.leader");
            try { leader = l == null ? null : UUID.fromString(l); } catch (IllegalArgumentException e) { leader = null; }
            if (activeItem == null) active = false;
        }
        ConfigurationSection pi = d.getConfigurationSection("pending-items");
        if (pi != null) for (String k : pi.getKeys(false)) {
            try { pendingItems.put(UUID.fromString(k), new ArrayList<>(pi.getStringList(k))); }
            catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection pr = d.getConfigurationSection("pending-refunds");
        if (pr != null) for (String k : pr.getKeys(false)) {
            try { pendingRefunds.put(UUID.fromString(k), pr.getLong(k)); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        if (preparedItem != null) {
            y.set("prepared.item", preparedItem);
            y.set("prepared.base-price", preparedBase);
        }
        y.set("last-run", lastRunDate);
        boolean persist = active && !test; // la simulación nunca se persiste
        y.set("auction.active", persist);
        if (persist) {
            y.set("auction.item", activeItem);
            y.set("auction.mini", mini);
            y.set("auction.base-price", basePrice);
            y.set("auction.end-millis", endMillis);
            y.set("auction.top-bid", topBid);
            y.set("auction.leader", leader == null ? null : leader.toString());
            y.set("auction.leader-name", leaderName);
        }
        pendingItems.forEach((u, l) -> y.set("pending-items." + u, l));
        pendingRefunds.forEach((u, a) -> y.set("pending-refunds." + u, a));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("No se pudo guardar data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ ciclo de vida

    public void init() {
        if (active) {
            baseItem = createItem(activeItem);
            if (baseItem == null) {
                plugin.getLogger().severe("Ítem '" + activeItem + "' no existe; se cancela la subasta restaurada.");
                if (leader != null) refund(leader, topBid);
                reset(false);
            } else {
                buildInventory();
            }
        }
        ticker = new BukkitRunnable() {
            @Override public void run() {
                try {
                    checkSchedule();
                    if (active) {
                        if (System.currentTimeMillis() >= endMillis) finish();
                        else refreshMenu();
                    }
                } catch (Exception ex) {
                    plugin.getLogger().severe("Error en el ciclo de subasta: " + ex);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (BukkitTask t : testTasks) t.cancel();
        testTasks.clear();
        bidMode.clear();
        closeViewers();
        save();
    }

    private void checkSchedule() {
        if (active && !test && !mini) return;
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (now.getDayOfWeek() != day || now.getHour() != hour || now.getMinute() != minute) return;
        String today = now.toLocalDate().toString();
        if (today.equals(lastRunDate)) return;
        lastRunDate = today;
        save();
        // La apertura real del viernes tiene prioridad sobre simulaciones y mini mercados.
        if ((test || mini) && preparedItem != null) cancel();
        if (preparedItem == null) {
            plugin.getLogger().info("Es hora de la subasta pero no hay ítem preparado.");
            return;
        }
        String err = start();
        if (err != null) plugin.getLogger().warning("No se pudo abrir la subasta programada: " + err);
    }

    // ------------------------------------------------------------ administración

    public boolean itemExists(String id) { return createItem(id) != null; }

    public String preparedItem() { return preparedItem; }

    public long maxBid() { return maxBid; }

    public List<String> itemNames() {
        try {
            return new ArrayList<>(MythicBukkit.inst().getItemManager().getItemNames());
        } catch (Throwable t) {
            return List.of();
        }
    }

    public void prepare(String itemId, long price) {
        preparedItem = itemId;
        preparedBase = price;
        save();
    }

    /** Claves de mensaje: el mini mercado usa su propio bloque "mini." para diferenciarse del evento. */
    private String bk(String name) { return (mini ? "mini." : "broadcast.") + name; }

    public int miniSeconds() { return miniSeconds; }

    public String start() { return startAuction(false, null, durationSeconds, preparedItem, preparedBase, false); }

    /**
     * Mini mercado negro: independiente del evento del viernes. No toca el ítem/precio preparados,
     * ni la fecha de última ejecución. No puede solaparse con la apertura programada.
     * @return null si abrió; si no, la clave del mensaje de error.
     */
    public String startMini(String itemId, long price, int seconds) {
        if (active) return "admin.already-active";
        ZonedDateTime now = ZonedDateTime.now(zone);
        long toNext = Duration.between(now, nextRun(now)).getSeconds();
        if (toNext < seconds + 120L) return "mini.too-close";
        return startAuction(false, null, seconds, itemId, price, true);
    }

    /** @return null si abrió; si no, la clave del mensaje de error. */
    private String startAuction(boolean testMode, CommandSender sender, int seconds,
                                String itemId, long price, boolean miniMode) {
        if (active) return "admin.already-active";
        if (itemId == null) return "admin.not-prepared";
        ItemStack item = createItem(itemId);
        if (item == null) return "admin.item-not-found";

        active = true;
        test = testMode;
        testSender = sender;
        mini = miniMode;
        activeItem = itemId;
        basePrice = price;
        endMillis = System.currentTimeMillis() + seconds * 1000L;
        leader = null;
        leaderName = null;
        topBid = 0;
        baseItem = item;
        buildInventory();
        save();
        broadcast(plugin.messages().prefixed(bk("open"),
                "item", displayName(item, activeItem),
                "base", MoneyUtil.format(basePrice),
                "duration", MoneyUtil.time(seconds * 1000L)));
        return null;
    }

    public boolean cancel() {
        if (!active) return false;
        String label = activeLabel();
        closeViewers();
        if (leader != null) refund(leader, topBid);
        broadcast(plugin.messages().prefixed(bk("cancelled"), "item", label));
        reset(false);
        return true;
    }

    public void info(CommandSender s) {
        Messages m = plugin.messages();
        String prep = preparedItem == null ? "-" : preparedItem + " (" + MoneyUtil.format(preparedBase) + ")";
        s.sendMessage(m.prefixed("admin.info-prepared", "prepared", prep));
        if (active) {
            s.sendMessage(m.prefixed("admin.info-active",
                    "item", (mini ? "[MINI] " : "") + activeItem,
                    "time", MoneyUtil.time(endMillis - System.currentTimeMillis()),
                    "leader", leaderName == null ? "-" : leaderName,
                    "top", topBid > 0 ? MoneyUtil.format(topBid) : "-"));
        } else {
            s.sendMessage(m.prefixed("admin.info-none"));
        }
    }

    // ------------------------------------------------------------ modo prueba (preview)

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy HH:mm (xxx)", Locale.forLanguageTag("es"));

    private static final List<String> REQUIRED_KEYS = List.of(
            "prefix", "gui.title", "gui.lore",
            "broadcast.open", "broadcast.new-leader", "broadcast.winner", "broadcast.no-bids",
            "broadcast.cancelled", "broadcast.error",
            "bid.prompt", "bid.timeout", "bid.cancelled", "bid.already-pending", "bid.invalid",
            "bid.too-low", "bid.below-base", "bid.insufficient", "bid.economy-error",
            "bid.accepted", "bid.outbid",
            "result.you-won", "result.delivered-pending", "result.refund-delivered",
            "error.no-active", "error.no-permission", "error.players-only",
            "admin.usage", "admin.prepared", "admin.invalid-price", "admin.item-not-found",
            "admin.already-active", "admin.not-prepared", "admin.started", "admin.cancelled",
            "admin.reloaded", "admin.info-prepared", "admin.info-active", "admin.info-none",
            "test.tag", "test.diag-title", "test.diag-ok", "test.diag-fail", "test.started",
            "test.would-win", "test.would-be-insufficient", "test.finished", "test.cancelled",
            "test.invalid-seconds",
            "gui.mini-title", "mini.open", "mini.new-leader", "mini.winner", "mini.no-bids",
            "mini.cancelled", "mini.started", "mini.usage", "mini.invalid-minutes", "mini.too-close");

    public int previewSeconds() { return previewSeconds; }

    /** Simulación completa: sin dinero real, sin entrega de ítem, sin persistencia, mensajes solo al emisor. */
    public String preview(CommandSender sender, int seconds) {
        String err = startAuction(true, sender, seconds, preparedItem, preparedBase, false);
        if (err != null) return err;
        scheduleBots(seconds);
        if (sender instanceof Player p) openMenu(p);
        return null;
    }

    private void scheduleBots(int seconds) {
        long t = seconds * 20L;
        testBot(t / 5, "Bot_Alfa", 1.0);
        testBot(t * 2 / 5, "Bot_Beta", 1.5);
        testBot(t * 3 / 5, "Bot_Alfa", 1.2);
    }

    private void testBot(long delay, String name, double factor) {
        testTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active || !test) return;
            long raw = Math.max(minBid(), (long) Math.ceil(minBid() * factor));
            long amount = Math.min(raw, maxBid);
            if (amount < minBid()) return;
            botBid(name, amount);
        }, Math.max(1L, delay)));
    }

    private void botBid(String name, long amount) {
        Messages m = plugin.messages();
        UUID prev = leader;
        long prevBid = topBid;
        leader = UUID.nameUUIDFromBytes(("bot:" + name).getBytes(StandardCharsets.UTF_8));
        leaderName = name;
        topBid = amount;
        if (prev != null && testSender instanceof Player tp && tp.getUniqueId().equals(prev)) {
            tp.sendMessage(m.prefixed("bid.outbid", "amount", MoneyUtil.format(prevBid)));
        }
        refreshMenu();
        broadcast(m.prefixed(bk("new-leader"),
                "player", name, "amount", MoneyUtil.format(amount), "item", activeLabel()));
    }

    private ZonedDateTime nextRun(ZonedDateTime now) {
        ZonedDateTime n = now.with(TemporalAdjusters.nextOrSame(day))
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        return n.isAfter(now) ? n : n.plusWeeks(1);
    }

    private void diag(CommandSender s, String name, boolean ok, String detail) {
        s.sendMessage(plugin.messages().component(ok ? "test.diag-ok" : "test.diag-fail",
                "name", name, "detail", detail));
    }

    public void diagnostics(CommandSender s) {
        Messages m = plugin.messages();
        s.sendMessage(m.component("test.diag-title"));

        boolean eco = plugin.economy() != null;
        diag(s, "Economía (Vault)", eco, eco ? plugin.economy().getName() : "sin proveedor");

        World w = Bukkit.getWorld(worldName);
        diag(s, "Mundo " + worldName, w != null,
                w != null ? w.getPlayers().size() + " jugador(es) recibirán los mensajes" : "no existe");

        ItemStack it = createItem(preparedItem);
        diag(s, "Ítem preparado", it != null,
                preparedItem == null ? "ninguno (usa /mercadonegro <id> <precio>)"
                        : it != null ? preparedItem + " -> " + displayName(it, preparedItem)
                        : preparedItem + " no existe en MythicMobs");
        diag(s, "Precio base", preparedBase > 0 && preparedBase <= maxBid, MoneyUtil.format(preparedBase));

        File dir = plugin.getDataFolder();
        diag(s, "Carpeta de datos escribible", dir.exists() ? dir.canWrite() : dir.mkdirs(), dir.getPath());

        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = nextRun(now);
        Duration d = Duration.between(now, next);
        diag(s, "Hora en " + zone, true, now.format(FMT));
        diag(s, "Hora del servidor (informativa)", true,
                ZonedDateTime.now(ZoneId.systemDefault()).format(FMT));
        diag(s, "Próxima apertura automática", true,
                next.format(FMT) + " - faltan " + d.toDays() + "d " + d.toHoursPart() + "h " + d.toMinutesPart() + "m");
        diag(s, "Duración / modo preview", true, durationSeconds + "s real, " + previewSeconds + "s preview");

        List<String> missing = new ArrayList<>();
        for (String k : REQUIRED_KEYS) if (!m.has(k)) missing.add(k);
        diag(s, "messages.yml", missing.isEmpty(),
                missing.isEmpty() ? "todas las claves presentes" : "faltan: " + String.join(", ", missing));
    }

    // ------------------------------------------------------------ GUI

    private void buildInventory() {
        AuctionHolder holder = new AuctionHolder();
        inventory = Bukkit.createInventory(holder, 27, plugin.messages().component(mini ? "gui.mini-title" : "gui.title"));
        holder.setInventory(inventory);
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(Component.text(" "));
        pane.setItemMeta(pm);
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, pane);
        refreshMenu();
    }

    private void refreshMenu() {
        if (inventory == null || baseItem == null) return;
        ItemStack it = baseItem.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.addAll(plugin.messages().lines("gui.lore",
                "base", MoneyUtil.format(basePrice),
                "top", topBid > 0 ? MoneyUtil.format(topBid) : "-",
                "leader", leaderName == null ? "-" : leaderName,
                "time", MoneyUtil.time(endMillis - System.currentTimeMillis())));
        meta.lore(lore);
        it.setItemMeta(meta);
        inventory.setItem(CENTER_SLOT, it);
    }

    public void openMenu(Player p) {
        if (!active || inventory == null) {
            p.sendMessage(plugin.messages().prefixed("error.no-active"));
            return;
        }
        p.openInventory(inventory);
    }

    private void closeViewers() {
        if (inventory == null) return;
        for (HumanEntity v : new ArrayList<>(inventory.getViewers())) v.closeInventory();
    }

    // ------------------------------------------------------------ modo apuesta por chat

    public void startBidMode(Player p) {
        Messages m = plugin.messages();
        if (!active) {
            p.closeInventory();
            p.sendMessage(m.prefixed("error.no-active"));
            return;
        }
        UUID id = p.getUniqueId();
        final Long expiry = System.currentTimeMillis() + bidTimeoutSeconds * 1000L;
        if (bidMode.putIfAbsent(id, expiry) != null) {
            p.sendMessage(m.prefixed("bid.already-pending"));
            return;
        }
        p.closeInventory();
        p.sendMessage(m.prefixed("bid.prompt", "min", MoneyUtil.format(minBid())));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bidMode.remove(id, expiry)) {
                Player o = Bukkit.getPlayer(id);
                if (o != null) o.sendMessage(plugin.messages().prefixed("bid.timeout"));
            }
        }, bidTimeoutSeconds * 20L);
    }

    public boolean inBidMode(Player p) {
        Long e = bidMode.get(p.getUniqueId());
        return e != null && e > System.currentTimeMillis();
    }

    /** Atómico: solo un mensaje por sesión es consumido (evita doble apuesta). */
    public boolean consumeBidMode(Player p) {
        Long e = bidMode.remove(p.getUniqueId());
        return e != null && e > System.currentTimeMillis();
    }

    public void clearBidMode(UUID id) { bidMode.remove(id); }

    private long minBid() { return topBid > 0 ? topBid + 1 : basePrice; }

    // ------------------------------------------------------------ apuestas (hilo principal)

    public void processChatBid(Player p, String text) {
        Messages m = plugin.messages();
        if (!p.isOnline()) return;
        String t = text.trim();
        if (t.equalsIgnoreCase("cancelar") || t.equalsIgnoreCase("cancel")) {
            p.sendMessage(m.prefixed("bid.cancelled"));
            return;
        }
        long amount = MoneyUtil.parse(t, maxBid);
        if (amount < 0) {
            p.sendMessage(m.prefixed("bid.invalid"));
            return;
        }
        placeBid(p, amount);
    }

    private void placeBid(Player p, long amount) {
        Messages m = plugin.messages();
        if (!active || System.currentTimeMillis() >= endMillis) {
            p.sendMessage(m.prefixed("error.no-active"));
            return;
        }
        if (amount < minBid()) {
            p.sendMessage(topBid > 0
                    ? m.prefixed("bid.too-low", "current", MoneyUtil.format(topBid))
                    : m.prefixed("bid.below-base", "base", MoneyUtil.format(basePrice)));
            return;
        }
        boolean sameLeader = p.getUniqueId().equals(leader);
        long toCharge = sameLeader ? amount - topBid : amount;

        boolean enough = plugin.economy().has(p, toCharge);
        if (test) {
            // Preview: se verifica el saldo (prueba Vault) pero nunca se cobra.
            if (!enough) p.sendMessage(m.prefixed("test.would-be-insufficient", "needed", MoneyUtil.format(toCharge)));
        } else {
            if (!enough) {
                p.sendMessage(m.prefixed("bid.insufficient", "needed", MoneyUtil.format(toCharge)));
                return;
            }
            EconomyResponse r = plugin.economy().withdrawPlayer(p, toCharge);
            if (!r.transactionSuccess()) {
                plugin.getLogger().warning("Retiro fallido para " + p.getName() + ": " + r.errorMessage);
                p.sendMessage(m.prefixed("bid.economy-error"));
                return;
            }
        }

        UUID prev = leader;
        long prevBid = topBid;
        leader = p.getUniqueId();
        leaderName = p.getName();
        topBid = amount;
        if (prev != null && !sameLeader) {
            refund(prev, prevBid);
            Player pp = Bukkit.getPlayer(prev);
            if (pp != null) pp.sendMessage(m.prefixed("bid.outbid", "amount", MoneyUtil.format(prevBid)));
        }
        save();
        refreshMenu();
        p.sendMessage(m.prefixed("bid.accepted", "amount", MoneyUtil.format(amount)));
        broadcast(m.prefixed(bk("new-leader"),
                "player", p.getName(), "amount", MoneyUtil.format(amount), "item", activeLabel()));
    }

    // ------------------------------------------------------------ resolución

    private void finish() {
        if (!active) return;
        Messages m = plugin.messages();
        UUID winner = leader;
        long amount = topBid;
        String winnerName = leaderName;
        String itemId = activeItem;
        String label = activeLabel();
        closeViewers();

        if (winner == null) {
            broadcast(m.prefixed(bk("no-bids"), "item", label));
            reset(true);
            return;
        }
        ItemStack item = createItem(itemId);
        if (item == null) {
            plugin.getLogger().severe("Ítem '" + itemId + "' ya no existe; se reembolsa al líder.");
            refund(winner, amount);
            broadcast(m.prefixed("broadcast.error"));
            reset(true);
            return;
        }
        if (test) {
            if (testSender instanceof Player tp && tp.getUniqueId().equals(winner)) {
                tp.sendMessage(m.prefixed("test.would-win", "item", label));
            }
            broadcast(m.prefixed(bk("winner"),
                    "player", winnerName == null ? "?" : winnerName,
                    "amount", MoneyUtil.format(amount), "item", label));
            reset(true);
            return;
        }
        // El dinero del ganador ya fue retenido al apostar; los perdedores ya fueron reembolsados.
        Player online = Bukkit.getPlayer(winner);
        if (online != null) {
            giveItem(online, item);
            online.sendMessage(m.prefixed("result.you-won", "item", label, "amount", MoneyUtil.format(amount)));
        } else {
            pendingItems.computeIfAbsent(winner, k -> new ArrayList<>()).add(itemId);
        }
        broadcast(m.prefixed(bk("winner"),
                "player", winnerName == null ? "?" : winnerName,
                "amount", MoneyUtil.format(amount), "item", label));
        reset(true);
    }

    private void reset(boolean auctionFinished) {
        boolean wasTest = test;
        boolean wasMini = mini;
        if (wasTest && testSender != null) {
            testSender.sendMessage(plugin.messages().prefixed(auctionFinished ? "test.finished" : "test.cancelled"));
        }
        for (BukkitTask t : testTasks) t.cancel();
        testTasks.clear();
        test = false;
        testSender = null;
        mini = false;
        active = false;
        activeItem = null;
        leader = null;
        leaderName = null;
        topBid = 0;
        inventory = null;
        baseItem = null;
        bidMode.clear();
        if (auctionFinished && clearPrepared && !wasTest && !wasMini) preparedItem = null;
        save();
    }

    public void deliverPending(Player p) {
        UUID id = p.getUniqueId();
        Messages m = plugin.messages();
        boolean dirty = false;

        List<String> items = pendingItems.get(id);
        if (items != null) {
            Iterator<String> it = items.iterator();
            while (it.hasNext()) {
                String mid = it.next();
                ItemStack s = createItem(mid);
                if (s == null) continue;
                giveItem(p, s);
                p.sendMessage(m.prefixed("result.delivered-pending", "item", displayName(s, mid)));
                it.remove();
                dirty = true;
            }
            if (items.isEmpty()) pendingItems.remove(id);
        }
        Long r = pendingRefunds.get(id);
        if (r != null && deposit(p, r)) {
            pendingRefunds.remove(id);
            p.sendMessage(m.prefixed("result.refund-delivered", "amount", MoneyUtil.format(r)));
            dirty = true;
        }
        if (dirty) save();
    }

    // ------------------------------------------------------------ utilidades

    private void refund(UUID id, long amount) {
        if (test) return; // preview: nunca hay dinero real
        if (!deposit(Bukkit.getOfflinePlayer(id), amount)) {
            plugin.getLogger().severe("Reembolso fallido de " + amount + " a " + id + "; queda pendiente.");
            pendingRefunds.merge(id, amount, Long::sum);
        }
    }

    private boolean deposit(OfflinePlayer op, long amount) {
        try {
            return plugin.economy().depositPlayer(op, amount).transactionSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    private void giveItem(Player p, ItemStack item) {
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    private ItemStack createItem(String id) {
        if (id == null) return null;
        try {
            ItemStack s = MythicBukkit.inst().getItemManager().getItemStack(id);
            if (s == null || s.getType().isAir()) return null;
            return s.clone();
        } catch (Throwable t) {
            plugin.getLogger().warning("Error obteniendo ítem de MythicMobs '" + id + "': " + t);
            return null;
        }
    }

    private String displayName(ItemStack item, String fallback) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return fallback;
    }

    private String activeLabel() {
        return baseItem != null ? displayName(baseItem, String.valueOf(activeItem)) : String.valueOf(activeItem);
    }

    /** Difunde únicamente a los jugadores del mundo configurado. */
    private void broadcast(Component c) {
        if (test) { // preview: solo lo ve quien lo ejecutó
            if (testSender != null) testSender.sendMessage(plugin.messages().component("test.tag").append(c));
            return;
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            plugin.getLogger().warning("El mundo '" + worldName + "' no existe; no se difunde el mensaje.");
            return;
        }
        for (Player p : w.getPlayers()) p.sendMessage(c);
    }
}
