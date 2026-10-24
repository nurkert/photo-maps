package eu.nurkert.photomaps.resourcepack;

import eu.nurkert.photomaps.PhotoMaps;
import eu.nurkert.photomaps.Settings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Sends the Java resource pack with the photo code texture to Java players, in addition to any server or other plugin
 * pack. The pack is inside the plugin jar and hosted by the plugin's own web server, so it needs no setup.
 */
public final class ResourcePacks implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("photomaps:resourcepack".getBytes(StandardCharsets.UTF_8));

    private final PhotoMaps plugin;
    private final byte[] pack;
    private final byte[] sha1;

    public ResourcePacks(PhotoMaps plugin) throws IOException {
        this.plugin = plugin;
        try (InputStream in = plugin.getResource("pack/photomaps.zip")) {
            if (in == null) {
                throw new IOException("missing pack/photomaps.zip in the plugin jar");
            }
            this.pack = in.readAllBytes();
        }
        try {
            this.sha1 = MessageDigest.getInstance("SHA-1").digest(pack);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public byte[] pack() {
        return pack;
    }

    public String sha1() {
        return HexFormat.of().formatHex(sha1);
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Settings settings = plugin.getSettings();
        // Bedrock players (Floodgate gives them ids with zero upper bits) get the Bedrock pack from Geyser
        if (!settings.resourcePack() || player.getUniqueId().getMostSignificantBits() == 0) {
            return;
        }
        String url;
        byte[] hash;
        if (!settings.resourcePackUrl().isEmpty()) {
            url = settings.resourcePackUrl();
            hash = null; // a pack hosted elsewhere may change without the plugin knowing
        } else if (plugin.isWebRunning() && plugin.getPackBaseUrl() != null) {
            url = plugin.getPackBaseUrl() + "/pack/" + sha1() + ".zip";
            hash = sha1;
        } else {
            return;
        }
        String prompt = settings.resourcePackPrompt().isBlank() ? null : settings.resourcePackPrompt();
        player.addResourcePack(PACK_ID, url, hash, prompt, settings.resourcePackRequired());
    }
}
