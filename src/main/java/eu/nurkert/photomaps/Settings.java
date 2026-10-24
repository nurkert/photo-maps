package eu.nurkert.photomaps;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * The values of config.yml, read once per (re)load.
 */
public record Settings(
        int port,
        String bind,
        String publicUrl,
        boolean https,
        String certificate,
        String privateKey,
        String keyStore,
        String keyStorePassword,
        int packPort,
        int sessionMinutes,
        int maxColumns,
        int maxRows,
        boolean consumeEmptyMaps,
        boolean autoPlace,
        boolean recipe,
        boolean resourcePack,
        String resourcePackUrl,
        boolean resourcePackRequired,
        String resourcePackPrompt) {

    public static Settings from(FileConfiguration config) {
        return new Settings(
                config.getInt("web.port", 8100),
                config.getString("web.bind", "0.0.0.0"),
                trimSlash(config.getString("web.public-url", "")),
                config.getBoolean("web.https.enabled", true),
                config.getString("web.https.certificate", "").trim(),
                config.getString("web.https.private-key", "").trim(),
                config.getString("web.https.keystore", "").trim(),
                config.getString("web.https.keystore-password", ""),
                config.getInt("web.https.pack-port", 8101),
                Math.max(1, config.getInt("upload.session-minutes", 15)),
                clamp(config.getInt("upload.max-columns", 5)),
                clamp(config.getInt("upload.max-rows", 5)),
                config.getBoolean("upload.consume-empty-maps", true),
                config.getBoolean("frames.auto-place", true),
                config.getBoolean("recipe.enabled", true),
                config.getBoolean("resource-pack.enabled", true),
                config.getString("resource-pack.url", "").trim(),
                config.getBoolean("resource-pack.required", false),
                config.getString("resource-pack.prompt", ""));
    }

    // a picture can be at most 10x10 maps; more would freeze the client that renders them
    private static int clamp(int value) {
        return Math.max(1, Math.min(10, value));
    }

    private static String trimSlash(String url) {
        url = url == null ? "" : url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
