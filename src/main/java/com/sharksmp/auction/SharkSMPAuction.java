package com.sharksmp.auction;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

public class SharkSMPAuction extends JavaPlugin implements Listener {
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, Map<Integer, String>> openMenus = new HashMap<>();
    private static final String TITLE = ChatColor.DARK_AQUA + "SharkSMP " + ChatColor.BLACK + "| Leilao";

    @Override public void onEnable() {
        saveDefaultConfig();
        if (Bukkit.getPluginManager().getPlugin("SharkSMP-Economy") == null) {
            getLogger().severe("SharkSMP-Economy nao encontrado! Instale-o antes deste plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        dataFile = new File(getDataFolder(), "auctions.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ah")).setExecutor(this);
        Objects.requireNonNull(getCommand("ah")).setTabCompleter((sender, command, alias, args) ->
                args.length == 1 ? Arrays.asList("vender", "meus", "cancelar", "ajuda") : Collections.emptyList());
        getLogger().info("SharkSMP-Auction ativado.");
    }

    @Override public void onDisable() { saveData(); }

    private void saveData() { try { data.save(dataFile); } catch (Exception e) { getLogger().severe("Falha ao salvar leiloes: " + e.getMessage()); } }
    private String path(String id) { return "listings." + id; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Somente jogadores podem usar este comando."); return true; }
        if (args.length == 0) { openAuction(player); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "vender":
                if (args.length != 2) { player.sendMessage(color("&cUse: /ah vender <preco>")); return true; }
                double price;
                try { price = Double.parseDouble(args[1].replace(',', '.')); }
                catch (NumberFormatException ex) { player.sendMessage(color("&cPreco invalido.")); return true; }
                if (!Double.isFinite(price) || price <= 0 || price > 1_000_000_000) { player.sendMessage(color("&cO preco deve ser maior que zero.")); return true; }
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) { player.sendMessage(color("&cSegure o item que deseja anunciar.")); return true; }
                int limit = getConfig().getInt("limite-anuncios", 5);
                if (countSeller(player.getUniqueId()) >= limit) { player.sendMessage(color("&cVoce ja atingiu o limite de " + limit + " anuncios.")); return true; }
                String id = UUID.randomUUID().toString().substring(0, 8);
                data.set(path(id) + ".seller", player.getUniqueId().toString());
                data.set(path(id) + ".seller-name", player.getName());
                data.set(path(id) + ".price", price);
                data.set(path(id) + ".item", hand.clone());
                player.getInventory().setItemInMainHand(null);
                saveData();
                player.sendMessage(color("&aItem anunciado por &e" + money(price) + "&a! ID: &f" + id));
                return true;
            case "meus":
                player.sendMessage(color("&bSeus anuncios:"));
                boolean found = false;
                for (String id : listingIds()) if (player.getUniqueId().toString().equals(data.getString(path(id) + ".seller"))) {
                    player.sendMessage(color("&7ID &f" + id + " &8- &e" + money(data.getDouble(path(id) + ".price")) + " &7(" + itemName(data.getItemStack(path(id) + ".item")) + ")")); found = true;
                }
                if (!found) player.sendMessage(color("&7Voce nao tem anuncios ativos."));
                return true;
            case "cancelar":
                if (args.length != 2) { player.sendMessage(color("&cUse: /ah cancelar <id>")); return true; }
                cancelListing(player, args[1]);
                return true;
            case "ajuda":
                player.sendMessage(color("&b/ah &7- abrir leilao"));
                player.sendMessage(color("&b/ah vender <preco> &7- anunciar item da mao"));
                player.sendMessage(color("&b/ah meus &7- ver seus anuncios"));
                player.sendMessage(color("&b/ah cancelar <id> &7- retirar anuncio"));
                return true;
            default: openAuction(player); return true;
        }
    }

    private void cancelListing(Player player, String id) {
        if (!data.contains(path(id))) { player.sendMessage(color("&cAnuncio nao encontrado.")); return; }
        if (!player.getUniqueId().toString().equals(data.getString(path(id) + ".seller")) && !player.hasPermission("sharksmp.auction.admin")) {
            player.sendMessage(color("&cVoce nao pode retirar esse anuncio.")); return;
        }
        ItemStack item = data.getItemStack(path(id) + ".item");
        if (item != null && item.getType() != Material.AIR) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
        data.set(path(id), null); saveData(); player.sendMessage(color("&aAnuncio retirado e item devolvido."));
    }

    private int countSeller(UUID uuid) { int n = 0; for (String id : listingIds()) if (uuid.toString().equals(data.getString(path(id)+".seller"))) n++; return n; }
    private List<String> listingIds() { ConfigurationSection sec = data.getConfigurationSection("listings"); return sec == null ? new ArrayList<>() : new ArrayList<>(sec.getKeys(false)); }

    private void openAuction(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        Map<Integer, String> slots = new HashMap<>();
        List<String> ids = listingIds();
        int slot = 0;
        for (String id : ids) {
            if (slot >= 45) break;
            ItemStack item = data.getItemStack(path(id) + ".item");
            if (item == null || item.getType() == Material.AIR) continue;
            ItemStack display = item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(" "); lore.add(color("&ePreco: &f" + money(data.getDouble(path(id) + ".price"))));
                lore.add(color("&7Vendedor: &f" + data.getString(path(id) + ".seller-name", "Desconhecido")));
                lore.add(color("&aClique para comprar"));
                meta.setLore(lore); display.setItemMeta(meta);
            }
            inv.setItem(slot, display); slots.put(slot, id); slot++;
        }
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta(); im.setDisplayName(color("&bSharkSMP Leilao"));
        im.setLore(Arrays.asList(color("&7Anuncios ativos: &f" + ids.size()), color("&7Use &f/ah vender <preco>"), color("&7para anunciar um item."))); info.setItemMeta(im);
        inv.setItem(49, info);
        ItemStack refresh = new ItemStack(Material.SUNFLOWER); ItemMeta rm = refresh.getItemMeta(); rm.setDisplayName(color("&aAtualizar")); refresh.setItemMeta(rm); inv.setItem(53, refresh);
        openMenus.put(player.getUniqueId(), slots); player.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getRawSlot() == 53) { openAuction(player); return; }
        String id = openMenus.getOrDefault(player.getUniqueId(), Collections.emptyMap()).get(event.getRawSlot());
        if (id != null) buy(player, id);
    }

    private void buy(Player buyer, String id) {
        if (!data.contains(path(id))) { buyer.sendMessage(color("&cEsse anuncio nao esta mais disponivel.")); openAuction(buyer); return; }
        String sellerText = data.getString(path(id) + ".seller", "");
        if (buyer.getUniqueId().toString().equals(sellerText)) { buyer.sendMessage(color("&cVoce nao pode comprar seu proprio item.")); return; }
        ItemStack item = data.getItemStack(path(id) + ".item");
        if (item == null || item.getType() == Material.AIR) { buyer.sendMessage(color("&cItem invalido neste anuncio.")); return; }
        if (buyer.getInventory().firstEmpty() == -1) { buyer.sendMessage(color("&cSeu inventario esta cheio.")); return; }
        double price = data.getDouble(path(id) + ".price"); UUID seller;
        try { seller = UUID.fromString(sellerText); } catch (Exception ex) { buyer.sendMessage(color("&cVendedor invalido.")); return; }
        try {
            Object economy = economyObject();
            Method has = economy.getClass().getMethod("has", UUID.class, double.class);
            Method take = economy.getClass().getMethod("take", UUID.class, double.class);
            Method add = economy.getClass().getMethod("add", UUID.class, double.class);
            if (!(Boolean) has.invoke(economy, buyer.getUniqueId(), price)) { buyer.sendMessage(color("&cSaldo insuficiente. Preco: &e" + money(price))); return; }
            if (!(Boolean) take.invoke(economy, buyer.getUniqueId(), price)) { buyer.sendMessage(color("&cNao foi possivel concluir o pagamento.")); return; }
            add.invoke(economy, seller, price);
            data.set(path(id), null); saveData();
            buyer.getInventory().addItem(item);
            buyer.sendMessage(color("&aCompra realizada por &e" + money(price) + "&a!"));
            Player onlineSeller = Bukkit.getPlayer(seller);
            if (onlineSeller != null) onlineSeller.sendMessage(color("&aSeu item foi vendido por &e" + money(price) + "&a!"));
            openAuction(buyer);
        } catch (Exception ex) {
            getLogger().severe("Erro na integracao com SharkSMP-Economy: " + ex.getMessage());
            buyer.sendMessage(color("&cErro ao acessar a economia. Avise um administrador."));
        }
    }

    private Object economyObject() throws Exception {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("SharkSMP-Economy");
        if (plugin == null) throw new IllegalStateException("SharkSMP-Economy ausente");
        return plugin.getClass().getMethod("getEconomy").invoke(plugin);
    }
    private String money(double value) { return String.format(Locale.US, "$%.2f", value); }
    private String itemName(ItemStack item) { return item == null ? "item" : item.getType().name(); }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { openMenus.remove(event.getPlayer().getUniqueId()); }
}
