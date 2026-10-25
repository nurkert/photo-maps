package eu.nurkert.photomaps.item;

import eu.nurkert.photomaps.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Creates and recognises the items of PhotoMaps. Items are recognised by markers in their persistent data, never by
 * name or lore.
 *
 * <p>Names are item names, not custom names: they look the same in the inventory, but item frames show only custom
 * names above the item, so a picture on the wall carries no label.
 *
 * <ul>
 *   <li>Photo code: paper with the item model {@code photomaps:qr_code} from the resource pack (Java) or the Geyser
 *       mapping (Bedrock). Right-clicking it turns it into a photo code map.
 *   <li>Photo code map: a filled map that shows the QR code of its upload page while held.
 *   <li>Picture part: a filled map with one part of an uploaded picture; it knows its position in the picture.
 * </ul>
 */
public final class PhotoItems {

    public static final Material CODE_BASE = Material.PAPER;

    /** A part of a picture: its id (the first map's id), its position and the picture's size in maps. */
    public record Part(int picture, int column, int row, int columns, int rows) {

        String encode() {
            return picture + ":" + column + ":" + row + ":" + columns + ":" + rows;
        }

        static Part decode(String value) {
            if (value == null) {
                return null;
            }
            String[] fields = value.split(":");
            try {
                return new Part(
                        Integer.parseInt(fields[0]),
                        Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]),
                        Integer.parseInt(fields[4]));
            } catch (RuntimeException exception) {
                return null;
            }
        }
    }

    private final NamespacedKey model;
    private final NamespacedKey codeKey;
    private final NamespacedKey codeMapKey;
    private final NamespacedKey partKey;

    public PhotoItems(JavaPlugin plugin) {
        this.model = new NamespacedKey(plugin, "qr_code");
        this.codeKey = new NamespacedKey(plugin, "photo_code");
        this.codeMapKey = new NamespacedKey(plugin, "photo_code_map");
        this.partKey = new NamespacedKey(plugin, "picture_part");
    }

    /**
     * @param viewer whose language the name and lore are in
     */
    public ItemStack createCode(CommandSender viewer, int amount) {
        ItemStack item = new ItemStack(CODE_BASE, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(codeKey, PersistentDataType.BYTE, (byte) 1);
        meta.setItemModel(model);
        meta.setItemName(Messages.get(viewer, "item.name"));
        meta.setLore(Messages.list(viewer, "item.lore"));
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createCodeMap(CommandSender viewer, MapView view) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.getPersistentDataContainer().set(codeMapKey, PersistentDataType.BYTE, (byte) 1);
        meta.setItemName(Messages.get(viewer, "item.map-name"));
        meta.setLore(Messages.list(viewer, "item.map-lore"));
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createPart(CommandSender viewer, MapView view, Part part) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, part.encode());
        int number = part.row() * part.columns() + part.column() + 1;
        int count = part.columns() * part.rows();
        meta.setItemName(Messages.get(
                viewer, "item.picture-name", "id", part.picture(), "part", number, "parts", count));
        if (count > 1) {
            meta.setLore(Messages.list(
                    viewer, "item.picture-lore", "column", part.column() + 1, "row", part.row() + 1));
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Picture parts made before 1.0.2 had a custom name, which item frames show above the picture. Turns the name the
     * plugin gave into an item name; a name a player gave on an anvil stays.
     *
     * @return whether the item changed
     */
    public boolean dropFrameLabel(ItemStack item) {
        Part part = part(item);
        if (part == null || !item.getItemMeta().hasDisplayName()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        String name = meta.getDisplayName();
        int number = part.row() * part.columns() + part.column() + 1;
        boolean ours = Messages.inAllLanguages(
                        "item.picture-name",
                        "id",
                        part.picture(),
                        "part",
                        number,
                        "parts",
                        part.columns() * part.rows())
                .contains(name);
        if (!ours) {
            return false;
        }
        meta.setDisplayName(null);
        meta.setItemName(name);
        item.setItemMeta(meta);
        return true;
    }

    public boolean isCode(ItemStack item) {
        return item != null && item.getType() == CODE_BASE && has(item, codeKey);
    }

    public boolean isCodeMap(ItemStack item) {
        return item != null && item.getType() == Material.FILLED_MAP && has(item, codeMapKey);
    }

    /**
     * @return the map view of a photo code map or picture part, {@code null} if it has none
     */
    public static MapView view(ItemStack item) {
        return item != null && item.getItemMeta() instanceof MapMeta meta && meta.hasMapView()
                ? meta.getMapView()
                : null;
    }

    /**
     * @return the position of a picture part in its picture, {@code null} for any other item
     */
    public Part part(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) {
            return null;
        }
        return Part.decode(item.getItemMeta().getPersistentDataContainer().get(partKey, PersistentDataType.STRING));
    }

    private static boolean has(ItemStack item, NamespacedKey key) {
        if (!item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return data.has(key, PersistentDataType.BYTE);
    }
}
