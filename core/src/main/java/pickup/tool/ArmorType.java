package pickup.tool;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

// 工具类，判断某 Material 是否为头盔/胸甲/护腿/靴子
public class ArmorType {

    private static final Set<Material> HELMETS = EnumSet.noneOf(Material.class);
    private static final Set<Material> CHESTPLATES = EnumSet.noneOf(Material.class);
    private static final Set<Material> LEGGINGS = EnumSet.noneOf(Material.class);
    private static final Set<Material> BOOTS = EnumSet.noneOf(Material.class);
    private static final Set<Material> WEAPONS = EnumSet.noneOf(Material.class);
    private static final Set<Material> TOOLS = EnumSet.noneOf(Material.class);

    static {
        // 初始化所有可能的盔甲材料
        initArmors();
        initWeapons();
        initTools();
    }

    private static void initArmors() {
        // 头盔
        addIfPresent(HELMETS, "LEATHER_HELMET");
        addIfPresent(HELMETS, "CHAINMAIL_HELMET");
        addIfPresent(HELMETS, "IRON_HELMET");
        addIfPresent(HELMETS, "GOLD_HELMET");
        addIfPresent(HELMETS, "GOLDEN_HELMET");
        addIfPresent(HELMETS, "DIAMOND_HELMET");
        addIfPresent(HELMETS, "NETHERITE_HELMET");
        addIfPresent(HELMETS, "TURTLE_HELMET");

        // 头部装饰物
        addIfPresent(HELMETS, "PLAYER_HEAD");
        addIfPresent(HELMETS, "ZOMBIE_HEAD");
        addIfPresent(HELMETS, "CREEPER_HEAD");
        addIfPresent(HELMETS, "DRAGON_HEAD");
        addIfPresent(HELMETS, "SKELETON_SKULL");
        addIfPresent(HELMETS, "WITHER_SKELETON_SKULL");
        addIfPresent(HELMETS, "PIGLIN_HEAD");
        addIfPresent(HELMETS, "CARVED_PUMPKIN");

        // 胸甲
        addIfPresent(CHESTPLATES, "LEATHER_CHESTPLATE");
        addIfPresent(CHESTPLATES, "CHAINMAIL_CHESTPLATE");
        addIfPresent(CHESTPLATES, "IRON_CHESTPLATE");
        addIfPresent(CHESTPLATES, "GOLD_CHESTPLATE");
        addIfPresent(CHESTPLATES, "GOLDEN_CHESTPLATE");
        addIfPresent(CHESTPLATES, "DIAMOND_CHESTPLATE");
        addIfPresent(CHESTPLATES, "NETHERITE_CHESTPLATE");
        addIfPresent(CHESTPLATES, "ELYTRA");

        // 护腿
        addIfPresent(LEGGINGS, "LEATHER_LEGGINGS");
        addIfPresent(LEGGINGS, "CHAINMAIL_LEGGINGS");
        addIfPresent(LEGGINGS, "IRON_LEGGINGS");
        addIfPresent(LEGGINGS, "GOLD_LEGGINGS");
        addIfPresent(LEGGINGS, "GOLDEN_LEGGINGS");
        addIfPresent(LEGGINGS, "DIAMOND_LEGGINGS");
        addIfPresent(LEGGINGS, "NETHERITE_LEGGINGS");

        // 靴子
        addIfPresent(BOOTS, "LEATHER_BOOTS");
        addIfPresent(BOOTS, "CHAINMAIL_BOOTS");
        addIfPresent(BOOTS, "IRON_BOOTS");
        addIfPresent(BOOTS, "GOLD_BOOTS");
        addIfPresent(BOOTS, "GOLDEN_BOOTS");
        addIfPresent(BOOTS, "DIAMOND_BOOTS");
        addIfPresent(BOOTS, "NETHERITE_BOOTS");
    }

    private static void initWeapons() {
        // 武器
        addIfPresent(WEAPONS, "WOODEN_SWORD");
        addIfPresent(WEAPONS, "STONE_SWORD");
        addIfPresent(WEAPONS, "IRON_SWORD");
        addIfPresent(WEAPONS, "GOLDEN_SWORD");
        addIfPresent(WEAPONS, "DIAMOND_SWORD");
        addIfPresent(WEAPONS, "NETHERITE_SWORD");

        addIfPresent(WEAPONS, "BOW");
        addIfPresent(WEAPONS, "CROSSBOW");
        addIfPresent(WEAPONS, "TRIDENT");

        // 斧头既是工具也是武器
        addIfPresent(WEAPONS, "WOODEN_AXE");
        addIfPresent(WEAPONS, "STONE_AXE");
        addIfPresent(WEAPONS, "IRON_AXE");
        addIfPresent(WEAPONS, "GOLDEN_AXE");
        addIfPresent(WEAPONS, "DIAMOND_AXE");
        addIfPresent(WEAPONS, "NETHERITE_AXE");
    }

    private static void initTools() {
        // 工具
        String[] toolTypes = {"PICKAXE", "SHOVEL", "AXE", "HOE"};
        String[] materials = {"WOODEN", "STONE", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"};

        for (String material : materials) {
            for (String type : toolTypes) {
                String name = material + "_" + type;
                addIfPresent(TOOLS, name);
            }
        }

        // 其他工具
        addIfPresent(TOOLS, "FISHING_ROD");
        addIfPresent(TOOLS, "SHEARS");
        addIfPresent(TOOLS, "FLINT_AND_STEEL");
        addIfPresent(TOOLS, "CARROT_ON_A_STICK");
        addIfPresent(TOOLS, "WARPED_FUNGUS_ON_A_STICK");
        addIfPresent(TOOLS, "SHIELD");
        addIfPresent(TOOLS, "COMPASS");
        addIfPresent(TOOLS, "CLOCK");
    }

    private static void addIfPresent(Set<Material> set, String name) {
        try {
            Material material = Material.getMaterial(name);
            if (material != null) {
                set.add(material);
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean isHelmet(Material material) {
        return material != null && HELMETS.contains(material);
    }

    public static boolean isChestplate(Material material) {
        return material != null && CHESTPLATES.contains(material);
    }

    public static boolean isLeggings(Material material) {
        return material != null && LEGGINGS.contains(material);
    }

    public static boolean isBoots(Material material) {
        return material != null && BOOTS.contains(material);
    }
}