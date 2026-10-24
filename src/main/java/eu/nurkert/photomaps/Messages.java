package eu.nurkert.photomaps;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Chat and item texts in English and German, picked by each player's client language.
 *
 * <p>Both files are copied into the plugin folder, where admins can edit them; keys missing there fall back to the
 * bundled defaults.
 */
public final class Messages {

    private static final List<String> LANGUAGES = List.of("en", "de");

    private static final Map<String, YamlConfiguration> bundles = new HashMap<>();

    private Messages() {}

    public static void load(JavaPlugin plugin) {
        bundles.clear();
        for (String language : LANGUAGES) {
            String name = "messages_" + language + ".yml";
            File file = new File(plugin.getDataFolder(), name);
            if (!file.exists()) {
                plugin.saveResource(name, false);
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            try (InputStream in = plugin.getResource(name)) {
                if (in != null) {
                    config.setDefaults(
                            YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
                }
            } catch (IOException ignored) {
                // the defaults are inside the jar, so this cannot really happen
            }
            bundles.put(language, config);
        }
    }

    static String language(CommandSender sender) {
        if (sender instanceof Player player) {
            String locale = player.getLocale().toLowerCase(Locale.ROOT);
            for (String language : LANGUAGES) {
                if (locale.startsWith(language)) {
                    return language;
                }
            }
        }
        return "en";
    }

    /**
     * @param replacements pairs of placeholder (without percent signs) and value
     */
    public static String get(CommandSender sender, String key, Object... replacements) {
        // getString(key, fallback) would skip the bundled defaults, so texts added in an update would show as keys
        String text = bundles.get(language(sender)).getString(key);
        if (text == null) {
            text = key;
        }
        return colour(replace(text, replacements));
    }

    public static List<String> list(CommandSender sender, String key, Object... replacements) {
        return bundles.get(language(sender)).getStringList(key).stream()
                .map(line -> colour(replace(line, replacements)))
                .toList();
    }

    /** Sends a message with the prefix. */
    public static void send(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(get(sender, "prefix") + get(sender, key, replacements));
    }

    private static String replace(String text, Object... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace("%" + replacements[i] + "%", String.valueOf(replacements[i + 1]));
        }
        return text;
    }

    private static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
