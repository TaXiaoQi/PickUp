# 📦 PickUp — 自定义拾取插件

> 一个高性能、可配置、双模式（玩家驱动 / 物品驱动）的 Minecraft 自定义拾取插件，适用于1.16.5-1.21.10 Spigot / Paper 服务器。

---

## 🆚 核心体验对比

### 📦 原版 Minecraft 拾取机制
- 拾取范围固定（约 1 方块），不可调整
- 物品拾取无序随机，有概率会被自己捡回
- 性能极低（单个掉落物每tick检测玩家是否靠近）

### 🚀 PickUp 插件增强体验
- ✅ 支持自定义拾取距离（默认 1.5 方块，可配置）
- ✅ 支持自定义各类拾取冷却时长（丢弃、掉落、红石）
- ✅ 支持自定义丢物品拾取冷却（防丢出又被自己捡回）
- ✅ 支持自定义物品限时主动寻找附近玩家（默认60tick）
- ✅ 支持自定义物品限时内寻找玩家频率（默认2tick/次）
- ✅ 支持拾取排序，先到先得（按内部条件判定，非随机）
- ✅ 支持同掉落物合并，减少实体（1.20.1原版更新内容）
- ✅ 支持服务器重启功能，需搭配特定启动脚本，详见下面（#安全重载原理与外部脚本说明）
- ✅ 配置热重载，支持命令开关本插件（`/up true|false`），关闭插件时自动恢复原版拾取
- ✅ 重点：
-  **性能优化设计**：
  > 掉落物生成初期主动检测玩家（频率/时长可配）；  
  > 超时后转为**内存缓存 + 玩家移动触发**，大幅降低持续计算压力（以空间换时间）
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
      <td><strong>掉落物合成</strong></td>
      <td>❌ 1.20.1以下无</td>
      <td>✅ 插件自带，支持最低1.14版本，至高1.21.10版本</td>
    </tr>
    <tr>
      <td><strong>版本兼容性</strong></td>
      <td>✅ 所有版本</td>
      <td>⚠️ 仅支持 <strong>Minecraft 1.14+</strong>（Paper 推荐）</td>
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
# ========================
# 插件总开关（关闭恢复原版拾取）
# ========================
enabled: true

# ========================
#       拾取行为设置
# ========================
pickup:
  # 全局拾取半径（单位：方块），有效范围：0.1 ~ 10.0
  range: 1.5
  # 玩家丢弃冷却（单位：ticks）（防"扔了马上又捡"）
  self-immune-ticks: 5
  # 是否允许自动拾取到副手（默认false，原版行为）
  offhand-pickup: false
  # 物品拾取冷却（单位：ticks）
  delays:
    # 玩家丢弃物品拾取冷却（单位：ticks）
    player-drop: 15
    # 实体掉落物品拾取冷却（单位：ticks）
    natural-drop: 10
    # 其他类型物品拾取冷却（单位：ticks）
    instant-pickup: 0

# ========================
# 拾取模式（决定拾取如何触发）
# ========================
mode:
  # 玩家移动拾取开关
  player-driven: true
  # 检查频率（单位：ticks）：值越小响应越快，CPU越高
  player-scan-interval: 6
  # 物品主动检测拾取开关
  item-driven: true
  # 主动检测时长 （单位：ticks）
  item-active-duration: 60
  # 拾取检查频率（单位：ticks）
  item-check-interval: 2

# ========================
#     掉落物合并设置
# ========================
custom-item-merge:
  # 掉落物合并开关（减少实体数量）
  enabled: true
  # 合并检测半径（单位：方块）
  range: 1.0
  # 合并检测时长（单位：ticks）
  active-duration-ticks: 10
  # 合并检查频率（单位：ticks）：值越小响应越快，CPU越高
  scan-interval-ticks: 2

# ========================
# 死亡日志设置(播报死亡坐标)
# ========================
death-log:
  # 启用死亡日志（在后台日志输出死亡坐标）
  enabled: true
  # 启用死亡坐标播报（替换原版死亡播报）
  send-private-message: true
```
---
## 🚀 快速开始

### 安装插件
将 <code>PickUp.jar</code> 放入服务器的 <code>plugins/</code> 目录。

### 首次启动
启动服务器后，插件会自动生成 <code>plugins/PickUp/config.yml</code>，并默认启用插件的拾取功能。

### 动态控制
服务器OP玩家可使用以下命令：

<code>/up true</code>    —— 开启插件拾取（禁用原版自动拾取）  
<code>/up false</code>   —— 关闭插件拾取（恢复原版行为）  
<code>/up status</code>  —— 查看当前插件状态  
<code>/up reload</code>  —— 重载配置文件

<code>/up list</code>    —— 列出所有可配置项  
<code>/up get &lt;key&gt;</code>   —— 获取指定配置项的当前值  
<code>/up set &lt;key&gt; &lt;value&gt;</code> —— 动态修改配置项（支持 Tab 补全）

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
最低版本要求：Minecraft 1.14； 兼容：Minecraft 1.14 ~ 1.21.x（Spigot / Paper）

依赖 <code>PersistentDataContainer</code> 在1.14版首次引入， 1.16.5支持完善，推荐1.16.5版起

### 红石兼容性
可分开设置玩家丢弃、生物或方块掉落、红石组件投放的拾取冷却时长，确保兼容性。

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