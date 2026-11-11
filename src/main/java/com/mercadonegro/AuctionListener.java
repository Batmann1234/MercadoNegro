package com.mercadonegro;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class AuctionListener implements Listener {

    private final MercadoNegroPlugin plugin;
    private final AuctionManager manager;

    public AuctionListener(MercadoNegroPlugin plugin, AuctionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof AuctionHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == e.getView().getTopInventory()
                && e.getRawSlot() == AuctionManager.CENTER_SLOT) {
            Bukkit.getScheduler().runTask(plugin, () -> manager.startBidMode(p));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof AuctionHolder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!manager.inBidMode(p)) return;
        e.setCancelled(true);
        if (!manager.consumeBidMode(p)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> manager.processChatBid(p, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.clearBidMode(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) manager.deliverPending(p);
        }, 40L);
    }
}
