package eu.nurkert.photomaps.item;

import eu.nurkert.photomaps.Messages;
import eu.nurkert.photomaps.PhotoMaps;
import org.bukkit.GameMode;
import org.bukkit.Rotation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;

/**
 * Hangs a whole picture at once: when a player puts one part of a picture into an empty item frame on a wall, the
 * other parts in their inventory go into the empty frames around it, each at its place in the picture.
 *
 * <p>Every additional frame is checked with its own {@link PlayerInteractEntityEvent}, so protection plugins decide
 * about each frame as if the player had clicked it.
 */
public final class FrameListener implements Listener {

    private final PhotoMaps plugin;
    // set while the events for the other frames are fired, so they do not start another placement
    private boolean placing;

    public FrameListener(PhotoMaps plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(PlayerInteractEntityEvent event) {
        if (placing
                || !plugin.getSettings().autoPlace()
                || !(event.getRightClicked() instanceof ItemFrame frame)
                || !isWall(frame.getFacing())
                || !isEmpty(frame.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        PhotoItems.Part clicked =
                plugin.getItems().part(player.getInventory().getItem(event.getHand()));
        if (clicked == null || clicked.columns() * clicked.rows() < 2) {
            return;
        }
        // the clicked frame is filled by the game; the others once it has done so
        EquipmentSlot hand = event.getHand();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && clicked.equals(plugin.getItems().part(frame.getItem()))) {
                placeOthers(player, frame, clicked, hand);
            }
        });
    }

    private void placeOthers(Player player, ItemFrame origin, PhotoItems.Part clicked, EquipmentSlot hand) {
        BlockFace facing = origin.getFacing();
        BlockFace right = rightOf(facing);
        Block originBlock = origin.getLocation().getBlock();
        PlayerInventory inventory = player.getInventory();
        boolean consume = player.getGameMode() != GameMode.CREATIVE;
        int placed = 0;

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            PhotoItems.Part part = plugin.getItems().part(item);
            if (part == null || part.picture() != clicked.picture() || part.equals(clicked)) {
                continue;
            }
            int dx = part.column() - clicked.column();
            int dy = clicked.row() - part.row();
            Block target = originBlock.getRelative(right.getModX() * dx, dy, right.getModZ() * dx);
            ItemFrame frame = emptyFrameAt(target, facing);
            if (frame == null || !allowed(player, frame, hand)) {
                continue;
            }
            ItemStack single = item.clone();
            single.setAmount(1);
            frame.setItem(single, false);
            frame.setRotation(Rotation.NONE);
            if (consume) {
                item.setAmount(item.getAmount() - 1);
                inventory.setItem(slot, item.getAmount() > 0 ? item : null);
            }
            placed++;
        }
        if (placed > 0) {
            Messages.send(player, "frames.placed", "count", placed);
        }
    }

    private boolean allowed(Player player, ItemFrame frame, EquipmentSlot hand) {
        placing = true;
        try {
            PlayerInteractEntityEvent check = new PlayerInteractEntityEvent(player, frame, hand);
            plugin.getServer().getPluginManager().callEvent(check);
            return !check.isCancelled();
        } finally {
            placing = false;
        }
    }

    private static ItemFrame emptyFrameAt(Block block, BlockFace facing) {
        for (Entity entity : block.getWorld()
                .getNearbyEntities(
                        BoundingBox.of(block), e -> e instanceof ItemFrame f && f.getFacing() == facing)) {
            ItemFrame frame = (ItemFrame) entity;
            if (frame.getLocation().getBlock().equals(block) && isEmpty(frame.getItem())) {
                return frame;
            }
        }
        return null;
    }

    private static boolean isWall(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.EAST || face == BlockFace.WEST;
    }

    /**
     * @return the direction that is "right" for someone looking at a frame on a wall that faces {@code facing}
     */
    static BlockFace rightOf(BlockFace facing) {
        return switch (facing) {
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            default -> throw new IllegalArgumentException("not a wall: " + facing);
        };
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
