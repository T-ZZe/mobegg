package fr.skills;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SkillsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // ====== REGLAGES : GAIN DE POINTS ======
    static final int POINTS_PLAYER_KILL = 4;                    // 4 points par joueur tué
    static final int MOBS_PER_REWARD = 150, POINTS_MOBS = 2;    // 2 points tous les 150 monstres tués
    static final int CROPS_PER_REWARD = 500, POINTS_CROPS = 3;  // 3 points tous les 500 cultures mûres cassées
    static final int ORES_PER_REWARD = 25, POINTS_ORES = 4;     // 4 points tous les 25 minerais précieux minés

    // ====== REGLAGES : COMPETENCES ======
    static final int WITHER_CHANCE_PERCENT = 10;   // chance de Wither quand tu frappes
    static final int WITHER_SECONDS = 5;           // durée du Wither II
    static final int DASH_MAX_USES = 3;            // 3 dashs ...
    static final int DASH_WINDOW_SECONDS = 30;     // ... toutes les 30 secondes
    static final long DASH_DELAY_MS = 600;         // anti double-clic
    static final double BONUS_HEALTH = 6.0;        // 3 coeurs

    static final Set<Material> ORES = EnumSet.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE, Material.ANCIENT_DEBRIS);
    static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS,
            Material.POTATOES, Material.BEETROOTS);

    // ====== LES 4 VOIES ======
    enum Path {
        SPEED("speed", "Voie de la Vitesse", Material.SUGAR, 1, "Cours plus vite, fonce avec le dash."),
        FORCE("force", "Voie de la Force", Material.IRON_SWORD, 3, "Frappe plus fort, empoisonne tes ennemis."),
        RESIST("resist", "Voie de la Résistance", Material.SHIELD, 5, "Encaisse les coups, résiste au feu."),
        FARM("farm", "Voie du Fermier", Material.WHEAT, 7, "Travaille vite, commerce mieux.");

        final String id, label, desc;
        final Material icon;
        final int slot;

        Path(String id, String label, Material icon, int slot, String desc) {
            this.id = id; this.label = label; this.icon = icon; this.slot = slot; this.desc = desc;
        }

        static Path byId(String id) {
            for (Path p : values()) if (p.id.equals(id)) return p;
            return null;
        }

        static Path bySlot(int slot) {
            for (Path p : values()) if (p.slot == slot) return p;
            return null;
        }
    }

    // ====== LES COMPETENCES (NOEUDS DE L'ARBRE) ======
    static class Node {
        final String id, name, desc;
        final Path path;
        final int tier, cost, slot;
        final Material icon;
        final Map<PotionEffectType, Integer> effects = new LinkedHashMap<>();
        boolean health, wither, dash;

        Node(String id, Path path, int tier, int cost, Material icon, String name, String desc) {
            this.id = id; this.path = path; this.tier = tier; this.cost = cost;
            this.icon = icon; this.name = name; this.desc = desc;
            this.slot = 9 * tier + path.slot; // lignes 1 à 3
        }

        Node eff(PotionEffectType t, int amp) { effects.put(t, amp); return this; }
        Node health() { health = true; return this; }
        Node wither() { wither = true; return this; }
        Node dash() { dash = true; return this; }
    }

    static final List<Node> NODES = new ArrayList<>();
    static final Set<PotionEffectType> MANAGED = new HashSet<>();
    static Node N_DASH, N_WITHER;

    private static Node add(Node n) {
        NODES.add(n);
        MANAGED.addAll(n.effects.keySet());
        return n;
    }

    static {
        // --- Vitesse ---
        add(new Node("speed1", Path.SPEED, 1, 0, Material.FEATHER, "Vitesse I",
                "Effet Vitesse I permanent.").eff(PotionEffectType.SPEED, 0));
        add(new Node("speed2", Path.SPEED, 2, 15, Material.SUGAR, "Vitesse II",
                "Effet Vitesse II permanent.").eff(PotionEffectType.SPEED, 1));
        N_DASH = add(new Node("dash", Path.SPEED, 3, 30, Material.ENDER_PEARL, "Dash",
                "Clic droit avec le sceptre pour foncer vers l'avant.").dash());

        // --- Force ---
        add(new Node("force1", Path.FORCE, 1, 5, Material.IRON_SWORD, "Force I",
                "Effet Force I permanent.").eff(PotionEffectType.STRENGTH, 0));
        add(new Node("force2", Path.FORCE, 2, 20, Material.DIAMOND_SWORD, "Force II",
                "Effet Force II permanent.").eff(PotionEffectType.STRENGTH, 1));
        N_WITHER = add(new Node("wither", Path.FORCE, 3, 30, Material.WITHER_SKELETON_SKULL, "Toucher du Wither",
                WITHER_CHANCE_PERCENT + "% de chance d'infliger Wither II (" + WITHER_SECONDS + "s) quand tu frappes.").wither());

        // --- Résistance ---
        add(new Node("res1", Path.RESIST, 1, 0, Material.IRON_CHESTPLATE, "Résistance I",
                "Effet Résistance I permanent.").eff(PotionEffectType.RESISTANCE, 0));
        add(new Node("res2", Path.RESIST, 2, 18, Material.DIAMOND_CHESTPLATE, "Résistance II + Feu",
                "Résistance II et Résistance au feu I permanents.")
                .eff(PotionEffectType.RESISTANCE, 1).eff(PotionEffectType.FIRE_RESISTANCE, 0));
        add(new Node("res3", Path.RESIST, 3, 30, Material.GOLDEN_APPLE, "3 cœurs bonus",
                "Tu gagnes 3 cœurs de vie maximum.").health());

        // --- Fermier ---
        add(new Node("farm1", Path.FARM, 1, 0, Material.STONE_HOE, "Célérité I",
                "Effet Célérité I permanent.").eff(PotionEffectType.HASTE, 0));
        add(new Node("farm2", Path.FARM, 2, 14, Material.EMERALD, "Célérité II + Héros III",
                "Célérité II et Héros du village III permanents.")
                .eff(PotionEffectType.HASTE, 1).eff(PotionEffectType.HERO_OF_THE_VILLAGE, 2));
        add(new Node("farm3", Path.FARM, 3, 28, Material.HEART_OF_THE_SEA, "Conduit + Héros V",
                "Force du conduit et Héros du village V permanents.")
                .eff(PotionEffectType.CONDUIT_POWER, 0).eff(PotionEffectType.HERO_OF_THE_VILLAGE, 4));
    }

    static Node nodeBySlot(int slot) {
        for (Node n : NODES) if (n.slot == slot) return n;
        return null;
    }

    static Node nodeOf(Path p, int tier) {
        for (Node n : NODES) if (n.path == p && n.tier == tier) return n;
        return null;
    }

    // ====== DONNEES ======
    static class Data {
        int points;
        int kills;        // monstres tués
        int playerKills;  // joueurs tués
        int crops;        // cultures mûres cassées
        int ores;         // minerais précieux minés
        String path;      // voie choisie (null = aucune)
        String wand;      // numéro de série du seul sceptre valide
        Set<String> owned = new HashSet<>();
        Set<String> off = new HashSet<>(); // compétences désactivées
    }

    static class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final Map<UUID, Data> data = new HashMap<>();
    private final Map<UUID, Deque<Long>> dashUses = new HashMap<>();
    private final Map<UUID, Long> pendingReset = new HashMap<>();
    private final Set<String> placed = new HashSet<>(); // minerais posés par des joueurs
    private File dataFile;
    private File placedFile;
    private NamespacedKey wandKey;
    private NamespacedKey serialKey;
    private NamespacedKey healthKey;

    @Override
    public void onEnable() {
        wandKey = new NamespacedKey(this, "wand");
        serialKey = new NamespacedKey(this, "wand_serial");
        healthKey = new NamespacedKey(this, "bonus_health");
        dataFile = new File(getDataFolder(), "data.yml");
        placedFile = new File(getDataFolder(), "placed.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("skills").setExecutor(this);
        getServer().getScheduler().runTaskTimer(this, this::save, 6000L, 6000L);
        // Applique les effets permanents toutes les secondes
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) applyEffects(p);
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ====== SAUVEGARDE ======
    private void load() {
        if (placedFile.exists()) {
            placed.addAll(YamlConfiguration.loadConfiguration(placedFile).getStringList("blocks"));
        }
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : cfg.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Data d = new Data();
                d.points = cfg.getInt(key + ".points");
                d.kills = cfg.getInt(key + ".kills");
                d.playerKills = cfg.getInt(key + ".playerKills");
                d.crops = cfg.getInt(key + ".crops");
                d.ores = cfg.getInt(key + ".ores");
                d.path = cfg.getString(key + ".path");
                d.wand = cfg.getString(key + ".wand");
                d.owned.addAll(cfg.getStringList(key + ".owned"));
                d.off.addAll(cfg.getStringList(key + ".off"));
                // Ancien système : on rembourse les anciens niveaux
                ConfigurationSection old = cfg.getConfigurationSection(key + ".levels");
                if (old != null) {
                    for (String k : old.getKeys(false)) {
                        int l = old.getInt(k);
                        d.points += l * (l + 1) / 2;
                    }
                }
                data.put(id, d);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Data> en : data.entrySet()) {
            String k = en.getKey().toString();
            Data d = en.getValue();
            cfg.set(k + ".points", d.points);
            cfg.set(k + ".kills", d.kills);
            cfg.set(k + ".playerKills", d.playerKills);
            cfg.set(k + ".crops", d.crops);
            cfg.set(k + ".ores", d.ores);
            cfg.set(k + ".path", d.path);
            cfg.set(k + ".wand", d.wand);
            cfg.set(k + ".owned", new ArrayList<>(d.owned));
            cfg.set(k + ".off", new ArrayList<>(d.off));
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
            YamlConfiguration pc = new YamlConfiguration();
            pc.set("blocks", new ArrayList<>(placed));
            pc.save(placedFile);
        } catch (IOException e) {
            getLogger().warning("Impossible de sauvegarder les données : " + e.getMessage());
        }
    }

    private Data data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new Data());
    }

    private boolean active(Data d, Node n) {
        return d.owned.contains(n.id) && !d.off.contains(n.id);
    }

    // ====== OUTILS ======
    private static Component c(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name));
        if (lore != null) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(c(s));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack createWand(String serial) {
        ItemStack it = item(Material.BLAZE_ROD, "§6Sceptre de compétences", List.of(
                "§7Clic droit : Dash (" + DASH_MAX_USES + " utilisations toutes les " + DASH_WINDOW_SECONDS + "s)",
                "§8Lié à ton compte - non craftable."));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isWand(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /** Un sceptre n'est valable que s'il porte le numéro de série enregistré pour ce joueur. */
    private boolean isValidWand(Player p, ItemStack it) {
        if (!isWand(it)) return false;
        String s = it.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
        return s != null && s.equals(data(p).wand);
    }

    private boolean hasValidWand(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isValidWand(p, it)) return true;
        return isValidWand(p, p.getItemOnCursor());
    }

    private void giveWand(Player p) {
        Data d = data(p);
        if (!d.owned.contains(N_DASH.id)) {
            p.sendMessage(c("§cLe sceptre se débloque avec la compétence §eDash §c(dernier palier de la Voie de la Vitesse)."));
            return;
        }
        if (hasValidWand(p)) {
            p.sendMessage(c("§eTu as déjà ton sceptre."));
            return;
        }
        String serial = UUID.randomUUID().toString();
        if (!p.getInventory().addItem(createWand(serial)).isEmpty()) {
            p.sendMessage(c("§cTon inventaire est plein."));
            return;
        }
        d.wand = serial; // les anciens exemplaires deviennent inutilisables
        p.sendMessage(c("§6Tu as reçu ton Sceptre de compétences."));
    }

    private void removeWands(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isWand(contents[i])) p.getInventory().setItem(i, null);
        }
    }

    // ====== EFFETS PERMANENTS ======
    private void applyEffects(Player p) {
        Data d = data(p);
        Map<PotionEffectType, Integer> want = new HashMap<>();
        boolean wantHealth = false;
        for (Node n : NODES) {
            if (!active(d, n)) continue;
            for (Map.Entry<PotionEffectType, Integer> e : n.effects.entrySet())
                want.merge(e.getKey(), e.getValue(), Math::max);
            if (n.health) wantHealth = true;
        }

        for (PotionEffectType t : MANAGED) {
            Integer amp = want.get(t);
            PotionEffect ex = p.getPotionEffect(t);
            boolean ours = ex != null && ex.isInfinite() && ex.isAmbient(); // nos effets : infinis + "ambient"
            if (amp == null) {
                if (ours) p.removePotionEffect(t);
                continue;
            }
            if (ours && ex.getAmplifier() == amp) continue;
            if (ours) p.removePotionEffect(t);
            else if (ex != null && ex.getAmplifier() >= amp) continue; // une potion plus forte est déjà active
            p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, amp, true, false, true));
        }

        // Cœurs bonus
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst != null) {
            AttributeModifier current = null;
            for (AttributeModifier m : inst.getModifiers()) {
                if (healthKey.equals(m.getKey())) { current = m; break; }
            }
            if (wantHealth && current == null) {
                inst.addTransientModifier(new AttributeModifier(healthKey, BONUS_HEALTH,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                p.setHealth(Math.min(inst.getValue(), p.getHealth() + BONUS_HEALTH));
            } else if (!wantHealth && current != null) {
                inst.removeModifier(current);
            }
        }
    }

    // ====== MENU ======
    private void openMenu(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 45, c("§8Arbre de compétences"));
        fill(m.inv, p);
        p.openInventory(m.inv);
    }

    private void fill(Inventory inv, Player p) {
        Data d = data(p);
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);

        for (Path pa : Path.values()) inv.setItem(pa.slot, pathItem(pa, d));
        for (Node n : NODES) inv.setItem(n.slot, nodeItem(n, d));

        if (d.owned.contains(N_DASH.id)) {
            inv.setItem(36, item(Material.BLAZE_ROD, "§6Recevoir le sceptre", List.of(
                    "§7Sert à utiliser le Dash (clic droit).",
                    "§7Un seul sceptre valide par joueur.")));
        } else {
            inv.setItem(36, item(Material.BARRIER, "§8Sceptre verrouillé", List.of(
                    "§cDébloque le Dash (Voie de la Vitesse,",
                    "§cdernier palier) pour l'obtenir.")));
        }
        inv.setItem(40, item(Material.NETHER_STAR, "§bPoints : §f" + d.points, List.of(
                "§7Comment gagner des points :",
                "§f- Joueur tué : §e+" + POINTS_PLAYER_KILL + " §7(tués : " + d.playerKills + ")",
                "§f- Monstres : §e+" + POINTS_MOBS + " §7tous les " + MOBS_PER_REWARD
                        + " (" + (d.kills % MOBS_PER_REWARD) + "/" + MOBS_PER_REWARD + ")",
                "§f- Cultures mûres : §e+" + POINTS_CROPS + " §7tous les " + CROPS_PER_REWARD
                        + " (" + (d.crops % CROPS_PER_REWARD) + "/" + CROPS_PER_REWARD + ")",
                "§f- Diamant/Émeraude/Netherite : §e+" + POINTS_ORES + " §7tous les " + ORES_PER_REWARD
                        + " (" + (d.ores % ORES_PER_REWARD) + "/" + ORES_PER_REWARD + ")")));
        inv.setItem(44, item(Material.TNT, "§cRéinitialiser ma progression", List.of(
                "§7Te permet de changer de voie.",
                "§cTu perds la moitié des points dépensés.",
                "§eClique deux fois pour confirmer.")));
    }

    private ItemStack pathItem(Path pa, Data d) {
        List<String> lore = new ArrayList<>();
        lore.add("§7" + pa.desc);
        lore.add("");
        String name;
        if (pa.id.equals(d.path)) {
            name = "§a" + pa.label + " §7(choisie)";
            lore.add("§aC'est ta voie actuelle.");
        } else if (d.path == null) {
            name = "§e" + pa.label;
            lore.add("§aClic pour choisir cette voie");
            lore.add("§cUn seul choix possible !");
        } else {
            name = "§8" + pa.label;
            lore.add("§cTu as déjà choisi une autre voie.");
        }
        return item(pa.icon, name, lore);
    }

    private ItemStack nodeItem(Node n, Data d) {
        List<String> lore = new ArrayList<>();
        lore.add("§7" + n.desc);
        lore.add("");
        boolean chosen = d.path != null;
        boolean mine = n.path.id.equals(d.path);

        if (chosen && !mine) {
            lore.add("§cAutre voie.");
            return item(Material.BARRIER, "§8" + n.name, lore);
        }
        if (!chosen) {
            lore.add(n.cost == 0 ? "§fCoût : §agratuit" : "§fCoût : §e" + n.cost + " points");
            lore.add("§7Choisis d'abord cette voie.");
            return item(n.icon, "§7" + n.name, lore);
        }
        if (d.owned.contains(n.id)) {
            boolean off = d.off.contains(n.id);
            lore.add(off ? "§cDésactivée" : "§aActive");
            lore.add("§eClic pour " + (off ? "activer" : "désactiver"));
            return item(n.icon, (off ? "§c[OFF] " : "§a[ON] ") + n.name, lore);
        }
        Node prev = n.tier > 1 ? nodeOf(n.path, n.tier - 1) : null;
        if (prev != null && !d.owned.contains(prev.id)) {
            lore.add("§cDébloque d'abord : §f" + prev.name);
            return item(Material.BARRIER, "§8" + n.name, lore);
        }
        lore.add("§fCoût : §e" + n.cost + " points");
        lore.add(d.points >= n.cost ? "§aClic pour acheter" : "§cPas assez de points");
        return item(n.icon, "§e" + n.name, lore);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getSlot();
        Path pa = Path.bySlot(slot);
        Node n = nodeBySlot(slot);
        if (pa != null) choosePath(p, pa);
        else if (n != null) clickNode(p, n);
        else if (slot == 36) giveWand(p);
        else if (slot == 44) tryReset(p);

        applyEffects(p);
        fill(e.getView().getTopInventory(), p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private void choosePath(Player p, Path pa) {
        Data d = data(p);
        if (d.path != null) {
            p.sendMessage(c(pa.id.equals(d.path)
                    ? "§eC'est déjà ta voie."
                    : "§cTu as déjà choisi une voie. Réinitialise ta progression pour changer."));
            return;
        }
        d.path = pa.id;
        for (Node n : NODES) {
            if (n.path == pa && n.cost == 0) { d.owned.add(n.id); d.off.remove(n.id); }
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        p.sendMessage(c("§aTu as choisi la §e" + pa.label + "§a !"));
    }

    private void clickNode(Player p, Node n) {
        Data d = data(p);
        if (d.path == null) { p.sendMessage(c("§cChoisis d'abord une voie (ligne du haut).")); return; }
        if (!n.path.id.equals(d.path)) {
            p.sendMessage(c("§cTu as choisi une autre voie. Réinitialise ta progression pour changer."));
            return;
        }
        if (d.owned.contains(n.id)) {
            if (d.off.remove(n.id)) p.sendMessage(c("§a" + n.name + " activée."));
            else { d.off.add(n.id); p.sendMessage(c("§c" + n.name + " désactivée.")); }
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            return;
        }
        Node prev = n.tier > 1 ? nodeOf(n.path, n.tier - 1) : null;
        if (prev != null && !d.owned.contains(prev.id)) {
            p.sendMessage(c("§cDébloque d'abord : " + prev.name));
            return;
        }
        if (d.points < n.cost) {
            p.sendMessage(c("§cIl te faut " + n.cost + " points (tu en as " + d.points + ")."));
            return;
        }
        d.points -= n.cost;
        d.owned.add(n.id);
        d.off.remove(n.id);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        p.sendMessage(c("§aCompétence débloquée : §e" + n.name));
    }

    private void tryReset(Player p) {
        Data d = data(p);
        if (d.path == null && d.owned.isEmpty()) { p.sendMessage(c("§eTu n'as rien à réinitialiser.")); return; }
        long now = System.currentTimeMillis();
        Long t = pendingReset.get(p.getUniqueId());
        if (t == null || now - t > 10000) {
            pendingReset.put(p.getUniqueId(), now);
            p.sendMessage(c("§cClique encore sur le TNT dans les 10 secondes pour confirmer (tu perds la moitié des points dépensés)."));
            return;
        }
        pendingReset.remove(p.getUniqueId());
        int spent = 0;
        for (Node n : NODES) if (d.owned.contains(n.id)) spent += n.cost;
        int refund = spent / 2;
        d.points += refund;
        d.owned.clear();
        d.off.clear();
        d.path = null;
        d.wand = null;
        removeWands(p);
        p.sendMessage(c("§aProgression réinitialisée. §7Points rendus : §e" + refund + " §7(sur " + spent + " dépensés)."));
    }

    // ====== GAIN DE POINTS ======
    private void addPoints(Player p, Data d, int n, String reason) {
        d.points += n;
        p.sendMessage(c("§b+" + n + " points de compétence ! §7(" + reason + ") Total : " + d.points + " - /skills"));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || k.equals(e.getEntity())) return;
        Data d = data(k);
        if (e.getEntity() instanceof Player) {
            d.playerKills++;
            addPoints(k, d, POINTS_PLAYER_KILL, "joueur tué");
        } else if (e.getEntity() instanceof Enemy) {
            d.kills++;
            if (d.kills % MOBS_PER_REWARD == 0) addPoints(k, d, POINTS_MOBS, MOBS_PER_REWARD + " monstres tués");
        }
    }

    /** Les minerais posés par un joueur sont mémorisés : les recasser ne donne rien. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (ORES.contains(e.getBlockPlaced().getType())) placed.add(key(e.getBlockPlaced()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material m = b.getType();
        Player p = e.getPlayer();
        if (ORES.contains(m)) {
            boolean wasPlaced = placed.remove(key(b));
            if (wasPlaced || p.getGameMode() == GameMode.CREATIVE) return;
            Data d = data(p);
            d.ores++;
            if (d.ores % ORES_PER_REWARD == 0) addPoints(p, d, POINTS_ORES, ORES_PER_REWARD + " minerais minés");
        } else if (CROPS.contains(m)) {
            if (p.getGameMode() == GameMode.CREATIVE) return;
            if (b.getBlockData() instanceof Ageable a && a.getAge() == a.getMaximumAge()) {
                Data d = data(p);
                d.crops++;
                if (d.crops % CROPS_PER_REWARD == 0) addPoints(p, d, POINTS_CROPS, CROPS_PER_REWARD + " cultures récoltées");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        dashUses.remove(e.getPlayer().getUniqueId());
        pendingReset.remove(e.getPlayer().getUniqueId());
        save();
    }

    // ====== TOUCHER DU WITHER ======
    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        if (!active(data(p), N_WITHER)) return;
        if (Math.random() * 100 < WITHER_CHANCE_PERCENT) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, WITHER_SECONDS * 20, 1));
        }
    }

    // ====== DASH (sceptre) ======
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isWand(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();

        if (!isValidWand(p, e.getItem())) {
            p.sendActionBar(c("§cCe sceptre n'est pas valide pour toi."));
            return;
        }
        if (!active(data(p), N_DASH)) {
            p.sendActionBar(c("§cDash non débloqué ou désactivé (/skills)"));
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> uses = dashUses.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        while (!uses.isEmpty() && now - uses.peekFirst() > DASH_WINDOW_SECONDS * 1000L) uses.pollFirst();
        if (!uses.isEmpty() && now - uses.peekLast() < DASH_DELAY_MS) return; // anti double-clic
        if (uses.size() >= DASH_MAX_USES) {
            long wait = (uses.peekFirst() + DASH_WINDOW_SECONDS * 1000L - now) / 1000 + 1;
            p.sendActionBar(c("§cDash rechargé dans " + wait + "s"));
            return;
        }
        uses.addLast(now);
        Vector v = p.getLocation().getDirection().multiply(1.8);
        v.setY(Math.max(v.getY(), 0) + 0.25);
        p.setVelocity(v);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 15);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.2f);
        p.sendActionBar(c("§aDash ! §7(" + (DASH_MAX_USES - uses.size()) + "/" + DASH_MAX_USES + " restants)"));
    }

    // ====== SCEPTRE NON CRAFTABLE ======
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack st : e.getInventory().getMatrix()) {
            if (isWand(st)) { e.getInventory().setResult(null); return; }
        }
    }

    @EventHandler
    public void onFuel(FurnaceBurnEvent e) {
        if (isWand(e.getFuel())) e.setCancelled(true);
    }

    // ====== COMMANDES ======
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("reset"))) {
            if (!sender.hasPermission("skills.admin")) {
                sender.sendMessage(c("§cTu n'as pas la permission."));
                return true;
            }
            if (args.length < 2) { sender.sendMessage(c("§c/skills give <joueur> <nombre> | /skills reset <joueur>")); return true; }
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t == null) { sender.sendMessage(c("§cJoueur introuvable.")); return true; }
            Data d = data(t);
            if (args[0].equalsIgnoreCase("give")) {
                int n;
                try { n = Integer.parseInt(args.length > 2 ? args[2] : "1"); }
                catch (NumberFormatException ex) { sender.sendMessage(c("§cNombre invalide.")); return true; }
                d.points += n;
                sender.sendMessage(c("§a" + n + " point(s) donné(s) à " + t.getName() + "."));
                t.sendMessage(c("§b+" + n + " point(s) de compétence !"));
            } else {
                // Reset admin : remboursement complet, sans pénalité
                int spent = 0;
                for (Node n : NODES) if (d.owned.contains(n.id)) spent += n.cost;
                d.points += spent;
                d.owned.clear();
                d.off.clear();
                d.path = null;
                d.wand = null;
                removeWands(t);
                sender.sendMessage(c("§aProgression de " + t.getName() + " réinitialisée (" + spent + " points rendus)."));
            }
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(c("§cCommande réservée aux joueurs."));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "wand" -> giveWand(p);
            case "points" -> {
                Data d = data(p);
                p.sendMessage(c("§bPoints : §f" + d.points + " §7| Joueurs : §f" + d.playerKills
                        + " §7| Monstres : §f" + (d.kills % MOBS_PER_REWARD) + "/" + MOBS_PER_REWARD
                        + " §7| Cultures : §f" + (d.crops % CROPS_PER_REWARD) + "/" + CROPS_PER_REWARD
                        + " §7| Minerais : §f" + (d.ores % ORES_PER_REWARD) + "/" + ORES_PER_REWARD));
            }
            default -> openMenu(p);
        }
        return true;
    }
}
