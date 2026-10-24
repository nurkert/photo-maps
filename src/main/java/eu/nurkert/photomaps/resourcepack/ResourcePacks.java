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
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Sends the Java resource pack with the photo code texture to Java players, in addition to any server or other plugin
 * pack (it only adds files in the {@code photomaps} namespace).
 *
 * <p>Release builds send the pack from the GitHub release of their version, like PorticleGun: a public HTTPS address
 * that every client can reach, whatever the server's network, ports or certificate. Minecraft applies the packs of a
 * server together, so a pack that cannot be downloaded would also hold back the packs of other plugins. The SHA-1 is
 * taken from the copy in the jar, which the build made from the same file. Development builds have no release and serve
 * the pack from the plugin's web server.
 */
public final class ResourcePacks implements Listener {

    static final String RELEASE_URL =
            "https://github.com/nurkert/photo-maps/releases/download/v%1$s/PhotoMaps-resourcepack-%1$s.zip";
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("photomaps:resourcepack".getBytes(StandardCharsets.UTF_8));

    private final PhotoMaps plugin;
    private final byte[] pack;
    private final byte[] sha1;
    private final String version;

    public ResourcePacks(PhotoMaps plugin) throws IOException {
        this.plugin = plugin;
        this.version = plugin.getDescription().getVersion();
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

    /** Whether the pack comes from the plugin's own web server (development builds) rather than a fixed address. */
    public boolean isSelfHosted() {
        return plugin.getSettings().resourcePackUrl().isEmpty() && releaseUrl(version) == null;
    }

    /**
     * @return the address of the pack in the GitHub release of this version, {@code null} for development builds
     */
    static String releaseUrl(String version) {
        if (version == null || version.isBlank() || version.contains("SNAPSHOT")) {
            return null;
        }
        return String.format(RELEASE_URL, version);
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
        } else if (releaseUrl(version) != null) {
            url = releaseUrl(version);
            hash = sha1;
        } else if (plugin.isWebRunning() && plugin.getPackBaseUrl() != null) {
            url = plugin.getPackBaseUrl() + "/pack/" + sha1() + ".zip";
            hash = sha1;
        } else {
            return;
        }
        String prompt = settings.resourcePackPrompt().isBlank() ? null : settings.resourcePackPrompt();
        player.addResourcePack(PACK_ID, url, hash, prompt, settings.resourcePackRequired());
    }

    /** Tells the admin when a client could not use the pack, with the reason, instead of failing silently. */
    @EventHandler
    public void on(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> plugin.getLogger()
                    .warning(event.getPlayer().getName() + " could not load the PhotoMaps resource pack ("
                            + event.getStatus() + "). Set resource-pack.url to an address their game can download "
                            + "from, or resource-pack.enabled: false to send no pack.");
            default -> {}
        }
    }
}
