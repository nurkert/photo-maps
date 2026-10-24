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
import eu.nurkert.photomaps.web.LetsEncrypt;
import eu.nurkert.photomaps.web.Sessions;
import eu.nurkert.photomaps.web.Tls;
import eu.nurkert.photomaps.web.WebServer;
import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.security.GeneralSecurityException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.SocketException;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // null without a lets-encrypt domain
    private LetsEncrypt letsEncrypt;
    private final AtomicBoolean issuing = new AtomicBoolean();
    // after a failed attempt, wait before asking Let's Encrypt again (it limits failed validations)
    private volatile long nextAttempt;

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
        // the lambda reads the field when a renderer is made, after it was assigned
        pictures = new PictureStore(
                this, mapId -> new QrRenderer(mapId, this::urlForMap, id -> pictures.codeOwner(id), palette));
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
            if (packs.isSelfHosted()) {
                // development builds: no release to download the pack from
                web.setPack(packs.pack(), packs.sha1());
            }
            plugins.registerEvents(packs, this);
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "The Java resource pack is missing, photo codes will look like paper", exception);
        }
        letsEncrypt = createLetsEncrypt();
        startWeb();
        if (settings.recipe()) {
            recipe.register();
        }
        pictures.attachLoaded();
        BedrockPackInstaller.checkGeyserSetup(this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, sessions::purge, 1200, 1200);
        // certificates renewed outside (certbot) are picked up within an hour, Let's Encrypt ones are renewed in time
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (letsEncrypt != null && !letsEncrypt.hasValidCertificate()) {
                requestCertificate();
            } else if (tls != null && tls.filesChanged()) {
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
        letsEncrypt = createLetsEncrypt();
        nextAttempt = 0;
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
                ? (tls != null ? "https://" : "http://") + host + (settings.port() == 443 ? "" : ":" + settings.port())
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
            } else if (tls.isSelfSigned() && letsEncrypt != null) {
                getLogger().info("Using a temporary self-signed certificate until Let's Encrypt has issued the "
                        + "certificate for " + letsEncrypt.domain() + ".");
            } else if (tls.isSelfSigned()) {
                getLogger().info("Using a self-signed certificate (SHA-256 " + tls.fingerprint() + "). Phones show a "
                        + "warning once; set web.https.lets-encrypt.domain for a trusted certificate.");
                if (web.hasPackServer()) {
                    getLogger().info("The resource pack is served on port " + settings.packPort() + " (plain HTTP, "
                            + "Minecraft refuses packs from self-signed servers; the pack is checked by its hash).");
                }
            }
            if (settings.publicUrl().isEmpty() && letsEncrypt == null) {
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
        if (letsEncrypt != null) {
            if (!letsEncrypt.hasValidCertificate()) {
                requestCertificate();
            }
            Date expiry = letsEncrypt.expiry();
            if (expiry != null && expiry.after(new Date())) {
                // still valid, even if it is renewed right now
                return Tls.fromPem(letsEncrypt.certificateFile(), letsEncrypt.keyFile());
            }
        }
        return Tls.selfSigned(new File(getDataFolder(), "tls/self-signed.p12"), host);
    }

    private LetsEncrypt createLetsEncrypt() {
        String domain = settings.letsEncryptDomain();
        if (domain.isEmpty() || !settings.https()) {
            return null;
        }
        try {
            return new LetsEncrypt(
                    new File(getDataFolder(), "tls/lets-encrypt"),
                    domain,
                    settings.letsEncryptEmail(),
                    URI.create(settings.acmeDirectory()),
                    settings.bind(),
                    settings.letsEncryptPort(),
                    LetsEncrypt.defaultClient());
        } catch (IllegalArgumentException exception) {
            getLogger().severe("web.https.lets-encrypt.domain '" + domain + "' is not a domain name (like "
                    + "photos.example.com). Let's Encrypt is not used.");
            return null;
        }
    }

    /**
     * Gets a certificate from Let's Encrypt in the background and switches the web server to it once it is there.
     */
    private void requestCertificate() {
        LetsEncrypt client = letsEncrypt;
        if (client == null || System.currentTimeMillis() < nextAttempt || !issuing.compareAndSet(false, true)) {
            return;
        }
        getLogger().info("Asking Let's Encrypt for a certificate for " + client.domain() + "...");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                client.obtain();
                getServer().getScheduler().runTask(this, () -> {
                    getLogger().info("Got the certificate for " + client.domain() + ", valid until "
                            + client.expiry() + ". It is renewed automatically.");
                    if (client == letsEncrypt && isEnabled()) {
                        web.stop();
                        startWeb();
                    }
                });
            } catch (BindException exception) {
                failed("Port " + settings.letsEncryptPort() + " could not be opened for Let's Encrypt's "
                        + "check: another program uses it, or ports below 1024 need root. Forward port 80 of your "
                        + "router/firewall to a free port such as 8080 and set web.https.lets-encrypt.challenge-port "
                        + "to it.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                failed("Let's Encrypt could not issue a certificate for " + client.domain() + ": "
                        + reason(exception) + ". Check that the domain points to this server and that port 80 "
                        + "of the domain reaches web.https.lets-encrypt.challenge-port.");
            } finally {
                issuing.set(false);
            }
        });
    }

    private static String reason(Throwable exception) {
        if (exception instanceof ConnectException
                || exception instanceof HttpConnectTimeoutException
                || exception instanceof UnknownHostException) {
            return "the CA could not be reached (" + exception.getClass().getSimpleName() + ")";
        }
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : exception.getClass().getSimpleName();
    }

    private void failed(String message) {
        nextAttempt = System.currentTimeMillis() + 6 * 60 * 60 * 1000L;
        getLogger().warning(message + " Next try in 6 hours or with /photomaps reload.");
    }

    /**
     * The host of the configured public URL, else this machine's address in the local network, which phones in the
     * same Wi-Fi can reach.
     */
    private String detectHost() {
        if (settings.publicUrl().isEmpty() && letsEncrypt != null) {
            return letsEncrypt.domain();
        }
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
