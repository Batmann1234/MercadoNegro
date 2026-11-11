package com.mercadonegro;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Commands implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("start", "cancel", "info", "reload", "preview", "mini");
    private final MercadoNegroPlugin plugin;
    private final AuctionManager manager;

    public Commands(MercadoNegroPlugin plugin, AuctionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Messages m = plugin.messages();

        if (cmd.getName().equalsIgnoreCase("subasta")) {
            if (sender instanceof Player p) manager.openMenu(p);
            else sender.sendMessage(m.prefixed("error.players-only"));
            return true;
        }

        if (!sender.hasPermission("mercadonegro.admin")) {
            sender.sendMessage(m.prefixed("error.no-permission"));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("mini")) {
            if (args.length < 3 || args.length > 4) {
                sender.sendMessage(m.prefixed("mini.usage"));
                return true;
            }
            long price = MoneyUtil.parse(args[2], manager.maxBid());
            if (price < 0) {
                sender.sendMessage(m.prefixed("admin.invalid-price"));
                return true;
            }
            int secs = manager.miniSeconds();
            if (args.length == 4) {
                int mins;
                try { mins = Integer.parseInt(args[3]); } catch (NumberFormatException e) { mins = -1; }
                if (mins < 1 || mins > 30) {
                    sender.sendMessage(m.prefixed("mini.invalid-minutes"));
                    return true;
                }
                secs = mins * 60;
            }
            String err = manager.startMini(args[1], price, secs);
            sender.sendMessage(err == null
                    ? m.prefixed("mini.started", "item", args[1], "base", MoneyUtil.format(price),
                            "duration", MoneyUtil.time(secs * 1000L))
                    : m.prefixed(err, "item", args[1]));
            return true;
        }

        if (args.length >= 1 && args.length <= 2 && args[0].equalsIgnoreCase("preview")) {
            int secs = manager.previewSeconds();
            if (args.length == 2) {
                try { secs = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) { secs = -1; }
            }
            if (secs < 15 || secs > 600) {
                sender.sendMessage(m.prefixed("test.invalid-seconds"));
                return true;
            }
            manager.diagnostics(sender);
            String err = manager.preview(sender, secs);
            sender.sendMessage(err == null
                    ? m.prefixed("test.started", "seconds", String.valueOf(secs))
                    : m.prefixed(err, "item", String.valueOf(manager.preparedItem())));
            return true;
        }

        if (args.length == 1) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "start" -> {
                    String err = manager.start();
                    sender.sendMessage(err == null ? m.prefixed("admin.started")
                            : m.prefixed(err, "item", String.valueOf(manager.preparedItem())));
                }
                case "cancel" -> sender.sendMessage(manager.cancel()
                        ? m.prefixed("admin.cancelled") : m.prefixed("error.no-active"));
                case "info" -> manager.info(sender);
                case "reload" -> {
                    plugin.reloadConfig();
                    m.load();
                    manager.reloadSettings();
                    sender.sendMessage(m.prefixed("admin.reloaded"));
                }
                default -> sender.sendMessage(m.prefixed("admin.usage"));
            }
            return true;
        }

        if (args.length == 2) {
            long price = MoneyUtil.parse(args[1], manager.maxBid());
            if (price < 0) {
                sender.sendMessage(m.prefixed("admin.invalid-price"));
                return true;
            }
            if (!manager.itemExists(args[0])) {
                sender.sendMessage(m.prefixed("admin.item-not-found", "item", args[0]));
                return true;
            }
            manager.prepare(args[0], price);
            sender.sendMessage(m.prefixed("admin.prepared", "item", args[0], "base", MoneyUtil.format(price)));
            return true;
        }

        sender.sendMessage(m.prefixed("admin.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("mercadonegro") || !sender.hasPermission("mercadonegro.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> all = new ArrayList<>(SUBS);
            all.addAll(manager.itemNames());
            String pre = args[0].toLowerCase(Locale.ROOT);
            return all.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(pre)).limit(50).toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("mini")) {
            if (args.length == 2) {
                String pre = args[1].toLowerCase(Locale.ROOT);
                return manager.itemNames().stream()
                        .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(pre)).limit(50).toList();
            }
            if (args.length == 3) return List.of("10m", "100m", "300m");
            if (args.length == 4) return List.of("3", "5", "10");
            return List.of();
        }
        if (args.length == 2) {
            return args[0].equalsIgnoreCase("preview") ? List.of("30", "60", "120") : List.of("10m", "300m", "1b");
        }
        return List.of();
    }
}
