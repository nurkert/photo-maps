package eu.nurkert.photomaps.command;

import eu.nurkert.photomaps.Messages;
import eu.nurkert.photomaps.PhotoMaps;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

/**
 * {@code /photomaps give [player] [amount]}, {@code /photomaps link} and {@code /photomaps reload}.
 */
public final class PhotoMapsCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("give", "link", "reload");

    private final PhotoMaps plugin;

    public PhotoMapsCommand(PhotoMaps plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        if (!SUBCOMMANDS.contains(sub)) {
            Messages.list(sender, "command.help").forEach(sender::sendMessage);
            return true;
        }
        if (!sender.hasPermission("photomaps.admin")) {
            Messages.send(sender, "command.no-permission");
            return true;
        }
        switch (sub) {
            case "give" -> give(sender, args);
            case "link" -> {
                if (sender instanceof Player player) {
                    plugin.openSession(player, -1);
                } else {
                    Messages.send(sender, "command.players-only");
                }
            }
            case "reload" -> {
                plugin.reload();
                Messages.send(sender, "command.reloaded");
            }
            default -> {}
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                Messages.send(sender, "command.player-not-found", "player", args[1]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            Messages.send(sender, "command.players-only");
            return;
        }
        int amount = 1;
        if (args.length > 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                // keep 1
            }
        }
        ItemStack codes = plugin.getItems().createCode(target, amount);
        for (ItemStack leftover : target.getInventory().addItem(codes).values()) {
            target.getWorld().dropItem(target.getLocation(), leftover);
        }
        Messages.send(sender, "command.given", "amount", amount, "player", target.getName());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("photomaps.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return null; // player names
        }
        return List.of();
    }
}
