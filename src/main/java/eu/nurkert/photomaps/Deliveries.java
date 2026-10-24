package eu.nurkert.photomaps;

import eu.nurkert.photomaps.item.PhotoItems;
import eu.nurkert.photomaps.map.PictureStore;
import eu.nurkert.photomaps.web.Sessions;
import eu.nurkert.photomaps.web.WebServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

/**
 * Turns uploaded pictures into maps in the player's inventory. Called by the web server; the game state is only
 * touched on the main thread.
 */
final class Deliveries implements WebServer.Backend {

    private static final ItemStack EMPTY_MAP = new ItemStack(Material.MAP);

    private final PhotoMaps plugin;

    Deliveries(PhotoMaps plugin) {
        this.plugin = plugin;
    }

    @Override
    public Integer emptyMaps(Sessions.Session session) throws Exception {
        return plugin.getServer()
                .getScheduler()
                .callSyncMethod(plugin, () -> {
                    Player player = Bukkit.getPlayer(session.player());
                    if (player == null) {
                        return null;
                    }
                    return pays(player) ? countEmptyMaps(player) : -1;
                })
                .get(10, TimeUnit.SECONDS);
    }

    @Override
    public WebServer.Result deliver(Sessions.Session session, int columns, int rows, List<Integer> parts, byte[] pixels)
            throws Exception {
        // cut the parts out here, off the main thread
        List<byte[]> tiles = new ArrayList<>();
        for (int part : parts) {
            tiles.add(tile(pixels, columns, part % columns, part / columns));
        }
        return plugin.getServer()
                .getScheduler()
                .callSyncMethod(plugin, () -> give(session, columns, rows, parts, tiles))
                .get(30, TimeUnit.SECONDS);
    }

    private WebServer.Result give(
            Sessions.Session session, int columns, int rows, List<Integer> parts, List<byte[]> tiles) {
        Player player = Bukkit.getPlayer(session.player());
        if (player == null) {
            return WebServer.Result.failure("offline", 0, 0);
        }
        if (pays(player)) {
            int available = countEmptyMaps(player);
            if (available < parts.size()) {
                return WebServer.Result.failure("maps", parts.size(), available);
            }
            player.getInventory().removeItem(new ItemStack(Material.MAP, parts.size()));
        }

        PictureStore store = plugin.getPictureStore();
        PhotoItems items = plugin.getItems();
        List<ItemStack> maps = new ArrayList<>();
        int picture = -1;
        for (int i = 0; i < parts.size(); i++) {
            MapView view = Bukkit.createMap(player.getWorld());
            if (picture < 0) {
                picture = view.getId();
            }
            try {
                store.addPicture(view, tiles.get(i));
            } catch (IOException exception) {
                // hand out nothing that could be blank after a restart, and give the empty maps back
                plugin.getLogger().log(Level.SEVERE, "Could not store a picture of " + player.getName(), exception);
                if (pays(player)) {
                    for (ItemStack leftover : player.getInventory()
                            .addItem(new ItemStack(Material.MAP, parts.size()))
                            .values()) {
                        player.getWorld().dropItem(player.getLocation(), leftover);
                    }
                }
                return WebServer.Result.failure("server", 0, 0);
            }
            int part = parts.get(i);
            maps.add(items.createPart(
                    player, view, new PhotoItems.Part(picture, part % columns, part / columns, columns, rows)));
        }

        boolean dropped = false;
        for (ItemStack leftover :
                player.getInventory().addItem(maps.toArray(ItemStack[]::new)).values()) {
            player.getWorld().dropItem(player.getLocation(), leftover);
            dropped = true;
        }
        Messages.send(player, "upload.received", "count", maps.size());
        if (dropped) {
            Messages.send(player, "upload.dropped");
        }
        player.playSound(player.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1, 1);
        return WebServer.Result.ok(maps.size());
    }

    private boolean pays(Player player) {
        return plugin.getSettings().consumeEmptyMaps()
                && player.getGameMode() != GameMode.CREATIVE
                && !player.hasPermission("photomaps.unlimited");
    }

    private static int countEmptyMaps(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (EMPTY_MAP.isSimilar(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** Cuts one 128x128 part out of the whole picture. */
    static byte[] tile(byte[] pixels, int columns, int column, int row) {
        int size = PictureStore.SIZE;
        int width = columns * size;
        byte[] tile = new byte[size * size];
        for (int y = 0; y < size; y++) {
            System.arraycopy(pixels, (row * size + y) * width + column * size, tile, y * size, size);
        }
        return tile;
    }
}
