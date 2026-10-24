package eu.nurkert.photomaps;

import eu.nurkert.photomaps.command.PhotoMapsCommand;
import eu.nurkert.photomaps.item.CodeListener;
import eu.nurkert.photomaps.item.FrameListener;
import eu.nurkert.photomaps.item.PhotoItems;
import eu.nurkert.photomaps.item.RecipeListener;
import eu.nurkert.photomaps.map.Palette;
import eu.nurkert.photomaps.map.PictureStore;
import eu.nurkert.photomaps.map.QrRenderer;
import eu.nurkert.photomaps.resourcepack.BedrockPackInstaller;
import eu.nurkert.photomaps.resourcepack.ResourcePacks;
import eu.nurkert.photomaps.web.Sessions;
import eu.nurkert.photomaps.web.Tls;
import eu.nurkert.photomaps.web.WebServer;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.SocketException;
import java.util.Collections;
import java.util.logging.Level;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The plugin entry point. Craft a photo code, right-click it, scan the QR code with a phone, upload a picture on the
 * page it opens and get it back as maps.
 */
public final class PhotoMaps extends JavaPlugin {

    private Settings settings;
    private Palette palette;
    private PhotoItems items;
    private PictureStore pictures;
    private Sessions sessions;
    private WebServer web;
    private RecipeListener recipe;
    private String publicUrl;
    private Tls tls;

