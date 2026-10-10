package de.louis.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SellPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final int SIZE = 54;
    private static final int GRID_END = 45;     // Slots 0-44: Verkaufsfläche
    private static final int ALL_SLOT = 47;     // Alles verkaufen
    private static final int SELL_SLOT = 49;    // Verkaufen
    private static final int CANCEL_SLOT = 51;  // Abbrechen

    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private EconomyBridge economy;

    /** Kennzeichnet unser Inventar. */
    private static final class SellHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------ Lebenszyklus

    @Override
    public void onEnable() {
        saveDefaultConfig();
        economy = new EconomyBridge(this);
        loadPrices();

        var cmd = getCommand("sell");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Offene Verkaufsfenster: Items zurückgeben
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof SellHolder) {
                returnItems(p, top);
                p.closeInventory();
            }
        }
    }

    private void loadPrices() {
        prices.clear();
        ConfigurationSection s = getConfig().getConfigurationSection("prices");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m == null) {
                getLogger().warning("Unbekanntes Item in prices: " + key);
                continue;
            }
            double v = s.getDouble(key);
            if (v > 0) prices.put(m, v);
        }
    }

    // --------------------------------------------------------------- Befehl

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("sell.admin")) {
                sender.sendMessage(msg("messages.no-permission"));
                return true;
            }
            reloadConfig();
            loadPrices();
            sender.sendMessage(msg("messages.reloaded", "{count}", String.valueOf(prices.size())));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(msg("messages.players-only"));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("all")) {
            sell(p, null, true);
            return true;
        }

        openGui(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> opts = new ArrayList<>(List.of("all"));
        if (sender.hasPermission("sell.admin")) opts.add("reload");
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return opts.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    // ------------------------------------------------------------------ GUI

    private void openGui(Player p) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, MM.deserialize(getConfig().getString("gui.title", "Verkaufen")));
        holder.inventory = inv;

        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = GRID_END; i < SIZE; i++) inv.setItem(i, filler);

        inv.setItem(ALL_SLOT, button(Material.CHEST,
                getConfig().getString("gui.all-name", "Alles verkaufen"),
                getConfig().getStringList("gui.all-lore")));
        inv.setItem(CANCEL_SLOT, button(Material.BARRIER,
                getConfig().getString("gui.cancel-name", "Abbrechen"),
                getConfig().getStringList("gui.cancel-lore")));
        updateSellButton(inv);

        p.openInventory(inv);
    }

    private ItemStack button(Material material, String name, List<String> lore, String... replacements) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(replace(name, replacements)));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(noItalic(replace(line, replacements)));
        if (!lines.isEmpty()) meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static String replace(String text, String... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return text;
    }

    private void updateSellButton(Inventory inv) {
        double total = 0;
        int count = 0;
        for (int i = 0; i < GRID_END; i++) {
            ItemStack it = inv.getItem(i);
            double price = priceOf(it);
            if (price > 0) {
                total += price * it.getAmount();
                count += it.getAmount();
            }
        }
        String amount = fmt(round2(total));
        inv.setItem(SELL_SLOT, button(Material.EMERALD,
                getConfig().getString("gui.sell-name", "Verkaufen"),
                getConfig().getStringList("gui.sell-lore"),
                "{amount}", amount, "{count}", String.valueOf(count)));
    }

    private void scheduleUpdate(Inventory inv) {
        Bukkit.getScheduler().runTask(this, () -> updateSellButton(inv));
    }

    // --------------------------------------------------------------- Events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;

        if (clicked == top) {
            if (e.getSlot() >= GRID_END) {
                e.setCancelled(true);
                handleButton(p, top, e.getSlot());
                return;
            }
        } else if (e.isShiftClick()) {
            // Shift-Klick aus dem Inventar: nur in die Verkaufsfläche schieben, nie auf die Buttons
            e.setCancelled(true);
            ItemStack current = e.getCurrentItem();
            if (current == null || current.getType().isAir()) return;
            ItemStack rest = addToGrid(top, current.clone());
            e.setCurrentItem(rest.getAmount() <= 0 ? null : rest);
        }
        scheduleUpdate(top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= GRID_END && raw < SIZE) {
                e.setCancelled(true);
                return;
            }
        }
        scheduleUpdate(top);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof SellHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        returnItems(p, inv);
    }

    private void handleButton(Player p, Inventory top, int slot) {
        if (slot == SELL_SLOT) {
            sell(p, top, false);
            closeLater(p);
        } else if (slot == ALL_SLOT) {
            sell(p, top, true);
            closeLater(p);
        } else if (slot == CANCEL_SLOT) {
            closeLater(p);
        }
    }

    private void closeLater(Player p) {
        Bukkit.getScheduler().runTask(this, p::closeInventory);
    }

    /** Gibt alles aus der Verkaufsfläche zurück (oder wirft es auf den Boden, wenn das Inventar voll ist). */
    private void returnItems(Player p, Inventory inv) {
        for (int i = 0; i < GRID_END; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            inv.setItem(i, null);
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
    }

    private ItemStack addToGrid(Inventory top, ItemStack stack) {
        for (int i = 0; i < GRID_END && stack.getAmount() > 0; i++) {
            ItemStack s = top.getItem(i);
            if (s != null && s.isSimilar(stack) && s.getAmount() < s.getMaxStackSize()) {
                int move = Math.min(stack.getAmount(), s.getMaxStackSize() - s.getAmount());
                s.setAmount(s.getAmount() + move);
                top.setItem(i, s);
                stack.setAmount(stack.getAmount() - move);
            }
        }
        for (int i = 0; i < GRID_END && stack.getAmount() > 0; i++) {
            ItemStack s = top.getItem(i);
            if (s == null || s.getType().isAir()) {
                int move = Math.min(stack.getAmount(), stack.getMaxStackSize());
                ItemStack put = stack.clone();
                put.setAmount(move);
                top.setItem(i, put);
                stack.setAmount(stack.getAmount() - move);
            }
        }
        return stack;
    }

    // -------------------------------------------------------------- Verkauf

    private double priceOf(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        if (getConfig().getBoolean("only-plain-items", true) && it.hasItemMeta()) return 0;
        return prices.getOrDefault(it.getType(), 0.0);
    }

    /**
     * Verkauft verkaufbare Items aus der Verkaufsfläche (grid) und optional aus dem Spielerinventar.
     * Items werden erst entfernt, wenn die Gutschrift sicher geklappt hat (sonst Rückgabe).
     */
    private void sell(Player p, Inventory grid, boolean includeInventory) {
        Map<Integer, ItemStack> fromGrid = new HashMap<>();
        Map<Integer, ItemStack> fromInv = new HashMap<>();
        double total = 0;
        int count = 0;

        if (grid != null) {
            for (int i = 0; i < GRID_END; i++) {
                ItemStack it = grid.getItem(i);
                double price = priceOf(it);
                if (price <= 0) continue;
                total += price * it.getAmount();
                count += it.getAmount();
                fromGrid.put(i, it.clone());
            }
        }

        PlayerInventory pi = p.getInventory();
        if (includeInventory) {
            for (int i = 0; i < 36; i++) {
                ItemStack it = pi.getItem(i);
                double price = priceOf(it);
                if (price <= 0) continue;
                total += price * it.getAmount();
                count += it.getAmount();
                fromInv.put(i, it.clone());
            }
        }

        total = round2(total);
        if (count == 0 || total <= 0) {
            p.sendMessage(msg("messages.nothing"));
            return;
        }

        if (grid != null) {
            for (int slot : fromGrid.keySet()) grid.setItem(slot, null);
        }
        for (int slot : fromInv.keySet()) pi.setItem(slot, null);

        if (!economy.deposit(p, total)) {
            if (grid != null) {
                for (Map.Entry<Integer, ItemStack> e : fromGrid.entrySet()) grid.setItem(e.getKey(), e.getValue());
            }
            for (Map.Entry<Integer, ItemStack> e : fromInv.entrySet()) pi.setItem(e.getKey(), e.getValue());
            p.sendMessage(msg("messages.failed"));
            return;
        }

        p.sendMessage(msg("messages.sold", "{count}", String.valueOf(count), "{amount}", fmt(total)));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    // --------------------------------------------------------------- Helfer

    Component msg(String path, String... replacements) {
        String text = getConfig().getString(path, "");
        text = text.replace("{prefix}", getConfig().getString("messages.prefix", ""));
        return MM.deserialize(replace(text, replacements));
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
