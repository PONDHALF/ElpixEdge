package net.elpixedge.core.utils;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

public class Keys {
    public static NamespacedKey hp, def, dmg, magicDmg, critChance, critDamage, lvl, skill, manaCost, ranged, mining, gathering, manaRegen;
    public static NamespacedKey customMobId, lastDamager, guiAction, customItemId, itemCooldown, enchantGroup, customBlock;
    public static NamespacedKey reqSkill, reqSkillLvl;
    // Off-hand & magic system keys
    public static NamespacedKey offhandTag;     // marks item as an off-hand weapon
    public static NamespacedKey bossTag;        // marks a mob as Boss (immune to stun)
    public static NamespacedKey eliteTag;       // marks a mob as Elite (immune to stun)
    public static NamespacedKey stunned;        // stun state on entity
    public static NamespacedKey scrollType;     // spell scroll type id (e.g. "ashen_spark")
    public static NamespacedKey scrollConsumable; // 1=consumable, 0=reusable
    public static NamespacedKey grimoireSlots;  // max spell slots in grimoire
    public static NamespacedKey grimoireActive; // index of currently active scroll slot
    public static NamespacedKey scrollData;     // serialized scroll list stored in grimoire
    public static NamespacedKey magicDmgItem;   // magic_damage stat on item (for staff/grimoire)
    public static NamespacedKey enchNullify;    // Nullify enchantment (damages Endermen/Wither)
    public static NamespacedKey tierDmgMult;   // tier damage multiplier stored on mob entity
    public static NamespacedKey betterModelId; // BetterModel plugin model ID for custom mobs
    public static NamespacedKey hologramUuid;  // UUID of the BetterModel TextDisplay hologram

    public static void init(Plugin plugin) {
        hp = new NamespacedKey(plugin, "hp");
        def = new NamespacedKey(plugin, "def");
        dmg = new NamespacedKey(plugin, "dmg");
        magicDmg = new NamespacedKey(plugin, "magic_dmg");
        critChance = new NamespacedKey(plugin, "cc");
        critDamage = new NamespacedKey(plugin, "cd");
        lvl = new NamespacedKey(plugin, "lvl");
        skill = new NamespacedKey(plugin, "skill");
        manaCost = new NamespacedKey(plugin, "mana_cost");
        ranged = new NamespacedKey(plugin, "ranged_only");
        mining = new NamespacedKey(plugin, "mining_power");
        gathering = new NamespacedKey(plugin, "gathering_power");
        customMobId = new NamespacedKey(plugin, "custom_mob_id");
        customItemId = new NamespacedKey(plugin, "custom_item_id");
        lastDamager = new NamespacedKey(plugin, "last_damager");
        guiAction = new NamespacedKey(plugin, "gui_action");
        itemCooldown = new NamespacedKey(plugin, "item_cooldown");
        enchantGroup = new NamespacedKey(plugin, "ench_groups");
        manaRegen = new NamespacedKey(plugin, "mana_regen");
        customBlock = new NamespacedKey(plugin, "custom_block");
        reqSkill = new NamespacedKey(plugin, "req_skill");
        reqSkillLvl = new NamespacedKey(plugin, "req_skill_lvl");
        // Off-hand & magic system
        offhandTag     = new NamespacedKey(plugin, "offhand_weapon");
        bossTag        = new NamespacedKey(plugin, "boss_mob");
        eliteTag       = new NamespacedKey(plugin, "elite_mob");
        stunned        = new NamespacedKey(plugin, "entity_stunned");
        scrollType     = new NamespacedKey(plugin, "scroll_type");
        scrollConsumable = new NamespacedKey(plugin, "scroll_consumable");
        grimoireSlots  = new NamespacedKey(plugin, "grimoire_slots");
        grimoireActive = new NamespacedKey(plugin, "grimoire_active");
        scrollData     = new NamespacedKey(plugin, "grimoire_scrolls");
        magicDmgItem   = new NamespacedKey(plugin, "magic_dmg_item");
        enchNullify    = new NamespacedKey(plugin, "ench_nullify");
        tierDmgMult    = new NamespacedKey(plugin, "tier_dmg_mult");
        betterModelId  = new NamespacedKey(plugin, "better_model_id");
        hologramUuid   = new NamespacedKey(plugin, "hologram_uuid");
    }
}