    @Override
    public void onLoad() {
        // runs before Geyser loads (loadbefore), so Geyser picks up the pack on this start
        if (getConfig().getBoolean("bedrock.install-pack", true)) {
            BedrockPackInstaller.install(this);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        Messages.load(this);
        palette = Palette.fromServer();
        items = new PhotoItems(this);
        sessions = new Sessions();
        pictures = new PictureStore(this, mapId -> new QrRenderer(mapId, this::urlForMap, palette));
        recipe = new RecipeListener(this, items);

        var plugins = getServer().getPluginManager();
        plugins.registerEvents(pictures, this);
        plugins.registerEvents(recipe, this);
        plugins.registerEvents(new CodeListener(this), this);
        plugins.registerEvents(new FrameListener(this), this);
        PhotoMapsCommand command = new PhotoMapsCommand(this);
        getCommand("photomaps").setExecutor(command);
        getCommand("photomaps").setTabCompleter(command);

        web = new WebServer(getLogger(), sessions, palette, new Deliveries(this), settings);
        try {
            ResourcePacks packs = new ResourcePacks(this);
            web.setPack(packs.pack(), packs.sha1());
            plugins.registerEvents(packs, this);
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "The Java resource pack is missing, photo codes will look like paper", exception);
        }
        startWeb();
        if (settings.recipe()) {
            recipe.register();
        }
        pictures.attachLoaded();
        BedrockPackInstaller.checkGeyserSetup(this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, sessions::purge, 1200, 1200);
        // renewed certificates (Let's Encrypt) are picked up within an hour
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (tls != null && tls.filesChanged()) {
                getLogger().info("The certificate changed, restarting the web server.");
                web.stop();
                startWeb();
            }
        }, 72_000, 72_000);
    }

    @Override
    public void onDisable() {
        if (web != null) {
            web.stop();
        }
        if (recipe != null) {
            recipe.unregister();
        }
        if (sessions != null) {
            sessions.clear();
        }
    }

    public void reload() {
        reloadConfig();
        settings = Settings.from(getConfig());
        Messages.load(this);
        web.setSettings(settings);
        // the certificate files may have changed as well, so the web server always restarts
        web.stop();
        startWeb();
        if (settings.recipe()) {
            recipe.register();
        } else {
            recipe.unregister();
        }
    }

    private void startWeb() {
        String host = detectHost();
        tls = null;
        if (settings.https()) {
            try {
                tls = loadTls(host);
            } catch (IOException | GeneralSecurityException exception) {
                getLogger()
                        .log(
                                Level.SEVERE,
                                "Could not load the certificate, the web server stays off so that no picture is sent "
                                        + "unencrypted. Check web.https in config.yml and run /photomaps reload.",
                                exception);
                return;
            }
        }
        publicUrl = settings.publicUrl().isEmpty()
                ? (tls != null ? "https://" : "http://") + host + ":" + settings.port()
                : settings.publicUrl();
        try {
            web.start(tls);
            getLogger().info("Web interface running at " + publicUrl);
            if (tls == null) {
                if (!publicUrl.startsWith("https://")) {
                    getLogger().warning("HTTPS is turned off: pictures travel UNENCRYPTED through the network. Only "
                            + "do this behind a reverse proxy that does HTTPS, and set web.public-url to its https:// "
                            + "address.");
                }
            } else if (tls.isSelfSigned()) {
                getLogger().info("Using a self-signed certificate (SHA-256 " + tls.fingerprint() + "). Phones show a "
                        + "warning once; set web.https.certificate for a certificate of your domain to avoid it.");
                if (settings.publicUrl().isEmpty() && settings.packPort() > 0) {
                    getLogger().info("The resource pack is served on port " + settings.packPort() + " (plain HTTP, "
                            + "Minecraft refuses packs from self-signed servers; the pack is checked by its hash).");
                }
            }
            if (settings.publicUrl().isEmpty()) {
                getLogger().info("Players outside your network need web.public-url in config.yml.");
            }
        } catch (IOException exception) {
            getLogger()
                    .log(
                            Level.SEVERE,
                            "Could not start the web server on port " + settings.port()
                                    + ". Is the port in use? Change web.port in config.yml and run /photomaps reload.",
                            exception);
        }
    }

    /**
     * Opens an upload page for the player and sends them its address. With a photo code map, the map shows the page's
     * QR code from now on.
     *
     * @param mapId the photo code map, -1 for none
     */
    public void openSession(Player player, int mapId) {
        if (!isWebRunning()) {
            Messages.send(player, "code.no-web");
            return;
        }
        Sessions.Session session =
                sessions.open(player.getUniqueId(), player.getName(), mapId, settings.sessionMinutes() * 60_000L);
        String url = pageUrl(session);
        Messages.send(player, "code.ready");
        TextComponent link = new TextComponent(
                TextComponent.fromLegacyText(Messages.get(player, "code.open-link", "url", url)));
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        link.setHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(Messages.get(player, "code.link-hover"))));
        player.spigot().sendMessage(link);
        player.sendMessage(Messages.get(player, "code.valid", "minutes", settings.sessionMinutes()));
        if (isSelfSigned()) {
            player.sendMessage(Messages.get(player, "code.self-signed"));
        }
    }

    private String urlForMap(int mapId) {
        Sessions.Session session = sessions.forMap(mapId);
        return session != null ? pageUrl(session) : null;
    }

    private String pageUrl(Sessions.Session session) {
        return publicUrl + "/s/" + session.token();
    }

    private Tls loadTls(String host) throws IOException, GeneralSecurityException {
        if (!settings.certificate().isEmpty() || !settings.privateKey().isEmpty()) {
            return Tls.fromPem(new File(settings.certificate()), new File(settings.privateKey()));
        }
        if (!settings.keyStore().isEmpty()) {
            return Tls.fromKeyStore(new File(settings.keyStore()), settings.keyStorePassword());
        }
        return Tls.selfSigned(new File(getDataFolder(), "tls/self-signed.p12"), host);
    }

    /**
     * The host of the configured public URL, else this machine's address in the local network, which phones in the
     * same Wi-Fi can reach.
     */
    private String detectHost() {
        if (!settings.publicUrl().isEmpty()) {
            try {
                String host = URI.create(settings.publicUrl()).getHost();
                if (host != null) {
                    return host;
                }
            } catch (IllegalArgumentException ignored) {
                // fall back to the local address
            }
        }
        String host = settings.bind();
        if (host.isBlank() || host.equals("0.0.0.0")) {
            host = Bukkit.getIp();
        }
        if (host == null || host.isBlank() || host.equals("0.0.0.0")) {
            host = localNetworkAddress();
        }
        return host;
    }

    private static String localNetworkAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // fall through
        }
        return "localhost";
    }

    public boolean isWebRunning() {
        return web != null && web.isRunning();
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    /**
     * @return where Minecraft downloads the resource pack from, {@code null} if it cannot be offered
     */
    public String getPackBaseUrl() {
        if (tls == null || !tls.isSelfSigned()) {
            return publicUrl;
        }
        return web.hasPackServer() ? "http://" + detectHost() + ":" + settings.packPort() : null;
    }

    /** Whether phones will warn about the certificate. */
    public boolean isSelfSigned() {
        return tls != null && tls.isSelfSigned();
    }

    public Settings getSettings() {
        return settings;
    }

    public PhotoItems getItems() {
        return items;
    }

    public PictureStore getPictureStore() {
        return pictures;
    }
}
