# MiniGame

MiniGame 是一个基于 NeoForge 的 Minecraft 模组项目，用来放一些偏“小游戏规则”的功能。

当前支持两个功能：

- `SameBlockBreak`：玩家破坏一个方块后，范围内同种方块会一起被破坏。
- `ChunkPlaceBlock`：玩家放置一个方块后，范围内其它区块的相同区块内坐标也会尝试放置同样的方块。

## 环境

- Minecraft：`26.1.2`
- NeoForge：`26.1.2.73`
- Java：`25`
- Mod ID：`minigame`

## 构建与运行

编译：

```powershell
.\gradlew.bat build
```

启动客户端开发环境：

```powershell
.\gradlew.bat runClient
```

启动服务端开发环境：

```powershell
.\gradlew.bat runServer
```

构建出的模组 jar 在：

```text
build/libs/
```

## 配置文件

开发环境下配置文件会生成在：

```text
run/config/minigame/
```

主要配置文件：

- `sameblockbreak.toml`
- `chunkplaceblock.toml`

修改配置后，建议重启世界或服务器，或者使用下面的命令开关功能。

## SameBlockBreak

功能：玩家破坏一个方块后，模组会扫描配置半径内的区块，把匹配的方块一起破坏。

默认行为：

- 默认启用。
- 默认只匹配同种方块。
- 默认只处理已加载区块。
- 水源和岩浆源也可以被桶触发。
- 可以限制自然掉落范围，避免大量掉落物卡服。

常用配置：

| 配置项 | 说明 |
| --- | --- |
| `enabled` | 是否启用 SameBlockBreak |
| `radius` | 扫描半径，单位为方块 |
| `matchBlockType` | 是否只破坏同种方块 |
| `dropRadius` | 距离触发点多远范围内使用自然破坏并掉落物品 |
| `maxNaturalBreakBlocks` | 单次任务最多自然破坏多少方块，`0` 表示不掉落，`-1` 表示不限制 |
| `cleanupUnsupportedNeighbors` | 是否清理失去支撑的附着类方块 |
| `rememberBrokenBlocksForever` | 是否记住已破坏的方块类型，阻止它们以后生成或放置 |
| `loadedChunksOnly` | 是否只处理已加载区块 |
| `allowChunkGeneration` | 是否允许同步加载或生成缺失区块 |

命令：

```mcfunction
/minigame sameblockbreak status
/minigame sameblockbreak cancel
/minigame sameblockbreak cancelall
/minigame sameblockbreak enable
/minigame sameblockbreak disable
```

这些命令需要游戏管理员权限。

## ChunkPlaceBlock

功能：玩家放置方块后，模组会在配置半径内其它区块的相同位置尝试放置同样的方块。

这里的“相同位置”指：

- 区块内 X 坐标相同。
- Y 坐标相同。
- 区块内 Z 坐标相同。

例如玩家在某区块内 `(localX=5, y=64, localZ=8)` 放置箱子，其它区块的 `(localX=5, y=64, localZ=8)` 如果是空气，也会尝试放置同样的箱子。

默认行为：

- 默认启用。
- 只会放置到空气位置，不会覆盖已有方块。
- 支持普通方块放置。
- 支持水桶和岩浆桶放置源方块。
- 会复制方块实体数据，例如箱子里的物品。
- 被复制出来的方块实体数据变化后，会同步到其它已管理的同位置方块。
- 会记住放置规则，之后区块加载或生成时再补放。
- 玩家放置方块时会全服提示：`'玩家名' 放置了 '方块名'`。

常用配置：

| 配置项 | 说明 |
| --- | --- |
| `enabled` | 是否启用 ChunkPlaceBlock |
| `radius` | 处理半径，单位为方块 |
| `syncBlockEntityData` | 是否复制并同步方块实体数据，例如箱子内容 |
| `applyToFutureChunkLoads` | 区块以后加载或生成时是否继续补放 |
| `destroySamePositionOnBreak` | 破坏方块时，是否同步破坏其它区块同位置同种方块 |
| `placementDelayTicks` | 放置后等待多少 tick 再读取源方块数据 |
| `blockEntitySyncDelayTicks` | 方块实体数据变化后等待多少 tick 再同步 |
| `loadedChunksOnly` | 是否只处理已加载区块 |
| `allowChunkGeneration` | 是否允许同步加载或生成缺失区块 |

命令：

```mcfunction
/minigame chunkplaceblock enable
/minigame chunkplaceblock disable
```

这些命令需要游戏管理员权限。

## 两个功能的关系

`SameBlockBreak` 和 `ChunkPlaceBlock` 都可能处理破坏行为。

为了避免两个功能同时抢同一类逻辑：

- 当 `SameBlockBreak` 启用时，`ChunkPlaceBlock` 的 `destroySamePositionOnBreak` 会自动设为 `false`。
- 如果你想使用 `ChunkPlaceBlock` 自己的同位置破坏功能，需要先关闭 `SameBlockBreak`。

## 性能建议

这类功能会扫描或修改大量区块，半径越大，对服务器压力越高。

建议：

- 大半径时保持 `loadedChunksOnly=true`。
- 不建议随便开启 `allowChunkGeneration`，它可能导致服务器同步加载或生成大量区块。
- 如果服务器卡顿，优先降低 `radius`、`chunksPerTick`、`blocksPerTick` 或 `maxMsPerTick`。
- 大规模破坏时适当降低掉落数量，避免掉落物过多。

## 说明

这个项目还在继续扩展中。后续如果增加新的小游戏功能，建议为每个功能单独建立：

- 独立 package
- 独立 config
- 独立 command
- 独立 feature/register 入口

这样主类只负责注册各个功能，逻辑会比较清楚。
