package eu.nurkert.photomaps.item;

import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Removes the label above pictures hung before 1.0.2 (see {@link PhotoItems#dropFrameLabel}): in item frames when
 * their area loads, and in inventories when players join.
 */
public final class LabelMigration implements Listener {

    private final PhotoItems items;

    public LabelMigration(PhotoItems items) {
        this.items = items;
    }

    @EventHandler
    public void on(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemFrame frame) {
                ItemStack item = frame.getItem();
                if (items.dropFrameLabel(item)) {
                    frame.setItem(item, false);
                }
            }
        }
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        PlayerInventory inventory = event.getPlayer().getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (items.dropFrameLabel(item)) {
                inventory.setItem(slot, item);
            }
        }
    }
}
