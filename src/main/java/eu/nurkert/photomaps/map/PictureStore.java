package eu.nurkert.photomaps.map;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps the pixels of every picture map and the photo code maps with their owners, and gives those maps their
 * renderers whenever the server loads them.
 *
 * <p>Plugin renderers are not saved with the world, so every map would come back empty after a restart. The pixels
 * of each map are therefore stored in {@code pictures/<map id>.bin} (16 KiB, gzipped) and the renderer is attached
 * again when the map is loaded.
 */
public final class PictureStore implements Listener {

    public static final int SIZE = 128;

    private final JavaPlugin plugin;
    private final File folder;
    private final File codesFile;
    private final Map<Integer, byte[]> pixels = new ConcurrentHashMap<>();
    // photo code map id -> owner; a code map belongs to whoever activated it, copies of it included
    private final Map<Integer, UUID> codeMaps = new ConcurrentHashMap<>();
    // photo code maps whose one picture was uploaded
    private final Set<Integer> usedCodes = ConcurrentHashMap.newKeySet();
    private final QrRenderer.Factory qrRenderers;

    public PictureStore(JavaPlugin plugin, QrRenderer.Factory qrRenderers) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "pictures");
        this.codesFile = new File(plugin.getDataFolder(), "codes.yml");
        this.qrRenderers = qrRenderers;
        YamlConfiguration codes = YamlConfiguration.loadConfiguration(codesFile);
        ConfigurationSection owners = codes.getConfigurationSection("owners");
        if (owners != null) {
            for (String id : owners.getKeys(false)) {
                try {
                    codeMaps.put(Integer.parseInt(id), UUID.fromString(owners.getString(id)));
                } catch (IllegalArgumentException | NullPointerException ignored) {
                    // a broken line, that map shows no code until it is activated again
                }
            }
        }
        usedCodes.addAll(codes.getIntegerList("used"));
    }

    /**
     * Gives the maps that are already loaded (after /reload) their renderers. Maps loaded later get them through
     * {@link MapInitializeEvent}.
     */
    @SuppressWarnings("deprecation") // Bukkit.getMap(int) is the only lookup by id
    public void attachLoaded() {
        Set<Integer> ids = new HashSet<>(codeMaps.keySet());
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".bin"));
        if (files != null) {
            for (File file : files) {
                try {
                    ids.add(Integer.parseInt(file.getName().substring(0, file.getName().length() - 4)));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        }
        for (int id : ids) {
            MapView view = Bukkit.getMap(id);
            if (view != null) {
                attach(view);
            }
        }
    }

    @EventHandler
    public void on(MapInitializeEvent event) {
        attach(event.getMap());
    }

    private void attach(MapView view) {
        if (view.getRenderers().stream().anyMatch(r -> r instanceof PictureRenderer || r instanceof QrRenderer)) {
            return;
        }
        MapRenderer renderer = null;
        if (codeMaps.containsKey(view.getId())) {
            renderer = qrRenderers.create(view.getId());
        } else {
            byte[] data = load(view.getId());
            if (data != null) {
                renderer = new PictureRenderer(data);
            }
        }
        if (renderer != null) {
            prepare(view);
            view.addRenderer(renderer);
        }
    }

    /** Removes the vanilla renderer and stops the map from following the world. */
    public static void prepare(MapView view) {
        for (MapRenderer renderer : view.getRenderers()) {
            view.removeRenderer(renderer);
        }
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
    }

    /**
     * Turns a fresh map into a picture map. Call on the main thread, before the map is handed out.
     *
     * <p>The file is written right away (16 KiB, well under a millisecond) instead of in the background: a background
     * task would be dropped if the server stopped in the same moment, and the picture would be lost.
     *
     * @throws IOException if the picture could not be stored; then the map must not be handed out
     */
    public void addPicture(MapView view, byte[] data) throws IOException {
        save(view.getId(), data);
        pixels.put(view.getId(), data);
        prepare(view);
        view.addRenderer(new PictureRenderer(data));
    }

    /**
     * Turns a fresh map into a photo code map of {@code owner}, or gives an existing one a new owner. Call on the main
     * thread.
     */
    public void addCode(MapView view, UUID owner) {
        boolean known = codeMaps.containsKey(view.getId());
        codeMaps.put(view.getId(), owner);
        if (!known || view.getRenderers().stream().noneMatch(r -> r instanceof QrRenderer)) {
            prepare(view);
            view.addRenderer(qrRenderers.create(view.getId()));
        }
        saveCodes();
    }

    /** Marks a photo code map as used up: it shows no QR code any more and opens no upload page. */
    public void markUsed(int mapId) {
        usedCodes.add(mapId);
        saveCodes();
    }

    public boolean isUsed(int mapId) {
        return usedCodes.contains(mapId);
    }

    private void saveCodes() {
        YamlConfiguration codes = new YamlConfiguration();
        codeMaps.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> codes.set("owners." + entry.getKey(), entry.getValue().toString()));
        codes.set("used", usedCodes.stream().sorted().toList());
        try {
            codes.save(codesFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + codesFile.getName(), exception);
        }
    }

    public boolean isCode(int mapId) {
        return codeMaps.containsKey(mapId);
    }

    /**
     * @return who the photo code map belongs to, {@code null} if it is no code map
     */
    public UUID codeOwner(int mapId) {
        return codeMaps.get(mapId);
    }

    private byte[] load(int id) {
        byte[] cached = pixels.get(id);
        if (cached != null) {
            return cached;
        }
        File file = new File(folder, id + ".bin");
        if (!file.isFile()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            byte[] data = new byte[SIZE * SIZE];
            in.readFully(data);
            pixels.put(id, data);
            return data;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not read picture map " + id, exception);
            return null;
        }
    }

    /** Writes the pixels to a temporary file and moves it into place, so a crash never leaves half a file. */
    private void save(int id, byte[] data) throws IOException {
        Files.createDirectories(folder.toPath());
        File temp = new File(folder, id + ".bin.tmp");
        try (FileOutputStream file = new FileOutputStream(temp);
                GZIPOutputStream out = new GZIPOutputStream(file)) {
            out.write(data);
            out.finish();
            file.getFD().sync(); // on disk before the map is handed out
        }
        Files.move(
                temp.toPath(),
                new File(folder, id + ".bin").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
