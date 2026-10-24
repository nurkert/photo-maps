package eu.nurkert.photomaps.item;

import eu.nurkert.photomaps.Messages;
import eu.nurkert.photomaps.PhotoMaps;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

/**
 * Right-clicking a photo code turns it into a photo code map and opens an upload page; right-clicking the map again
 * renews the page. Right-clicking a block works as well, which Bedrock touch players need, because they cannot
 * right-click the air with paper.
 */
public final class CodeListener implements Listener {

    private final PhotoMaps plugin;

    public CodeListener(PhotoMaps plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void on(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        PhotoItems items = plugin.getItems();
        ItemStack item = event.getItem();
        boolean code = items.isCode(item);
        if (!code && !items.isCodeMap(item)) {
            return;
        }
        Player player = event.getPlayer();
        // chests, doors and buttons keep working with a code in hand
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock().getType().isInteractable()
                && !player.isSneaking()) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("photomaps.use")) {
            Messages.send(player, "code.no-permission");
            return;
        }
        if (!plugin.isWebRunning()) {
            Messages.send(player, "code.no-web");
            return;
        }

        MapView view;
        if (code) {
            view = Bukkit.createMap(player.getWorld());
            plugin.getPictureStore().addCode(view);
            ItemStack map = items.createCodeMap(player, view);
            EquipmentSlot hand = event.getHand();
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(hand, item);
                for (ItemStack leftover : player.getInventory().addItem(map).values()) {
                    player.getWorld().dropItem(player.getLocation(), leftover);
                }
            } else {
                player.getInventory().setItem(hand, map);
            }
            player.playSound(player.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1, 1.4f);
        } else {
            view = PhotoItems.view(item);
            if (view == null) {
                return;
            }
            if (!plugin.getPictureStore().isCode(view.getId())) {
                // a code map whose renderer data was lost: register it again
                plugin.getPictureStore().addCode(view);
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.6f);
        }
        plugin.openSession(player, view.getId());
    }
}
