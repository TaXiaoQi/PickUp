package pickup.feature.pickupmanager;

import org.bukkit.entity.*;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import pickup.Main;
import pickup.config.PickupConfig;
import pickup.feature.ItemSpatialIndex;

/**
 * 拾取管理器 - 协调者类
 * 负责协调各个组件的工作，对外提供统一接口
 */
public class PickupManager implements PickupConfig.ConfigChangeListener {
    private final Main plugin;
    private final PickupConfig config;
    private final ItemSpatialIndex itemIndex;

    // 子组件
    private final ItemLifecycleManager lifecycleManager;
    private final PlayerDrivenPickupHandler playerHandler;
    private final ItemDrivenPickupScheduler itemScheduler;


    // 管理器运行状态
    private boolean active = false;

    public PickupManager(Main plugin, PickupConfig config, ItemSpatialIndex spatialIndex) {
        this.plugin = plugin;
        this.config = config;
        this.itemIndex = spatialIndex;

        // 初始化子组件
        this.lifecycleManager = new ItemLifecycleManager(plugin, itemIndex);
        PickupExecutor pickupExecutor = new PickupExecutor(plugin, config, itemIndex, lifecycleManager);
        this.playerHandler = new PlayerDrivenPickupHandler(plugin, config, itemIndex, pickupExecutor);
        this.itemScheduler = new ItemDrivenPickupScheduler(plugin, config, itemIndex, pickupExecutor);

        this.config.addChangeListener(this);

    }


    /**
     * 配置变更监听器实现
     */
    @Override
    public void onConfigChanged(String key, Object value) {
        plugin.getLogger().info("配置变更: " + key + " = " + value);

        // 根据配置变化重新启用/禁用相关组件
        switch (key) {
            case "mode.player-driven":
                if (config.isPlayerDriven()) {
                    playerHandler.enable();
                } else {
                    playerHandler.disable();
                }
                break;

            case "mode.item-driven":
                if (config.isItemDrivenEnabled()) {
                    itemScheduler.enable();
                } else {
                    itemScheduler.disable();
                }
                break;

            case "pickup.range":
            case "pickup.delays.player-drop":
            case "pickup.delays.natural-drop":
            case "pickup.delays.instant-pickup":
            case "pickup.self-immune-ticks":
            case "mode.item-active-duration":
                plugin.getLogger().info("配置已更新，相关组件将使用新值");
                break;

            case "enabled":
                if (Boolean.TRUE.equals(value)) {
                    if (config.isPlayerDriven()) {
                        playerHandler.enable();
                    }
                    if (config.isItemDrivenEnabled()) {
                        itemScheduler.enable();
                    }
                } else {
                    playerHandler.disable();
                    itemScheduler.disable();
                }
                break;

            case "__RELOAD_ALL__":
                // 重新加载所有配置后重新启用组件
                if (!plugin.isPickupDisabled()) {
                    if (config.isPlayerDriven()) {
                        playerHandler.enable();
                    } else {
                        playerHandler.disable();
                    }

                    if (config.isItemDrivenEnabled()) {
                        itemScheduler.enable();
                    } else {
                        itemScheduler.disable();
                    }
                }
                plugin.getLogger().info("所有配置已重新加载");
                break;
        }
    }

    // ====== 公共入口：由 PickupEvent 调用 ======

    /**
     * 处理物品生成事件
     */
    public void handleItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        lifecycleManager.handleItemSpawn(item);

        // 启动物品驱动任务
        if (config.isItemDrivenEnabled()) {
            itemScheduler.startItemDrivenPickupTask(item);
        }
    }

    /**
     * 处理玩家丢弃物品事件
     */
    public void handlePlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItemDrop();
        lifecycleManager.handlePlayerDrop(item, player.getUniqueId());
    }

    /**
     * 处理方块掉落物品事件
     */
    public void handleBlockDrop(BlockDropItemEvent event) {
        for (Item item : event.getItems()) {
            lifecycleManager.handleBlockDrop(item);
        }
    }

    /**
     * 处理实体死亡事件
     */
    public void handleEntityDeath(EntityDeathEvent event) {
        lifecycleManager.handleEntityDeath(event);
    }

    /**
     * 玩家驱动的拾取扫描
     */
    public void tryPickup(Player player) {
        playerHandler.tryPickup(player);
    }

    // ====== 启用/禁用控制 ======

    /**
     * 启用拾取管理器
     */
    public void enable() {
        if (active) return;
        active = true;

        if (config.isPlayerDriven()) {
            playerHandler.enable();
        }
        if (config.isItemDrivenEnabled()) {
            itemScheduler.enable();
        }
    }

    /**
     * 禁用拾取管理器
     */
    public void disable() {
        if (!active) return;
        active = false;

        playerHandler.disable();
        itemScheduler.disable();
    }

    // ====== 工具方法 ======

    public boolean hasPickupableItems(org.bukkit.World world) {
        return itemIndex.hasItemsInWorld(world);
    }

    public boolean hasPickupMark(ItemStack stack) {
        return lifecycleManager.hasPickupMark(stack);
    }

    public ItemStack createCleanStack(ItemStack original) {
        return lifecycleManager.createCleanStack(original);
    }

    public boolean isActive() {
        return active && (
                (config.isPlayerDriven() && playerHandler.isActive()) ||
                        (config.isItemDrivenEnabled() && itemScheduler.isActive())
        );
    }

    // ===== 获取组件实例 =====
    public ItemLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    public Main getPlugin() {
        return plugin;
    }
}