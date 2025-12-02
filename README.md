# 📦 PickUp — 自定义拾取插件

> 一个高性能、可配置、双模式（玩家驱动 / 物品驱动）的 Minecraft 自定义拾取插件，专为 Paper 1.19+ 服务器设计。

---

## 🆚 核心体验对比

### 📦 原版 Minecraft 拾取机制
- 拾取范围固定（约 1 方块），不可调整
- 物品拾取无序随机，有概率会被自己捡回
- 性能极低（单个掉落物每tick检测玩家是否靠近）

### 🚀 PickUp 插件增强体验
- ✅ 支持自定义拾取距离（默认 1.5 方块，可配置）
- ✅ 支持自定义拾取冷却时长（默认 30 tick，可配置）
- ✅ 支持自定义丢弃物品冷却（防丢出去立马捡回来）
- ✅ 支持自定义物品限时主动寻找附近玩家（默认60tick）
- ✅ 支持自定义物品寻找玩家频率（默认5tick/次）
- ✅ 支持拾取排序，先到先得（扔物品玩家不会再捡回去）
- ✅ 支持服务器重启功能，需搭配特定启动脚本，详见下面（#安全重载原理与外部脚本说明）
- ✅ 配置热重载，支持命令开关插件（`/up true|false`）
- ✅ 重点：
- 掉落物生成时，依旧会主动检测玩家， 但是`频率\时长`支持自定义； 超过设定时长后，将掉落物数据以nbt形式存入内存，不再主动检测玩家；后续拾取以玩家移动对比内存数据为检测，优化服务器计算压力（以空间换时间）

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
服务器OP玩家可使用以下命令：

<code>/up true</code> &nbsp;&nbsp;&nbsp;—— 开启自动拾取  
<code>/up false</code> &nbsp;&nbsp;—— 关闭自动拾取（恢复原版行为）  
<code>/up reload</code> —— 重载配置文件

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
## 🔁 服务器重载原理与外部脚本说明

### 🧠 为什么不用 Bukkit 的 `/reload`？
Bukkit/Spigot 的原生 `/reload` 命令存在严重内存泄漏风险，官方已不推荐使用。为保障服务器长期稳定运行，PickUp 插件采用 “软关闭 + 外部重启” 机制实现安全重载。

### ⚙️ 工作原理
1. 玩家执行 `/mc reload`
2. 插件执行以下操作：
    - 停止所有事件监听器
    - 清理内存中的临时数据
    - 在插件目录创建 `restart.flag` 标记文件
    - 调用 `Bukkit.shutdown()` 平滑关闭服务器
3. **外部启动脚本** 检测到 `restart.flag` 存在 → 自动重新启动服务端
4. 重启后，插件正常加载，配置生效，无残留状态

✅ 整个过程**无内存泄漏**、**无类加载冲突**，适合生产环境。

### 💻 Windows 启动脚本示例（`start.bat`）

```bat
@echo off
chcp 65001 >nul
title Paper Minecraft Server - Auto Restart Enabled
cd /d "%~dp0"

:: 检查 Paper 文件是否存在
if not exist "paper-1.21.10-115.jar" (
    echo [ERROR] 找不到服务端文件：paper-1.21.10-115.jar
    echo 请确保该文件位于当前目录。
    pause
    exit /b
)

:launch
echo [%date% %time%] 正在启动服务器...
java -Xmx1G -Xms1G -jar paper-1.21.10-115.jar nogui

echo [%date% %time%] 服务器已关闭。

:: 检查是否需要重启
if exist restart.flag (
    del /f /q restart.flag >nul 2>&1
    echo [%date% %time%] 检测到重启请求，5秒后重新启动...
    timeout /t 5 /nobreak >nul
    goto launch
) else (
    echo.
    echo 未检测到重启请求，按任意键退出...
    pause
    exit /b
)
```

> 📝 **使用说明**：
> - 将此脚本保存为 `start.bat`，与 `paper-*.jar` 放在同一目录
> - **始终通过双击 `start.bat` 启动服务器**
> - 执行 `/mc reload` 后，服务器会自动重启并应用新配置

### 🐧 Linux / macOS 用户？

只需编写等效的 Shell 脚本（`start.sh`），逻辑相同：

```bash
#!/bin/bash
while true; do
  java -Xmx1G -Xms1G -jar paper-1.21.10-115.jar nogui
  if [ -f "restart.flag" ]; then
    rm -f restart.flag
    echo "检测到重启请求，5秒后重启..."
    sleep 5
  else
    echo "正常退出。"
    break
  fi
done
```
## 📄 许可证
本项目采用 [MIT License](LICENSE) 开源。
> PickUp — 让拾取更智能，而不失原味。