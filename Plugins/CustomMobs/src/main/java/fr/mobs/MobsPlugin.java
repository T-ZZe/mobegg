package fr.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MobsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // ====== LES 9 MOBS ======
    // Paramètres : vie, dégâts corps à corps, vitesse, armure, taille, dégâts de l'attaque spéciale, butin (diamants)
    enum Type {
        FLAME("flame", "Gardien des Flammes", "§6", EntityType.HUSK, Material.HUSK_SPAWN_EGG,
                400, 14, 0.27, 10, 1.2, 16, 3,
                "Nova de feu : une onde de flammes explose autour de lui."),
        STORM("storm", "Chevalier de la Tempête", "§b", EntityType.VINDICATOR, Material.VINDICATOR_SPAWN_EGG,
                350, 16, 0.29, 12, 1.15, 18, 4,
                "Charge éclair : il fonce sur toi en tranchant tout."),
        SHADOW("shadow", "Spectre des Ombres", "§5", EntityType.WITHER_SKELETON, Material.WITHER_SKELETON_SPAWN_EGG,
                300, 18, 0.31, 10, 1.1, 20, 5,
                "Frappe d'ombre : il se téléporte dans ton dos et frappe."),
        FROST("frost", "Seigneur du Givre", "§3", EntityType.STRAY, Material.STRAY_SPAWN_EGG,
                280, 10, 0.27, 8, 1.15, 14, 5,
                "Souffle glacial : un cône de givre qui te gèle sur place."),
        VENOM("venom", "Reine Venimeuse", "§2", EntityType.SPIDER, Material.SPIDER_SPAWN_EGG,
                300, 14, 0.36, 6, 1.6, 5, 6,
                "Nuée toxique : un nuage de poison s'abat sur toi."),
        BERSERK("berserk", "Berserker Dévastateur", "§c", EntityType.RAVAGER, Material.RAVAGER_SPAWN_EGG,
                600, 22, 0.30, 12, 1.2, 22, 8,
                "Fracas sismique : il bondit et fracasse le sol."),
        ABYSS("abyss", "Seigneur des Abysses", "§9", EntityType.DROWNED, Material.DROWNED_SPAWN_EGG,
                450, 16, 0.28, 12, 1.25, 18, 7,
                "Vortex abyssal : il t'aspire puis t'écrase."),
        BRUTE("brute", "Brute Écarlate", "§4", EntityType.PIGLIN_BRUTE, Material.PIGLIN_BRUTE_SPAWN_EGG,
                500, 20, 0.32, 14, 1.3, 9, 9,
                "Tourbillon : une toupie de lames qui frappe 3 fois."),
        NECRO("necro", "Seigneur Nécromancien (BOSS)", "§8§l", EntityType.ZOMBIE, Material.ZOMBIE_SPAWN_EGG,
                1200, 24, 0.28, 20, 1.8, 12, 16,
                "Invocation : il appelle 4 sbires à la fois (8 maximum).");

        final String id, label, color, power;
        final EntityType entity;
        final Material egg;
        final double health, damage, speed, armor, scale, special;
        final int reward;

        Type(String id, String label, String color, EntityType entity, Material egg,
             double health, double damage, double speed, double armor, double scale,
             double special, int reward, String power) {
            this.id = id; this.label = label; this.color = color; this.entity = entity; this.egg = egg;
            this.health = health; this.damage = damage; this.speed = speed; this.armor = armor;
            this.scale = scale; this.special = special; this.reward = reward; this.power = power;
        }

        static Type byId(String id) {
            if (id == null) return null;
            for (Type t : values()) if (t.id.equalsIgnoreCase(id)) return t;
            return null;
        }
    }

    // ====== REGLAGES ======
    static final long COOLDOWN_MS = 8000;      // délai entre deux attaques spéciales
    static final long BOSS_COOLDOWN_MS = 12000; // pour le boss (invocation)
    static final int MAX_MINIONS = 8;           // sbires vivants en même temps par boss
    static final int MINIONS_PER_SUMMON = 4;    // sbires invoqués à chaque fois

    private NamespacedKey eggKey;
    private NamespacedKey mobKey;
    private NamespacedKey minionKey;
    private final Set<UUID> tracked = new HashSet<>();
    private final Map<UUID, Long> nextUse = new HashMap<>();
    private final Map<UUID, Set<UUID>> minions = new HashMap<>(); // boss -> sbires

    @Override
    public void onEnable() {
        eggKey = new NamespacedKey(this, "egg_type");
        mobKey = new NamespacedKey(this, "mob_type");
        minionKey = new NamespacedKey(this, "minion_of");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("mobegg").setExecutor(this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 10L);
    }

    private static Component c(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    // ====== OEUFS ======
    private ItemStack createEgg(Type t, int amount) {
        ItemStack it = new ItemStack(t.egg, amount);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(t.color + "Oeuf : " + t.label));
        meta.lore(List.of(c("§7" + t.power), c("§8Clic droit sur un bloc pour l'invoquer.")));
        meta.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, t.id);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(eggKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true); // pas d'apparition vanilla
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Type t = Type.byId(id);
        if (t == null) return;
        Location loc = e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        spawn(t, loc);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
    }

    // ====== APPARITION ======
    private void spawn(Type t, Location loc) {
        if (!(loc.getWorld().spawnEntity(loc, t.entity) instanceof LivingEntity le)) return;

        le.customName(c(t.color + t.label));
        le.setCustomNameVisible(true);
        le.setRemoveWhenFarAway(false);
        le.setCanPickupItems(false);
        le.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, t.id);

        setBase(le, Attribute.MAX_HEALTH, t.health);
        le.setHealth(t.health);
        setBase(le, Attribute.ATTACK_DAMAGE, t.damage);
        setBase(le, Attribute.MOVEMENT_SPEED, t.speed);
        setBase(le, Attribute.ARMOR, t.armor);
        setBase(le, Attribute.KNOCKBACK_RESISTANCE, 0.6);
        setBase(le, Attribute.SCALE, t.scale);
        setBase(le, Attribute.FOLLOW_RANGE, 40);

        if (le instanceof PiglinAbstract pa) pa.setImmuneToZombification(true);
        if (le instanceof Zombie z) z.setBaby(false);

        EntityEquipment eq = le.getEquipment();
        if (eq != null) {
            switch (t) {
                case FLAME -> {
                    eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    eq.setChestplate(new ItemStack(Material.GOLDEN_CHESTPLATE));
                    eq.setItemInMainHand(new ItemStack(Material.BLAZE_ROD));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, true, false));
                }
                case STORM -> {
                    eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                    eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                    eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                    eq.setItemInMainHand(new ItemStack(Material.IRON_AXE));
                }
                case SHADOW -> {
                    eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                    eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                    eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
                }
                case FROST -> {
                    eq.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
                    eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                }
                case ABYSS -> {
                    eq.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
                    eq.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                }
                case BRUTE -> eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                case NECRO -> {
                    eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET)); // évite de brûler au soleil
                    eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
                    eq.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
                    eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
                }
                default -> { }
            }
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
        }
        tracked.add(le.getUniqueId());
        loc.getWorld().spawnParticle(Particle.EXPLOSION, loc.clone().add(0, 1, 0), 3);
        loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
    }

    private void setBase(LivingEntity le, Attribute a, double v) {
        AttributeInstance inst = le.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    private Type typeOf(Entity en) {
        return Type.byId(en.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING));
    }

    /** Après un redémarrage, on ré-enregistre le mob dès qu'il cible un joueur. */
    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (typeOf(e.getEntity()) != null) tracked.add(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(minionKey, PersistentDataType.STRING)) {
            e.getDrops().clear();   // les sbires ne donnent rien
            e.setDroppedExp(0);
            return;
        }
        Type t = typeOf(e.getEntity());
        if (t == null) return;
        Set<UUID> mine = minions.remove(e.getEntity().getUniqueId());
        if (mine != null) {         // le boss meurt : ses sbires disparaissent
            for (UUID id : mine) {
                Entity m = Bukkit.getEntity(id);
                if (m != null && m.isValid()) {
                    m.getWorld().spawnParticle(Particle.SMOKE, m.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
                    m.remove();
                }
            }
        }
        tracked.remove(e.getEntity().getUniqueId());
        nextUse.remove(e.getEntity().getUniqueId());
        e.getDrops().clear();
        e.getDrops().add(new ItemStack(Material.DIAMOND, t.reward));
        e.setDroppedExp(t.reward * 100);
        Location l = e.getEntity().getLocation();
        l.getWorld().spawnParticle(Particle.EXPLOSION, l.clone().add(0, 1, 0), 3);
    }

    // ====== BOUCLE DES ATTAQUES SPECIALES ======
    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<UUID> it = tracked.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity en = Bukkit.getEntity(id);
            if (!(en instanceof Mob mob) || !mob.isValid()) { it.remove(); nextUse.remove(id); continue; }
            Type t = typeOf(mob);
            if (t == null) continue;
            if (!(mob.getTarget() instanceof Player target)) continue;
            if (target.isDead() || !target.getWorld().equals(mob.getWorld()) || !hittable(target)) continue;
            Long nu = nextUse.get(id);
            if (nu != null && nu > now) continue;

            double dist = mob.getLocation().distance(target.getLocation());
            double dmg = t.special;
            boolean fired = switch (t) {
                case FLAME -> dist <= 7 && flameNova(mob, dmg);
                case STORM -> dist >= 4 && dist <= 14 && stormCharge(mob, target, dmg);
                case SHADOW -> dist >= 3 && dist <= 16 && shadowStrike(mob, target, dmg);
                case FROST -> dist <= 12 && frostBreath(mob, target, dmg);
                case VENOM -> dist >= 3 && dist <= 14 && toxicCloud(mob, target, dmg);
                case BERSERK -> dist >= 3 && dist <= 12 && quake(mob, target, dmg);
                case ABYSS -> dist >= 3 && dist <= 11 && vortex(mob, dmg);
                case BRUTE -> dist <= 5 && spin(mob, dmg);
                case NECRO -> dist <= 25 && summon(mob, target, dmg);
            };
            if (fired) nextUse.put(id, now + (t == Type.NECRO ? BOSS_COOLDOWN_MS : COOLDOWN_MS));
        }
    }

    // ====== OUTILS D'ATTAQUE ======
    private boolean hittable(Player p) {
        return p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR;
    }

    private void hurt(Player p, double dmg, Entity source) {
        if (source != null && source.isValid()) p.damage(dmg, source); else p.damage(dmg);
    }

    private void ring(Location center, double radius, Particle p, int points) {
        World w = center.getWorld();
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            w.spawnParticle(p, center.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0, 0, 0, 0);
        }
    }

    // ---- 1. NOVA DE FEU ----
    private boolean flameNova(Mob mob, double dmg) {
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_BLAZE_AMBIENT, 2f, 0.6f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!mob.isValid()) { cancel(); return; }
                Location c = mob.getLocation();
                World w = c.getWorld();
                if (t < 20) {
                    ring(c, 6, Particle.SMOKE, 30);
                    ring(c, 6 * (t / 20.0), Particle.FLAME, 20);
                } else {
                    for (double r = 1; r <= 6; r += 1) ring(c, r, Particle.FLAME, (int) (r * 10));
                    w.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 1, 0), 4, 1.5, 0.5, 1.5, 0);
                    w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.8f);
                    for (Player p : w.getPlayers()) {
                        if (!hittable(p) || p.getLocation().distance(c) > 6) continue;
                        hurt(p, dmg, mob);
                        p.setFireTicks(120);
                        Vector kb = p.getLocation().toVector().subtract(c.toVector());
                        if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(1.1).setY(0.5));
                    }
                    cancel();
                    return;
                }
                t += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
        return true;
    }

    // ---- 2. CHARGE ECLAIR ----
    private boolean stormCharge(Mob mob, Player target, double dmg) {
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.8f);
        new BukkitRunnable() {
            int t = 0;
            final Set<UUID> hit = new HashSet<>();
            Vector dir;
            @Override public void run() {
                if (!mob.isValid()) { cancel(); return; }
                Location c = mob.getLocation().add(0, 1, 0);
                World w = c.getWorld();
                if (t < 12) {
                    w.spawnParticle(Particle.ELECTRIC_SPARK, c, 12, 0.5, 0.8, 0.5, 0.05);
                } else if (t == 12) {
                    dir = target.getLocation().toVector().subtract(mob.getLocation().toVector());
                    dir.setY(0);
                    if (dir.lengthSquared() < 0.01) { cancel(); return; }
                    dir.normalize();
                    mob.setVelocity(dir.clone().multiply(1.9).setY(0.2));
                    w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.7f);
                } else {
                    w.spawnParticle(Particle.SWEEP_ATTACK, c, 2, 0.4, 0.3, 0.4, 0);
                    w.spawnParticle(Particle.CRIT, c, 8, 0.4, 0.5, 0.4, 0.2);
                    for (Player p : w.getPlayers()) {
                        if (hit.contains(p.getUniqueId()) || !hittable(p)) continue;
                        if (p.getLocation().add(0, 1, 0).distance(c) > 2.5) continue;
                        hit.add(p.getUniqueId());
                        hurt(p, dmg, mob);
                        p.setVelocity(dir.clone().multiply(1.2).setY(0.4));
                        w.spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.1);
                        w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 1f);
                    }
                    if (t > 22) { cancel(); return; }
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
        return true;
    }

    // ---- 3. FRAPPE D'OMBRE ----
    private boolean shadowStrike(Mob mob, Player target, double dmg) {
        Location from = mob.getLocation().add(0, 1, 0);
        from.getWorld().spawnParticle(Particle.SMOKE, from, 40, 0.4, 0.8, 0.4, 0.05);
        from.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, from, 25, 0.4, 0.8, 0.4, 0.05);
        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.6f);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!mob.isValid() || !target.isOnline() || target.isDead()) return;
            Location tl = target.getLocation();
            Location behind = tl.clone().add(tl.getDirection().setY(0).normalize().multiply(-1.6));
            behind.setDirection(tl.toVector().subtract(behind.toVector()));
            mob.teleport(behind);
            World w = behind.getWorld();
            w.spawnParticle(Particle.SMOKE, behind.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, behind.clone().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0.05);
            w.playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.4f);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!mob.isValid() || !target.isOnline() || target.isDead()) return;
                Location eye = target.getLocation().add(0, 1, 0);
                if (eye.distance(mob.getLocation()) > 3.8) return;
                World w2 = eye.getWorld();
                w2.spawnParticle(Particle.SWEEP_ATTACK, eye, 3, 0.5, 0.3, 0.5, 0);
                w2.spawnParticle(Particle.DAMAGE_INDICATOR, eye, 12, 0.3, 0.5, 0.3, 0.1);
                w2.spawnParticle(Particle.CRIT, eye, 20, 0.4, 0.5, 0.4, 0.3);
                w2.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);
                hurt(target, dmg, mob);
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
            }, 8L);
        }, 12L);
        return true;
    }

    // ---- 4. SOUFFLE GLACIAL (cône) ----
    private boolean frostBreath(Mob mob, Player target, double dmg) {
        Vector dir = target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector());
        if (dir.lengthSquared() < 0.01) return false;
        dir.normalize();
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 2f, 0.7f);
        new BukkitRunnable() {
            int t = 0;
            final Set<UUID> hit = new HashSet<>();
            @Override public void run() {
                if (!mob.isValid()) { cancel(); return; }
                Location s = mob.getEyeLocation();
                World w = s.getWorld();
                if (t < 10) {
                    w.spawnParticle(Particle.SNOWFLAKE, s, 8, 0.4, 0.4, 0.4, 0.02);
                } else {
                    for (double k = 1; k <= 10; k += 0.8) {
                        Location pt = s.clone().add(dir.clone().multiply(k));
                        double spread = k * 0.12;
                        w.spawnParticle(Particle.SNOWFLAKE, pt, 4, spread, spread, spread, 0.01);
                        w.spawnParticle(Particle.CLOUD, pt, 1, spread, spread, spread, 0.01);
                    }
                    for (Player p : w.getPlayers()) {
                        if (hit.contains(p.getUniqueId()) || !hittable(p)) continue;
                        Vector to = p.getEyeLocation().toVector().subtract(s.toVector());
                        double d = to.length();
                        if (d > 10 || d < 0.1) continue;
                        if (to.normalize().dot(dir) < 0.88) continue;
                        hit.add(p.getUniqueId());
                        hurt(p, dmg, mob);
                        p.setFreezeTicks(p.getMaxFreezeTicks());
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2));
                    }
                    if (t >= 24) { cancel(); return; }
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
        return true;
    }

    // ---- 5. NUEE TOXIQUE (zone de poison) ----
    private boolean toxicCloud(Mob mob, Player target, double dmg) {
        Location center = target.getLocation().clone();
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 2f, 0.5f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                World w = center.getWorld();
                if (t < 20) {
                    ring(center, 4, Particle.WITCH, 24);
                } else {
                    if (t % 2 == 0) {
                        for (int i = 0; i < 10; i++) {
                            w.spawnParticle(Particle.WITCH, center.clone().add(
                                    Math.random() * 8 - 4, Math.random() * 1.5, Math.random() * 8 - 4), 2, 0.1, 0.1, 0.1, 0);
                        }
                    }
                    if (t % 10 == 0) {
                        for (Player p : w.getPlayers()) {
                            if (!hittable(p) || p.getLocation().distance(center) > 4.2) continue;
                            hurt(p, dmg, mob);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1));
                        }
                    }
                    if (t >= 100) { cancel(); return; }
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
        return true;
    }

    // ---- 6. FRACAS SISMIQUE (bond + choc) ----
    private boolean quake(Mob mob, Player target, double dmg) {
        Vector d = target.getLocation().toVector().subtract(mob.getLocation().toVector());
        d.setY(0);
        if (d.lengthSquared() > 0.01) d.normalize();
        mob.setVelocity(d.multiply(0.8).setY(1.1));
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 2f, 0.8f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!mob.isValid()) { cancel(); return; }
                Location c = mob.getLocation();
                World w = c.getWorld();
                if (t < 18) {
                    if (t % 3 == 0) w.spawnParticle(Particle.ANGRY_VILLAGER, c.clone().add(0, 2, 0), 3, 0.4, 0.3, 0.4, 0);
                } else {
                    for (double r = 1; r <= 5; r += 1) ring(c, r, Particle.CRIT, (int) (r * 8));
                    w.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 0.5, 0), 5, 2, 0.3, 2, 0);
                    w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
                    for (Player p : w.getPlayers()) {
                        if (!hittable(p) || p.getLocation().distance(c) > 5) continue;
                        hurt(p, dmg, mob);
                        Vector kb = p.getLocation().toVector().subtract(c.toVector());
                        if (kb.lengthSquared() < 0.01) kb = new Vector(0, 0, 0); else kb.normalize();
                        p.setVelocity(kb.multiply(0.8).setY(0.9));
                    }
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
        return true;
    }

    // ---- 7. VORTEX ABYSSAL (aspire puis écrase) ----
    private boolean vortex(Mob mob, double dmg) {
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 2f, 0.5f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!mob.isValid()) { cancel(); return; }
                Location c = mob.getLocation();
                World w = c.getWorld();
                if (t < 24) {
                    for (int i = 0; i < 3; i++) {
                        double a = t * 0.5 + i * 2.09;
                        double r = 8 - t * 0.3;
                        Location l = c.clone().add(Math.cos(a) * r, 0.3 + t * 0.05, Math.sin(a) * r);
                        w.spawnParticle(Particle.SOUL, l, 2, 0.05, 0.05, 0.05, 0);
                        w.spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0);
                    }
                    for (Player p : w.getPlayers()) {
                        if (!hittable(p) || p.getLocation().distance(c) > 10) continue;
                        Vector pull = c.toVector().subtract(p.getLocation().toVector());
                        if (pull.length() > 1.5) p.setVelocity(pull.normalize().multiply(0.35));
                    }
                } else {
                    ring(c, 3, Particle.CLOUD, 24);
                    w.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 1, 0), 3, 1, 0.3, 1, 0);
                    w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
                    for (Player p : w.getPlayers()) {
                        if (!hittable(p) || p.getLocation().distance(c) > 3.8) continue;
                        hurt(p, dmg, mob);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
                    }
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
        return true;
    }

    // ---- 8. TOURBILLON (3 coups) ----
    private boolean spin(Mob mob, double dmg) {
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PIGLIN_BRUTE_ANGRY, 2f, 0.6f);
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!mob.isValid()) { cancel(); return; }
                Location c = mob.getLocation();
                World w = c.getWorld();
                for (int k = 0; k < 6; k++) {
                    double a = n * 0.8 + k * Math.PI / 3;
                    Location l = c.clone().add(Math.cos(a) * 2.5, 1, Math.sin(a) * 2.5);
                    w.spawnParticle(n < 6 ? Particle.CRIT : Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0);
                }
                if (n == 6 || n == 10 || n == 14) {
                    w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.8f);
                    for (Player p : w.getPlayers()) {
                        if (!hittable(p) || p.getLocation().distance(c) > 3.8) continue;
                        hurt(p, dmg, mob);
                        Vector kb = p.getLocation().toVector().subtract(c.toVector());
                        if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(0.5).setY(0.25));
                    }
                }
                if (n >= 14) { cancel(); return; }
                n++;
            }
        }.runTaskTimer(this, 0L, 2L);
        return true;
    }

    // ---- 9. INVOCATION DE SBIRES (boss) ----
    private boolean summon(Mob boss, Player target, double dmg) {
        Set<UUID> set = minions.computeIfAbsent(boss.getUniqueId(), k -> new HashSet<>());
        set.removeIf(id -> {
            Entity m = Bukkit.getEntity(id);
            return m == null || !m.isValid();
        });
        if (set.size() >= MAX_MINIONS) return false; // assez de sbires, on garde l'attaque pour plus tard

        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2f, 0.6f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!boss.isValid()) { cancel(); return; }
                Location c = boss.getLocation();
                World w = c.getWorld();
                if (t < 30) {                                    // incantation
                    ring(c, 4, Particle.SOUL_FIRE_FLAME, 24);
                    w.spawnParticle(Particle.WITCH, c.clone().add(0, 2.5, 0), 6, 0.5, 0.3, 0.5, 0);
                } else {                                         // les sbires surgissent en cercle
                    int n = Math.min(MINIONS_PER_SUMMON, MAX_MINIONS - set.size());
                    for (int i = 0; i < n; i++) {
                        double a = 2 * Math.PI * i / Math.max(1, n);
                        Location l = c.clone().add(Math.cos(a) * 3, 0, Math.sin(a) * 3);
                        spawnMinion(boss, l, i, target, dmg, set);
                    }
                    w.playSound(c, Sound.ENTITY_EVOKER_CAST_SPELL, 2f, 0.7f);
                    w.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 1, 0), 2);
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
        return true;
    }

    private void spawnMinion(Mob boss, Location l, int index, Player target, double dmg, Set<UUID> set) {
        World w = l.getWorld();
        if (!(w.spawnEntity(l, index % 2 == 0 ? EntityType.ZOMBIE : EntityType.SKELETON) instanceof LivingEntity m)) return;
        m.customName(c("§8Sbire du Nécromancien"));
        m.setCustomNameVisible(true);
        m.setRemoveWhenFarAway(true);
        m.setCanPickupItems(false);
        m.getPersistentDataContainer().set(minionKey, PersistentDataType.STRING, boss.getUniqueId().toString());
        if (m instanceof Zombie z) z.setBaby(false);

        setBase(m, Attribute.MAX_HEALTH, 60);
        m.setHealth(60);
        setBase(m, Attribute.ATTACK_DAMAGE, dmg);
        setBase(m, Attribute.MOVEMENT_SPEED, 0.3);
        setBase(m, Attribute.ARMOR, 6);

        EntityEquipment eq = m.getEquipment();
        if (eq != null) {
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));   // évite de brûler au soleil
            eq.setHelmetDropChance(0f);
            if (m instanceof Zombie) {
                eq.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                eq.setItemInMainHandDropChance(0f);
            }
        }
        if (m instanceof Mob mm) mm.setTarget(target);
        set.add(m.getUniqueId());
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 25, 0.3, 0.6, 0.3, 0.05);
        w.spawnParticle(Particle.SMOKE, l.clone().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.02);
    }

    // ====== INTERFACE (MENU DES OEUFS) ======
    static class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private void openMenu(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 27, c("§8Oeufs de mobs custom"));

        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(c(" "));
        pane.setItemMeta(pm);
        for (int i = 0; i < m.inv.getSize(); i++) m.inv.setItem(i, pane);

        Type[] types = Type.values();
        for (int i = 0; i < types.length; i++) {
            Type t = types[i];
            ItemStack egg = new ItemStack(t.egg);
            ItemMeta meta = egg.getItemMeta();
            meta.displayName(c(t.color + t.label));
            meta.lore(List.of(
                    c("§7" + t.power),
                    c(""),
                    c("§fVie : §c" + (int) t.health),
                    c("§fDégâts : §c" + (int) t.damage + " §7(spécial : §c" + (int) t.special + "§7)"),
                    c("§fArmure : §b" + (int) t.armor),
                    c(""),
                    c("§aClic : §f1 oeuf"),
                    c("§aShift + clic : §f16 oeufs")));
            egg.setItemMeta(meta);
            m.inv.setItem(9 + i, egg); // ligne du milieu : slots 9 à 17
        }
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.isOp()) { p.closeInventory(); return; }
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int idx = e.getSlot() - 9;
        Type[] types = Type.values();
        if (idx < 0 || idx >= types.length) return;
        int n = e.isShiftClick() ? 16 : 1;
        p.getInventory().addItem(createEgg(types[idx], n));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ====== COMMANDES ======
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(c("§cCette commande est réservée aux opérateurs."));
            return true;
        }
        if (args.length == 0 && sender instanceof Player menuPlayer) {
            openMenu(menuPlayer);
            return true;
        }
        if (args.length >= 1 && Type.byId(args[0]) != null) {
            if (!(sender instanceof Player self)) { sender.sendMessage(c("§cUtilise /mobegg give <joueur> <type>.")); return true; }
            int n = 1;
            if (args.length >= 2) {
                try { n = Math.max(1, Math.min(64, Integer.parseInt(args[1]))); }
                catch (NumberFormatException ex) { sender.sendMessage(c("§cNombre invalide.")); return true; }
            }
            Type t = Type.byId(args[0]);
            self.getInventory().addItem(createEgg(t, n));
            self.sendMessage(c("§a" + n + " oeuf(s) " + t.label + " ajouté(s) à ton inventaire."));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            for (Type t : Type.values()) sender.sendMessage(c(t.color + t.id + " §7- " + t.label + " : " + t.power));
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            Type t = Type.byId(args[2]);
            if (target == null) { sender.sendMessage(c("§cJoueur introuvable.")); return true; }
            if (t == null) { sender.sendMessage(c("§cType inconnu. Fais /mobegg list")); return true; }
            int n = 1;
            if (args.length >= 4) {
                try { n = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); }
                catch (NumberFormatException ex) { sender.sendMessage(c("§cNombre invalide.")); return true; }
            }
            target.getInventory().addItem(createEgg(t, n));
            sender.sendMessage(c("§a" + n + " oeuf(s) " + t.label + " donné(s) à " + target.getName() + "."));
            return true;
        }
        sender.sendMessage(c("§e/mobegg §7(menu) | §e/mobegg <type> [nombre] §7| §e/mobegg give <joueur> <type> [nombre] §7| §e/mobegg list"));
        return true;
    }
}
