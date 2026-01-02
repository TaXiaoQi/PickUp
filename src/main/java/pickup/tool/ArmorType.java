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

    static {
        // 初始化所有可能的盔甲材料（按版本逐步添加）
        addIfPresent(HELMETS, "LEATHER_HELMET");
        addIfPresent(HELMETS, "CHAINMAIL_HELMET");
        addIfPresent(HELMETS, "IRON_HELMET");
        addIfPresent(HELMETS, "GOLD_HELMET");      // 1.13-1.15 用 GOLD_HELMET
        addIfPresent(HELMETS, "GOLDEN_HELMET");    // 1.16+ 用 GOLDEN_HELMET
        addIfPresent(HELMETS, "DIAMOND_HELMET");
        addIfPresent(HELMETS, "NETHERITE_HELMET"); // 1.16+
        addIfPresent(HELMETS, "TURTLE_HELMET");    // 1.13+

        // 头部可穿戴物（原版允许戴在头上）
        addIfPresent(HELMETS, "PLAYER_HEAD");
        addIfPresent(HELMETS, "ZOMBIE_HEAD");
        addIfPresent(HELMETS, "CREEPER_HEAD");
        addIfPresent(HELMETS, "DRAGON_HEAD");
        addIfPresent(HELMETS, "SKELETON_SKULL");
        addIfPresent(HELMETS, "WITHER_SKELETON_SKULL");

        addIfPresent(CHESTPLATES, "LEATHER_CHESTPLATE");
        addIfPresent(CHESTPLATES, "CHAINMAIL_CHESTPLATE");
        addIfPresent(CHESTPLATES, "IRON_CHESTPLATE");
        addIfPresent(CHESTPLATES, "GOLD_CHESTPLATE");
        addIfPresent(CHESTPLATES, "GOLDEN_CHESTPLATE");
        addIfPresent(CHESTPLATES, "DIAMOND_CHESTPLATE");
        addIfPresent(CHESTPLATES, "NETHERITE_CHESTPLATE");
        addIfPresent(CHESTPLATES, "ELYTRA"); // 1.9+

        addIfPresent(LEGGINGS, "LEATHER_LEGGINGS");
        addIfPresent(LEGGINGS, "CHAINMAIL_LEGGINGS");
        addIfPresent(LEGGINGS, "IRON_LEGGINGS");
        addIfPresent(LEGGINGS, "GOLD_LEGGINGS");
        addIfPresent(LEGGINGS, "GOLDEN_LEGGINGS");
        addIfPresent(LEGGINGS, "DIAMOND_LEGGINGS");
        addIfPresent(LEGGINGS, "NETHERITE_LEGGINGS");

        addIfPresent(BOOTS, "LEATHER_BOOTS");
        addIfPresent(BOOTS, "CHAINMAIL_BOOTS");
        addIfPresent(BOOTS, "IRON_BOOTS");
        addIfPresent(BOOTS, "GOLD_BOOTS");
        addIfPresent(BOOTS, "GOLDEN_BOOTS");
        addIfPresent(BOOTS, "DIAMOND_BOOTS");
        addIfPresent(BOOTS, "NETHERITE_BOOTS");
    }

    private static void addIfPresent(Set<Material> set, String name) {
        try {
            Material material = Material.getMaterial(name);
            if (material != null) {
                set.add(material);
            }
        } catch (Exception ignored) {
            // 忽略不存在的 Material（如旧版本没有 NETHER）
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