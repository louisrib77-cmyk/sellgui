package de.louis.sell;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Locale;

/** Gutschrift über Vault (per Reflection) oder über einen Konsolenbefehl. */
final class EconomyBridge {

    private final SellPlugin plugin;
    private Object provider;
    private Method mDeposit;
    private Method mSuccess;

    EconomyBridge(SellPlugin plugin) {
        this.plugin = plugin;
    }

    private String mode() {
        return plugin.getConfig().getString("economy.mode", "auto").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean tryVault() {
        if (provider != null) return true;
        try {
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration((Class) eco);
            if (rsp == null) return false;
            Object p = rsp.getProvider();
            Method deposit = eco.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            Class<?> resp = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            Method success = resp.getMethod("transactionSuccess");
            mDeposit = deposit;
            mSuccess = success;
            provider = p;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            provider = null;
            return false;
        }
    }

    boolean deposit(OfflinePlayer p, double amount) {
        if (amount <= 0) return false;
        if (!mode().equals("command") && tryVault()) {
            try {
                Object r = mDeposit.invoke(provider, p, amount);
                return (Boolean) mSuccess.invoke(r);
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().warning("Vault-Gutschrift fehlgeschlagen: " + e.getMessage());
                return false;
            }
        }
        if (mode().equals("vault")) return false;

        String name = p.getName();
        String template = plugin.getConfig().getString("economy.give-command", "");
        if (name == null || template.isBlank()) return false;
        String cmd = template
                .replace("{player}", name)
                .replace("{amount}", SellPlugin.fmt(amount));
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }
}
