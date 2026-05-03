package net.elpixedge.core.player;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;

import java.util.*;

public class StashManager {
    private final Map<UUID, List<ItemStack>> stash = new HashMap<>();
    private final Map<UUID, Long> expiry = new HashMap<>();
    private static final long EXPIRY_TIME = 20 * 60 * 1000L; // 20 minutes

    public void addItem(Player p, ItemStack item) {
        UUID uuid = p.getUniqueId();
        stash.computeIfAbsent(uuid, k -> new ArrayList<>()).add(item);
        if (!expiry.containsKey(uuid)) {
            expiry.put(uuid, System.currentTimeMillis() + EXPIRY_TIME);
        }

        p.sendMessage(ChatColor.GOLD + "[Warning] Your inventory is full! Some items were sent to your Temporary Stash. " +
                "⚠ Item in stash will be deleted after 20 minute of first item ⚠");
        TextComponent click = new TextComponent(ChatColor.YELLOW + ChatColor.UNDERLINE.toString() + "[Click here to claim or type /claimstash]");
        click.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/claimstash"));
        p.spigot().sendMessage(click);
    }

    public void claimStash(Player p) {
        UUID uuid = p.getUniqueId();
        if (!stash.containsKey(uuid) || stash.get(uuid).isEmpty()) {
            p.sendMessage(ChatColor.RED + "Your stash is empty.");
            return;
        }
        
        List<ItemStack> items = new ArrayList<>(stash.get(uuid));
        List<ItemStack> remaining = new ArrayList<>();
        
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(item.clone());
            if (!leftover.isEmpty()) {
                remaining.addAll(leftover.values());
            }
        }
        
        if (remaining.isEmpty()) {
            stash.remove(uuid);
            expiry.remove(uuid);
            p.sendMessage(ChatColor.GREEN + "All items claimed from your stash!");
        } else {
            stash.put(uuid, remaining);
            p.sendMessage(ChatColor.YELLOW + "Inventory still full! " + remaining.size() + " items remain in your stash.");
        }
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = expiry.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now > entry.getValue()) {
                stash.remove(entry.getKey());
                it.remove();
            }
        }
    }
}
