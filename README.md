# 📦 PickUp — 智能自动拾取插件

> 一个高性能、可配置、双模式（玩家驱动 / 物品驱动）的 Minecraft 自动拾取插件，专为 Paper 1.19+ 服务器设计。

---

## 🆚 核心体验对比

### 📦 原版 Minecraft 拾取机制
- 必须走到物品正上方才能拾取
- 拾取范围固定（约 1 方块），不可调整
- 刚丢出的物品会立刻被自己捡回，易造成误操作
- 无冷却、无免疫、无配置选项
- 性能极低但功能极其有限

### 🚀 PickUp 插件增强体验
- ✅ 支持远程自动拾取（默认 1.5 方块，可配置）
- ✅ 投掷后有冷却期（默认 30 tick），防止刚扔就捡
- ✅ 自己丢的物品对自己额外免疫（防连捡）
- ✅ 双模式：玩家移动时扫描 or 物品主动寻找附近玩家
- ✅ 音效精准还原原版（0.1 音量 + 随机音调）
- ✅ 配置热重载，支持命令开关（`/up true|false`）

---

## 📊 详细功能对比表

<table>
  <thead>
    <tr>
      <th>功能特性</th>
      <th>原版 Minecraft</th>
      <th>PickUp 插件</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><strong>拾取方式</strong></td>
      <td>❌ 必须接触物品</td>
      <td>✅ 远程自动拾取，无需接触</td>
    </tr>
    <tr>
      <td><strong>拾取范围</strong></td>
      <td>固定 ≈1 方块（不可调）</td>
      <td>✅ 可配置（0.1 ~ 10.0 方块）</td>
    </tr>
    <tr>
      <td><strong>投掷冷却免疫</strong></td>
      <td>❌ 无，立即可捡</td>
      <td>✅ 物品生成后冷却（默认 30 tick）</td>
    </tr>
    <tr>
      <td><strong>自我丢弃免疫</strong></td>
      <td>❌ 无区分</td>
      <td>✅ 自己丢的物品对自己额外免疫（可配）</td>
    </tr>
    <tr>
      <td><strong>拾取模式</strong></td>
      <td>仅被动拾取</td>
      <td>✅ 双模式：<br>• 玩家驱动（移动触发）<br>• 物品驱动（物品主动检测）</td>
    </tr>
    <tr>
      <td><strong>性能优化</strong></td>
      <td>全局简单检测</td>
      <td>✅ 仅监控含受管物品的区块，多世界支持，低开销</td>
    </tr>
    <tr>
      <td><strong>数据持久化</strong></td>
      <td>❌ 无状态</td>
      <td>✅ 使用 PersistentDataContainer 存储元数据，跨重启有效</td>
    </tr>
    <tr>
      <td><strong>音效体验</strong></td>
      <td>原生音效</td>
      <td>✅ 精准还原原版（0.1 音量 + 随机 pitch）</td>
    </tr>
    <tr>
      <td><strong>版本兼容性</strong></td>
      <td>✅ 所有版本</td>
      <td>⚠️ 仅支持 <strong>Minecraft 1.19+</strong>（Paper 推荐）</td>
    </tr>
    <tr>
      <td><strong>配置与管理</strong></td>
      <td>❌ 不可配置</td>
      <td>✅ 全参数通过 <code>config.yml</code> 调整<br>✅ 支持命令热开关（<code>/up true|false</code>）<br>✅ 支持安全重启（<code>/mc reload</code>）</td>
    </tr>
  </tbody>
</table>

---

## 🛠 适用场景

- ✅ 生存服务器（提升便利性）
- ✅ RPG / MMO 服务器（自定义拾取范围）
- ✅ 小游戏（如“捡金币”类玩法）
- ✅ 高性能需求服（比同类插件更轻量）

---

## 📄 配置示例 (`config.yml`)

```yaml
# 是否启用插件拾取
# 为 true 时使用插件拾取逻辑，为 false 时恢复原版拾取
enabled: true

# 玩家移动时触发拾取（玩家驱动模式）
player-driven: true

# 全局拾取半径（方块），有效范围：0.1 ~ 10.0
pickup-range: 1.5

# 全局物品延迟拾取（ticks，20 ticks = 1秒）
# 物品生成后需等待此时间才能被拾取（防刷）
throw-cooldown-ticks: 30

# 物品主动行为设置（物品驱动模式）
item-driven:

  # 是否启用物品主动寻找附近玩家
  enabled: true

  # 物品生成后多少 ticks 内会尝试寻找玩家（超过则停止）
  active-detection-ticks: 60

  # 每隔多少 ticks 尝试一次拾取（值越小越频繁，性能消耗越大）
  # 建议 ≥1，例如：5 = 每秒4次，10 = 每秒2次
  pickup-attempt-interval-ticks: 5

  # 玩家丢出的物品在此 ticks 内不能被自己拾取（防“扔了又捡”）
  self-immune-ticks: 5
```
---
## 🚀 快速开始

### 安装插件
将 <code>PickUp.jar</code> 放入服务器的 <code>plugins/</code> 目录。

### 首次启动
启动服务器后，插件会自动生成 <code>plugins/PickUp/config.yml</code>，并默认启用拾取功能。

### 动态控制
玩家或管理员可使用以下命令：

<code>/up true</code> &nbsp;&nbsp;&nbsp;—— 开启自动拾取  
<code>/up false</code> &nbsp;&nbsp;—— 关闭自动拾取（恢复原版行为）  
<code>/up reload</code> —— 重载配置文件（无需重启）

🔒 权限要求：执行者需拥有 <code>pickup.admin</code> 权限（默认 OP 拥有）。

### 安全重启（推荐用于生产环境）
执行命令：

<code>/mc reload</code>

插件将：
- 停止所有监听器
- 清理内存中的临时数据
- 创建 <code>restart.flag</code> 文件

配合外部启动脚本（如 <code>start.sh</code>），可实现 **无缝重启**，避免 Bukkit <code>/reload</code> 导致的内存泄漏问题。

💡 提示：<code>/mc reload</code> 是专为本插件设计的安全重启机制，不是通用 Minecraft 命令。

---

## ⚠️ 注意事项

### 版本兼容性
最低版本要求：Minecraft 1.19+（Paper 推荐）  
因依赖 <code>PersistentDataContainer</code> 在物品实体上的稳定实现，不支持 1.18.2 及以下版本。

### 红石兼容性
仅影响玩家拾取行为，漏斗、投掷器、矿车等红石组件仍使用原版逻辑，确保兼容性。

### 多世界支持
所有设置自动应用于所有已加载的世界，无需额外配置.

---

> PickUp — 让拾取更智能，而不失原味。