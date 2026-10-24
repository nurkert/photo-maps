package eu.nurkert.photomaps.item;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The recipe of the photo code: four ink sacs around an empty map.
 *
 * <pre>
 *   . I .
 *   I M I
 *   . I .
 * </pre>
 *
 * <p>Photo codes are paper underneath, but can never be used as paper in other recipes.
 */
public final class RecipeListener implements Listener {

    private final PhotoItems items;
    private final NamespacedKey key;
    private boolean registered;

    public RecipeListener(JavaPlugin plugin, PhotoItems items) {
        this.items = items;
        this.key = new NamespacedKey(plugin, "photo_code");
    }

    public void register() {
        if (registered) {
            return;
        }
        ShapedRecipe recipe = new ShapedRecipe(key, items.createCode(Bukkit.getConsoleSender(), 1));
        recipe.shape(" I ", "IMI", " I ");
        recipe.setIngredient('I', Material.INK_SAC);
        recipe.setIngredient('M', Material.MAP);
        Bukkit.addRecipe(recipe);
        registered = true;
        Bukkit.getOnlinePlayers().forEach(player -> player.discoverRecipe(key));
    }

    public void unregister() {
        if (registered) {
            Bukkit.removeRecipe(key);
            registered = false;
        }
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        if (registered) {
            event.getPlayer().discoverRecipes(List.of(key));
        }
    }

    @EventHandler
    public void on(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        NamespacedKey recipe = event.getRecipe() instanceof Keyed keyed ? keyed.getKey() : null;
        HumanEntity crafter = event.getView().getPlayer();
        if (key.equals(recipe)) {
            // the result is in the crafter's language, and only for those who may use it
            inventory.setResult(crafter.hasPermission("photomaps.use") ? items.createCode(crafter, 1) : null);
            return;
        }
        for (ItemStack item : inventory.getMatrix()) {
            if (items.isCode(item)) {
                inventory.setResult(null);
                return;
            }
        }
    }
}
