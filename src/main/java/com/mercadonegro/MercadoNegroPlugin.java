package com.mercadonegro;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class MercadoNegroPlugin extends JavaPlugin {

    private Economy economy;
    private Messages messages;
    private AuctionManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();
        manager = new AuctionManager(this);
        manager.load();

        getServer().getPluginManager().registerEvents(new AuctionListener(this, manager), this);
        Commands commands = new Commands(this, manager);
        for (String name : new String[]{"mercadonegro", "subasta"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(commands);
                pc.setTabCompleter(commands);
            }
        }

        // Se difiere 1 tick para que EssentialsX ya haya registrado su economía.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!setupEconomy()) {
                getLogger().severe("No se encontró un proveedor de economía de Vault. Desactivando.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            manager.init();
        });
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public Economy economy() { return economy; }
    public Messages messages() { return messages; }
}
