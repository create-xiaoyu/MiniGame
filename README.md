# CarvsBlockBreak logic notes and NeoForge porting guide

本文档说明当前 Bukkit/Paper/Spigot 插件如何做到“破坏一个方块后，世界内同种方块批量破坏”，并给出在 NeoForge 上复刻该逻辑的详细设计。

当前目录像是插件 jar 的解包结果，只有 `plugin.yml`、`config.yml` 和 `.class` 文件，没有 Java 源码。因此下面的 Bukkit 逻辑来自对 `.class` 的反编译分析，重点类是：

- `com.carvs.blockbreak.listeners.BlockBreakListener`
- `com.carvs.blockbreak.queue.DestructionManager`
- `com.carvs.blockbreak.queue.ChunkDestructionTask`
- `com.carvs.blockbreak.config.PluginConfig`

## 目标行为

玩家破坏一个方块时，插件记录这个方块的类型和坐标，然后在一个指定半径内查找同种方块并删除。

默认配置中：

- `destruction.radius: 3000`
- `destruction.match-block-type: true`
- `destruction.default-drop-radius: 50`
- `performance.chunks-per-tick: 200`
- `performance.max-ms-per-tick: 15`
- `performance.tick-delay: 1`
- `performance.loaded-chunks-only: false`

也就是说，它默认会在触发点周围 3000 格的方形区域内处理同种方块。实际覆盖范围是 `6000 x 6000` 的水平区域。

## 一句话总结性能策略

它不是一次性把世界内所有方块都扫描并删除，而是：

1. 先把目标区域拆成 chunk 队列。
2. 每 tick 只处理一小批 chunk。
3. 每 tick 使用毫秒预算强制停止。
4. 每个 chunk 内先用轻量快照读取方块类型。
5. 只有真正命中的方块才拿真实 `Block` 对象做修改。
6. 远离玩家的方块直接静默删除，不掉落物品。
7. 静默删除时禁用物理更新，避免连锁方块更新。

这几个点合起来，才是它不容易卡顿的原因。

## Bukkit 插件触发链路

### 1. `BlockBreakListener.onBlockBreak`

当玩家破坏方块时：

1. 读取玩家、世界、配置。
2. 检查黑名单世界。
3. 如果当前世界已有破坏任务，且配置不允许并发任务，则拒绝。
4. 如果需要确认，则要求玩家 5 秒内再次破坏方块。
5. 读取被破坏方块类型：
   - `brokenBlockType = event.getBlock().getType()`
6. 读取被破坏方块位置：
   - `blockLocation = event.getBlock().getLocation()`
7. 如果 `match-block-type = true`，目标类型就是这个 `Material`。
8. 调用：
   - `DestructionManager.startDestruction(world, player, targetMaterial, blockLocation)`

注意：反编译结果显示，类里存在 `isPlayerAuthorized`，但 `onBlockBreak` 里没有看到它被调用。因此 `allowed-players`、权限配置可能没有实际限制触发者。配置里也有 cooldown 相关字段和方法，但主破坏事件里没有看到实际冷却判断。

### 2. `DestructionManager.startDestruction`

管理器负责创建并保存任务。

核心行为：

1. 按玩家读取本次掉落半径。
2. 按玩家读取本次最大掉落数。
3. 创建 `ChunkDestructionTask`。
4. 把任务加入 `activeTasks`：
   - key 是世界名
   - value 是该世界正在运行的任务列表
5. 调用任务的 `start()`。
6. 任务结束时从 `activeTasks` 移除。

它支持同一个世界多个任务并发，因为 `activeTasks` 的 value 是一个任务列表，不是单个任务。

## `ChunkDestructionTask` 的完整工作流程

这是核心类。它负责：

- 生成 chunk 队列
- 按 tick 调度
- 控制每 tick 最大耗时
- 加载或跳过 chunk
- 扫描 chunk 内方块
- 破坏目标方块
- 统计进度
- 更新 action bar / boss bar / console log

### 1. 任务初始化

任务保存这些关键字段：

- `world`
- `initiator`
- `targetMaterials`
- `center`
- `radius`
- `dropRadius`
- `maxDrops`
- `chunkList`
- `chunkIndex`
- `processedChunks`
- `blocksDestroyed`
- `skippedChunks`
- `emptyChunks`
- `totalDrops`
- `startTime`
- `cancelled`

其中 `targetMaterials` 为 null 或空集合时，表示破坏所有非空气方块。正常破坏同种方块时，它只包含一个 `Material`。

### 2. `start()`

任务启动时：

1. 记录 `startTime`。
2. 调用 `queueAllWorldChunks()` 生成 chunk 队列。
3. 如果没有 chunk，直接结束。
4. 播放开始音效，创建 boss bar。
5. 日志输出本次任务目标、chunk 总数、半径、世界名。
6. 创建一个 `BukkitRunnable`：
   - delay = 0
   - period = `config.tickDelay`
7. 每次 tick 执行：
   - 如果取消：清理并停止任务。
   - 调用 `processChunks()`。
   - 调用 `preloadChunks()`。
   - 如果 `chunkIndex >= chunkList.size()`，调用 `complete()` 并停止任务。

### 3. `queueAllWorldChunks()`

这个方法把半径内的 chunk 排队。

#### 坐标换算

它先把中心方块坐标转成 chunk 坐标：

```text
centerChunkX = centerBlockX >> 4
centerChunkZ = centerBlockZ >> 4
```

然后把破坏半径换成 chunk 范围：

```text
minChunkX = (centerBlockX - radius) >> 4
maxChunkX = (centerBlockX + radius) >> 4
minChunkZ = (centerBlockZ - radius) >> 4
maxChunkZ = (centerBlockZ + radius) >> 4
```

注意：这里用右移 `>> 4`，等价于按 16 做向下取整的 chunk 坐标换算。NeoForge 复刻时不要用普通 `/ 16`，因为负坐标会出错。应使用 `SectionPos.blockToSectionCoord(blockX)` 或 `Math.floorDiv(blockX, 16)`。

#### 扫描 `.mca` region 文件

插件会读取世界目录下的：

```text
<world>/region/*.mca
```

每个 `.mca` 文件名类似：

```text
r.<regionX>.<regionZ>.mca
```

region 文件中，一个 region 包含 `32 x 32` 个 chunk。插件读取 `.mca` 文件前 4096 字节 location table：

```text
entryIndex = 4 * (localChunkX + localChunkZ * 32)
offset = header[entryIndex] << 16 | header[entryIndex + 1] << 8 | header[entryIndex + 2]
sectorCount = header[entryIndex + 3]
```

如果：

```text
offset != 0 && sectorCount != 0
```

则认为这个 chunk 在磁盘上实际存在。

如果读取 `.mca` 文件失败，插件会保守地把这个 region 的 1024 个 chunk 全部视为存在。

#### 优先处理已存在 chunk

插件把半径内 chunk 分成两组：

1. `existingChunks`
   - `.mca` header 显示确实存在的 chunk
2. `possibleChunks`
   - 半径内但没有在 `.mca` header 中确认存在的 chunk

两组分别按距离中心 chunk 的平方距离排序：

```text
distance = (chunkX - centerChunkX)^2 + (chunkZ - centerChunkZ)^2
```

最终队列顺序：

```text
chunkList = existingChunks sorted by distance
chunkList += possibleChunks sorted by distance
```

这样附近 chunk 会优先破坏，已经生成过的 chunk 会优先处理。

### 4. `processChunks()`

这是每 tick 的主处理函数。

核心限流逻辑：

```text
startNanos = System.nanoTime()
budgetNanos = maxMsPerTick * 1_000_000
maxChunks = chunksPerTick
chunksProcessedThisTick = 0

while chunksProcessedThisTick < maxChunks:
    idx = chunkIndex.getAndIncrement()
    if idx >= chunkList.size:
        break

    process one chunk
    chunksProcessedThisTick++

    if System.nanoTime() - startNanos > budgetNanos:
        break
```

这意味着 `chunks-per-tick` 是安全上限，而真正控制卡顿的是 `max-ms-per-tick`。

当前配置是每 tick 最多 15ms。Minecraft 服务器 20 TPS 时每 tick 约 50ms，因此理论上会保留一部分主线程时间给其他逻辑。

#### chunk 加载策略

每个 chunk 处理前：

1. 判断是否已加载：
   - `world.isChunkLoaded(x, z)`
2. 如果未加载且 `loaded-chunks-only = true`：
   - 跳过该 chunk
   - `skippedChunks++`
   - `processedChunks++`
3. 如果未加载且 `loaded-chunks-only = false`：
   - 调用 `world.loadChunk(x, z, true)`
   - 然后 `world.getChunkAt(x, z)`

风险：`loadChunk(x, z, true)` 可能导致同步加载甚至生成 chunk。大半径下，如果很多 chunk 没加载，这仍然可能卡。

### 5. `preloadChunks()`

每 tick 处理完之后，插件会提前请求后续 50 个 chunk：

```text
start = current chunkIndex
end = min(start + 50, chunkList.size)
for i in start..end:
    if chunk not loaded:
        world.getChunkAtAsync(x, z)
```

这一步用于降低后续 tick 同步加载 chunk 的概率。

注意：它只是预加载 chunk，不会异步修改方块。Bukkit/Paper 中实际世界修改仍必须在主线程完成。

### 6. `destroyChunkBlocks(chunk)`

这是每个 chunk 内真正删除方块的方法。

#### 读取快照

插件先拿 `ChunkSnapshot`：

```java
ChunkSnapshot snapshot = chunk.getChunkSnapshot();
```

随后大部分读取都来自 snapshot：

```java
Material blockType = snapshot.getBlockType(localX, y, localZ);
```

这样避免对每个坐标都调用真实世界对象。只有命中的方块，才调用：

```java
Block block = chunk.getBlock(localX, y, localZ);
```

这是一个重要优化。

#### 扫描顺序

它遍历 chunk 内的 `16 x 16` 水平列：

```text
for localX in 0..15:
  for localZ in 0..15:
```

每列先求最高方块：

```java
highestY = world.getHighestBlockYAt(worldX, worldZ, HeightMap.WORLD_SURFACE);
```

然后从 `highestY` 往 `world.getMinHeight()` 向下扫：

```text
for y = highestY down to minY:
```

这样不会扫描世界最高高度以上的空气。

#### 跳过空气

如果 snapshot 中的方块类型是：

- `AIR`
- `CAVE_AIR`
- `VOID_AIR`

则直接跳过。

#### 匹配目标方块

如果 `targetMaterials` 不为空：

```text
只破坏 targetMaterials 中包含的 Material
```

如果 `targetMaterials` 为空：

```text
破坏所有非空气方块
```

当前插件默认是同种方块模式，因此通常只匹配一个 `Material`。

#### 特殊处理 waterlogged

当目标材料包含 `WATER` 时，插件会额外处理 waterlogged 方块：

1. 如果当前方块本身不是 `WATER`，但它的 `BlockData` 实现了 `Waterlogged`。
2. 且 `isWaterlogged() == true`。
3. 那么设置 `waterlogged = false`。
4. 不删除该方块本体。

也就是说，目标是水时，插件不只删除水源/流水，也会把含水楼梯、含水台阶等方块中的水移除。

#### 掉落半径

每个方块判断是否在掉落半径内：

```text
dx = abs(worldX - centerX)
dz = abs(worldZ - centerZ)
withinDropRadius = dx <= dropRadius && dz <= dropRadius
```

注意它只看 X/Z，不看 Y。

#### 删除方式

如果目标方块是流体：

- `WATER`
- `LAVA`
- `BUBBLE_COLUMN`

则直接静默删除。

如果不是流体，且在掉落半径内，且没有超过 `maxDrops`：

```java
block.breakNaturally();
totalDrops++;
```

否则：

```java
block.setType(Material.AIR, false);
```

这里的 `false` 非常关键：它表示不应用物理更新。这样不会让红石、沙子、水流、邻居方块更新全部连锁触发。

#### `maxDrops` 的真实语义

配置名叫 `max-drops`，但反编译看起来它实际限制的是“调用 `breakNaturally()` 的方块次数”，不是最终生成的 item entity 数量。

一个方块可能掉多个物品，也可能不掉物品。因此 NeoForge 复刻时可以保留这个语义，命名为 `maxNaturalBreakBlocks` 会更准确。

## 为什么这个插件相对不卡

### 1. 按 tick 分批

它不会在一次事件中完成所有工作，而是用定时任务分散到多个 tick。

### 2. 毫秒预算

`max-ms-per-tick` 会硬性限制每 tick 的处理时间。即使 `chunks-per-tick` 设置很高，只要耗时超过预算也会停。

### 3. 按 chunk 而不是按全世界无序扫描

半径被换算成 chunk 队列，每次处理队列中的一部分。

### 4. 优先处理真实存在的 chunk

通过 `.mca` header 判断 chunk 是否存在，减少无意义处理。

### 5. 使用 `ChunkSnapshot`

先用快照读取方块类型，减少真实世界对象访问。

### 6. 只对命中方块执行真实修改

非目标方块只读 snapshot，不调用真实 `Block`。

### 7. 远处不掉落

远离触发点的方块直接删掉，不生成 item entity。

### 8. 删除时禁用物理更新

`setType(AIR, false)` 避免大量邻居更新和物理连锁。

## 当前插件的注意点和潜在问题

### `blocks-per-chunk-tick` 没看到实际使用

配置文件有：

```yml
performance:
  blocks-per-chunk-tick: 3000
```

`PluginConfig` 也读取了这个值，但 `ChunkDestructionTask.destroyChunkBlocks` 中没有看到它控制循环。也就是说原插件的实际限流主要是：

- 每 tick 最大 chunk 数
- 每 tick 最大毫秒数

不是“每 tick 最大方块数”。

在 NeoForge 复刻时，建议真正实现 `blocks-per-chunk-tick`，否则单个高密度 chunk 仍然可能造成微卡。

### 同步 chunk 加载仍可能卡

`loaded-chunks-only = false` 时，原插件会同步调用 `loadChunk(x, z, true)`。如果目标区域包含大量未加载或未生成 chunk，会有明显风险。

NeoForge 版本建议优先做：

- 非生成式加载已存在 chunk
- 异步请求 chunk
- 未加载完成的 chunk 延后处理

不要在单个 server tick 中同步生成大量 chunk。

### region 文件扫描发生在任务启动阶段

`queueAllWorldChunks()` 会读取 `.mca` 文件头。半径很大、region 文件很多时，启动瞬间也可能有磁盘 IO 压力。

NeoForge 复刻时可以把 region header 扫描放到后台线程，然后把结果提交回服务器线程开始任务。但后台线程不能访问 `ServerLevel` 的可变世界状态。

### 权限和冷却配置疑似未生效

反编译结果中看到权限和冷却相关方法，但主破坏监听里没有看到调用。NeoForge 复刻时应明确实现：

- 谁能触发
- 冷却时间
- 是否允许任务并发
- 是否需要二次确认

## NeoForge 复刻目标

NeoForge 复刻不需要逐行照搬 Bukkit API，而要保留这些行为语义：

1. 玩家破坏方块后启动批量破坏任务。
2. 默认只破坏同种方块。
3. 任务以触发点为中心，处理指定半径内的 chunk。
4. 每 tick 按时间预算处理。
5. 世界修改只在逻辑服务端线程执行。
6. 可以异步准备 chunk 队列或请求 chunk，但不能异步 `setBlock`。
7. 远处方块静默删除。
8. 近处方块可自然掉落。
9. 静默删除不触发邻居更新。
10. 支持水和 waterlogged 的特殊处理。

## NeoForge 官方概念对照

以下是复刻时需要用到的 NeoForge / Minecraft 概念：

- 事件注册使用 `NeoForge.EVENT_BUS`。
- 方块破坏可监听 `BlockEvent.BreakEvent`。官方文档说明它在真正破坏阶段服务端触发。
- 每 tick 执行任务可监听 server tick 或 level tick 事件。具体事件类名会随 NeoForge 版本变化，建议按目标版本确认。
- 修改世界方块使用 `Level#setBlock(BlockPos, BlockState, int)`。
- `setBlock` 的第三个参数是 update flags。不要使用会触发大量邻居更新的 flag。
- 不要在客户端逻辑或后台线程修改 `ServerLevel`。

参考文档：

- NeoForge Events: https://docs.neoforged.net/docs/1.21.4/concepts/events/
- NeoForge Block breaking pipeline: https://docs.neoforged.net/docs/1.21.3/blocks/#breaking-a-block
- NeoForge `Level#setBlock` update flags: https://docs.neoforged.net/docs/1.21.4/blocks/states/#levelsetblock

## 推荐 NeoForge 类设计

建议拆成这些类：

```text
com.example.sameblockbreak
  SameBlockBreakMod
  config
    ModConfig
  event
    BreakEventHandler
    ServerTickHandler
  destruction
    DestructionManager
    DestructionTask
    ChunkCursor
    ChunkQueueBuilder
    RegionFileScanner
    TargetMatcher
    DropPolicy
    DestructionStats
```

### `ModConfig`

配置字段建议：

```java
public final class ModConfig {
    public int radius = 3000;
    public boolean matchBlockType = true;

    public int dropRadius = 50;
    public int maxNaturalBreakBlocks = -1;

    public int chunksPerTick = 200;
    public int maxMsPerTick = 10;
    public int blocksPerTick = 20000;
    public int blocksPerChunkStep = 3000;
    public int preloadDistance = 50;

    public boolean loadedChunksOnly = false;
    public boolean allowChunkGeneration = false;
    public boolean allowDuringDestruction = true;

    public int cooldownSeconds = 0;
    public boolean requireConfirmation = false;
}
```

建议 NeoForge 版本把 `allowChunkGeneration` 独立出来，不要直接复刻 Bukkit 的 `loadChunk(..., true)`。如果要保护服务器，默认不要生成新区块。

### `DestructionManager`

职责：

- 保存所有活跃任务。
- 接收事件启动任务。
- 每 tick 调用任务。
- 停止任务。
- 查询进度。
- 存储玩家自定义 drop radius / max drops。

推荐数据结构：

```java
public final class DestructionManager {
    private final Map<ResourceKey<Level>, List<DestructionTask>> activeTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerDropRadius = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerMaxDrops = new ConcurrentHashMap<>();

    public boolean start(ServerLevel level, @Nullable ServerPlayer player, TargetMatcher target, BlockPos center) {
        // create task, add to activeTasks, start it
    }

    public void tick(MinecraftServer server) {
        // iterate active tasks and call task.tick()
        // remove completed/cancelled tasks
    }
}
```

### `DestructionTask`

推荐字段：

```java
public final class DestructionTask {
    private final ResourceKey<Level> dimension;
    private final UUID initiatorId;
    private final TargetMatcher target;
    private final BlockPos center;
    private final int radius;
    private final int dropRadius;
    private final int maxNaturalBreakBlocks;

    private final LongList chunkQueue;
    private int chunkIndex;

    private ChunkCursor currentChunkCursor;

    private long blocksScanned;
    private long blocksDestroyed;
    private long naturalBreaks;
    private int processedChunks;
    private int skippedChunks;
    private int emptyChunks;

    private boolean cancelled;
    private boolean complete;
    private final long startMillis;
}
```

`LongList` 可以用 fastutil，也可以用普通 `LongArrayList`。如果不想引入依赖，用 `LongArrayList` 需要确认项目已有 fastutil；Minecraft 本身通常带 fastutil，但 mod 代码中仍要按目标环境确认。

## NeoForge 事件处理

### 监听方块破坏

推荐监听 `BlockEvent.BreakEvent`。事件发生在服务端破坏流程中，并能拿到被破坏前的 `BlockState`。

伪代码：

```java
@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class BreakEventHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }

        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockState brokenState = event.getState();
        if (brokenState.isAir()) {
            return;
        }

        if (!PermissionRules.canTrigger(player)) {
            return;
        }

        if (Cooldowns.isCoolingDown(player)) {
            return;
        }

        TargetMatcher target = ModConfig.matchBlockType
            ? TargetMatcher.sameBlock(brokenState.getBlock())
            : TargetMatcher.allNonAir();

        DestructionManager.INSTANCE.start(level, player, target, event.getPos());
    }
}
```

注意：

- 不建议取消原始破坏事件。原方块应该由正常游戏流程破坏。
- 使用较低优先级可以让其他 mod 先取消或调整事件。
- 如果目标版本事件 API 名字不同，让 AI 按目标 NeoForge 版本替换类名和 getter。

### 每 tick 推进任务

伪代码：

```java
@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ServerTickHandler {

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        DestructionManager.INSTANCE.tick(event.getServer());
    }
}
```

如果目标版本没有 `ServerTickEvent.Post`，使用等价的 server tick 或 level tick post 事件。关键要求是：在逻辑服务端线程运行。

## NeoForge chunk 队列设计

### 半径换算

使用方形半径，与 Bukkit 原插件一致：

```java
int centerChunkX = SectionPos.blockToSectionCoord(center.getX());
int centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());

int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
```

不要用：

```java
center.getX() / 16
```

因为负坐标会错。

### chunk key

推荐使用 Minecraft 自带的 `ChunkPos.asLong(x, z)`：

```java
long key = ChunkPos.asLong(chunkX, chunkZ);
int chunkX = ChunkPos.getX(key);
int chunkZ = ChunkPos.getZ(key);
```

如果目标版本没有这些静态方法，则使用 `new ChunkPos(key)` 或自己打包：

```java
long key = ((long) chunkX & 0xffffffffL) | (((long) chunkZ & 0xffffffffL) << 32);
```

### region 文件扫描

原插件通过 `.mca` header 优先确认已存在 chunk。NeoForge 可以复刻，但维度目录路径要小心：

- 主世界通常是 `<world>/region`
- 下界通常是 `<world>/DIM-1/region`
- 末地通常是 `<world>/DIM1/region`
- 自定义维度可能在 `<world>/dimensions/<namespace>/<path>/region`

如果不想依赖路径细节，可以先实现无 region 扫描版本：直接把半径内所有 chunk 加入队列，再通过非生成式 chunk load 判断是否存在。性能会差一些，但更稳。

如果实现 region 扫描，逻辑如下：

```java
Set<Long> existing = new HashSet<>();

for each regionFile in regionDir matching "r.*.*.mca":
    parse regionX and regionZ from file name
    read first 4096 bytes

    for localX in 0..31:
        for localZ in 0..31:
            int index = 4 * (localX + localZ * 32);
            int offset = ((header[index] & 255) << 16)
                       | ((header[index + 1] & 255) << 8)
                       |  (header[index + 2] & 255);
            int sectors = header[index + 3] & 255;

            if (offset != 0 && sectors != 0):
                int chunkX = regionX * 32 + localX;
                int chunkZ = regionZ * 32 + localZ;
                existing.add(ChunkPos.asLong(chunkX, chunkZ));
```

然后构建队列：

```java
List<Long> existingInRadius = new ArrayList<>();
List<Long> possibleInRadius = new ArrayList<>();

for chunkX in minChunkX..maxChunkX:
    for chunkZ in minChunkZ..maxChunkZ:
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (existing.contains(key)) existingInRadius.add(key);
        else possibleInRadius.add(key);

Comparator<Long> byDistance = Comparator.comparingLong(key -> {
    int dx = ChunkPos.getX(key) - centerChunkX;
    int dz = ChunkPos.getZ(key) - centerChunkZ;
    return (long) dx * dx + (long) dz * dz;
});

existingInRadius.sort(byDistance);
possibleInRadius.sort(byDistance);

chunkQueue.addAll(existingInRadius);
chunkQueue.addAll(possibleInRadius);
```

## NeoForge chunk 加载策略

原 Bukkit 插件会在需要时同步加载甚至生成 chunk。NeoForge 复刻建议分成三个模式：

### 模式 A：只处理已加载 chunk

最安全，性能最好，但不完整：

```java
if (!level.hasChunk(chunkX, chunkZ)) {
    skippedChunks++;
    return ChunkResult.SKIPPED;
}
```

适合服务器稳定优先。

### 模式 B：只加载已存在 chunk，不生成

推荐默认模式。目标是覆盖磁盘上已有 chunk，但不生成新地形。

伪代码：

```java
ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
if (!(chunk instanceof LevelChunk levelChunk)) {
    requestAsyncLoadIfAvailable(chunkX, chunkZ);
    return ChunkResult.DEFERRED;
}
```

具体 API 名会随 Minecraft / NeoForge 版本变化。要求 AI 按目标版本确认：

- 如何检查 chunk 是否已经加载
- 如何非生成式获取 chunk
- 如何异步请求 chunk
- 如何在 future 完成后回到 server thread

### 模式 C：允许生成 chunk

最接近原插件的 `loadChunk(x, z, true)`，但风险最高。

只建议在明确需要“半径内未生成区块也算世界”的情况下打开：

```java
if (config.allowChunkGeneration) {
    // request or load chunk with generation allowed
}
```

大半径下不要默认开启。

## NeoForge 方块扫描策略

NeoForge 没有 Bukkit `ChunkSnapshot` 的完全等价物。可以用两种策略。

### 策略 A：简单安全版

使用 `ServerLevel#getBlockState(pos)` 读取方块，每 tick 严格限制方块数量和耗时。

优点：

- 实现简单。
- API 稳定。
- 不依赖内部 chunk section 数据结构。

缺点：

- 比 `ChunkSnapshot` / section palette 扫描慢。

伪代码：

```java
while (cursor.hasMore() && blocksThisTick < config.blocksPerTick && now < deadline) {
    BlockPos pos = cursor.nextPos();
    BlockState state = level.getBlockState(pos);

    if (state.isAir()) {
        continue;
    }

    if (!target.matches(state)) {
        maybeRemoveWaterloggedWater(level, pos, state, target);
        continue;
    }

    destroyMatchedBlock(level, pos, state, player);
}
```

这个版本一定要实现 `ChunkCursor`，不要一次性扫完整个 chunk。

### 策略 B：高性能 section 版

使用 `LevelChunk` 的 section 数据结构，按 section 跳过不可能包含目标方块的区域。

目标思路：

1. 获取 `LevelChunk`。
2. 遍历 chunk section。
3. 如果 section 全空气，跳过。
4. 如果目标是某个 block，使用类似 `section.maybeHas(state -> state.is(targetBlock))` 的 API 跳过不包含目标的 section。
5. 对可能命中的 section 扫描 `16 x 16 x 16`。
6. 命中后再调用 `level.setBlock` 或 `level.destroyBlock`。

伪代码：

```java
for each LevelChunkSection section in chunk.getSections():
    if (section.hasOnlyAir()) {
        continue;
    }

    if (target.isSpecificBlock() && !section.maybeHas(state -> target.matches(state))) {
        continue;
    }

    for localY in 15 downTo 0:
        for localX in 0..15:
            for localZ in 0..15:
                BlockState state = section.getBlockState(localX, localY, localZ);
                ...
```

该方案最接近 Bukkit 插件“先轻量读，命中再修改”的思想。但 Minecraft 内部类和方法名容易随版本变化，所以要让 AI 按目标 NeoForge 版本确认。

## `ChunkCursor`：实现真正的 `blocks-per-chunk-tick`

原插件没有真正用上 `blocks-per-chunk-tick`。NeoForge 复刻建议补上。

`ChunkCursor` 保存当前 chunk 的扫描进度：

```java
public final class ChunkCursor {
    public final int chunkX;
    public final int chunkZ;

    private int localX;
    private int localZ;
    private int y;
    private boolean initializedColumn;

    public BlockPos.MutableBlockPos nextPos(ServerLevel level) {
        // Return next position in this chunk.
        // Preserve progress across ticks.
    }
}
```

扫描顺序复刻原插件：

1. localX 从 0 到 15
2. localZ 从 0 到 15
3. 每列从 `heightmap` 最高 Y 往 `minBuildHeight` 扫

也可以改成 section 扫描，但要保证每 tick 可暂停、下 tick 可继续。

每 tick 主循环应同时限制：

```text
maxMsPerTick
chunksPerTick
blocksPerTick
blocksPerChunkStep
```

推荐逻辑：

```java
public TickResult tick(ServerLevel level, MinecraftServer server) {
    long start = System.nanoTime();
    long deadline = start + config.maxMsPerTick * 1_000_000L;

    int chunksThisTick = 0;
    int blocksThisTick = 0;

    while (!complete && chunksThisTick < config.chunksPerTick) {
        if (System.nanoTime() >= deadline) {
            return TickResult.RUNNING;
        }
        if (blocksThisTick >= config.blocksPerTick) {
            return TickResult.RUNNING;
        }

        if (currentChunkCursor == null) {
            if (!advanceToNextChunk(level)) {
                complete = true;
                return TickResult.COMPLETE;
            }
            chunksThisTick++;
        }

        int before = blocksThisTick;
        processCurrentChunkStep(level, deadline, config.blocksPerChunkStep);
        blocksThisTick += (blocksScannedThisStep);

        if (currentChunkCursor.isComplete()) {
            finishCurrentChunk();
            currentChunkCursor = null;
        }
    }

    return TickResult.RUNNING;
}
```

## Target matching in NeoForge

Bukkit 的 `Material` 更像“大类型”。NeoForge 中建议用 `Block` 匹配，而不是完整 `BlockState` 匹配，否则朝向、含水、年龄等状态会导致“同种方块”匹配失败。

### 普通方块

```java
public boolean matches(BlockState state) {
    return !state.isAir() && state.is(targetBlock);
}
```

### 破坏所有方块

```java
public boolean matches(BlockState state) {
    return !state.isAir();
}
```

### 水和 lava

水比较特殊。原 Bukkit 插件用 `Material.WATER`，还处理 waterlogged。NeoForge 可以这样抽象：

```java
public boolean matches(BlockState state) {
    if (targetBlock == Blocks.WATER) {
        return state.is(Blocks.WATER);
    }
    if (targetBlock == Blocks.LAVA) {
        return state.is(Blocks.LAVA);
    }
    return state.is(targetBlock);
}

public boolean shouldRemoveWaterlogged(BlockState state) {
    return targetBlock == Blocks.WATER
        && state.hasProperty(BlockStateProperties.WATERLOGGED)
        && state.getValue(BlockStateProperties.WATERLOGGED);
}
```

处理 waterlogged：

```java
if (target.shouldRemoveWaterlogged(state)) {
    BlockState dry = state.setValue(BlockStateProperties.WATERLOGGED, false);
    level.setBlock(pos, dry, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    blocksDestroyed++;
    return;
}
```

## NeoForge 删除方块策略

### 静默删除

对应 Bukkit：

```java
block.setType(Material.AIR, false);
```

NeoForge 推荐：

```java
int flags = Block.UPDATE_CLIENTS
          | Block.UPDATE_SUPPRESS_DROPS
          | Block.UPDATE_KNOWN_SHAPE;

level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
```

关键点：

- 包含 `UPDATE_CLIENTS`，让客户端看到方块消失。
- 包含 `UPDATE_SUPPRESS_DROPS`，避免掉落。
- 不包含 `UPDATE_NEIGHBORS`，避免邻居更新风暴。
- 可包含 `UPDATE_KNOWN_SHAPE`，减少形状更新递归。

不要用：

```java
level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
```

因为它等价于更强的更新，会触发邻居更新，性能风险更大。

### 自然破坏并掉落

对应 Bukkit：

```java
block.breakNaturally();
```

NeoForge 可用：

```java
level.destroyBlock(pos, true, player);
```

具体签名按目标 Minecraft 版本确认。有的版本可能需要更多参数。

触发自然掉落前要检查：

```java
boolean isFluid = state.is(Blocks.WATER)
               || state.is(Blocks.LAVA)
               || state.is(Blocks.BUBBLE_COLUMN);

boolean withinDropRadius =
    Math.abs(pos.getX() - center.getX()) <= dropRadius
 && Math.abs(pos.getZ() - center.getZ()) <= dropRadius;

boolean mayDrop =
    !isFluid
 && withinDropRadius
 && (maxNaturalBreakBlocks < 0 || naturalBreaks < maxNaturalBreakBlocks);
```

如果 `mayDrop`：

```java
level.destroyBlock(pos, true, player);
naturalBreaks++;
blocksDestroyed++;
```

否则：

```java
silentRemove(level, pos);
blocksDestroyed++;
```

## 线程规则

必须遵守：

- `ServerLevel#getBlockState`
- `ServerLevel#setBlock`
- `ServerLevel#destroyBlock`
- entity 生成
- 掉落物生成
- boss bar / player message

这些都必须在服务端线程执行。

允许后台线程做：

- 读取 `.mca` header。
- 构建 chunk key 列表。
- 排序纯数据。

后台线程完成后必须回到服务器线程：

```java
server.execute(() -> {
    DestructionManager.INSTANCE.attachPreparedQueue(...);
});
```

不要在 `CompletableFuture` 的异步回调里直接修改世界。

## 进度统计

原插件进度按 chunk 算：

```text
progress = processedChunks / totalChunks
```

NeoForge 如果实现 chunk 内部分步扫描，可以仍按 chunk 算，也可以增加 block 级进度：

```java
public record DestructionStats(
    int totalChunks,
    int processedChunks,
    long blocksScanned,
    long blocksDestroyed,
    long naturalBreaks,
    long startMillis,
    double chunkProgress
) {
    public long elapsedMillis() {
        return System.currentTimeMillis() - startMillis;
    }

    public double chunksPerSecond() {
        long elapsed = elapsedMillis();
        return elapsed <= 0 ? 0 : processedChunks * 1000.0 / elapsed;
    }
}
```

## 推荐配置值

开发测试：

```toml
radius = 64
chunksPerTick = 20
maxMsPerTick = 5
blocksPerTick = 8000
blocksPerChunkStep = 1500
dropRadius = 16
maxNaturalBreakBlocks = 128
loadedChunksOnly = true
allowChunkGeneration = false
```

录制或单人服务器：

```toml
radius = 3000
chunksPerTick = 200
maxMsPerTick = 10
blocksPerTick = 30000
blocksPerChunkStep = 3000
dropRadius = 50
maxNaturalBreakBlocks = 1000
loadedChunksOnly = false
allowChunkGeneration = false
```

极限复刻原插件：

```toml
radius = 3000
chunksPerTick = 200
maxMsPerTick = 15
blocksPerTick = 100000
blocksPerChunkStep = 65536
dropRadius = 50
maxNaturalBreakBlocks = -1
loadedChunksOnly = false
allowChunkGeneration = true
```

不建议在公共服务器使用极限配置。

## NeoForge 复刻伪代码总览

### 启动任务

```java
public boolean start(ServerLevel level, ServerPlayer player, TargetMatcher target, BlockPos center) {
    ModConfig cfg = ConfigHolder.get();

    if (cfg.blacklistedDimensions.contains(level.dimension().location())) {
        return false;
    }

    if (!cfg.allowDuringDestruction && hasActiveTask(level.dimension())) {
        return false;
    }

    CompletableFuture
        .supplyAsync(() -> ChunkQueueBuilder.build(level, center, cfg.radius, cfg.useRegionScan))
        .thenAccept(queue -> level.getServer().execute(() -> {
            DestructionTask task = new DestructionTask(level.dimension(), player.getUUID(), target, center, queue, cfg);
            activeTasks.computeIfAbsent(level.dimension(), k -> new CopyOnWriteArrayList<>()).add(task);
        }));

    return true;
}
```

如果不想异步扫描 region，直接同步构建队列也可以，但半径大时启动瞬间可能卡。

### 每 tick 推进

```java
public void tick(MinecraftServer server) {
    for (var entry : activeTasks.entrySet()) {
        ServerLevel level = server.getLevel(entry.getKey());
        if (level == null) {
            cancelAll(entry.getValue());
            continue;
        }

        for (DestructionTask task : entry.getValue()) {
            TickResult result = task.tick(level, server);
            if (result.isFinished()) {
                task.cleanup(server);
                entry.getValue().remove(task);
            }
        }
    }
}
```

### 处理 chunk

```java
private boolean advanceToNextChunk(ServerLevel level) {
    while (chunkIndex < chunkQueue.size()) {
        long key = chunkQueue.getLong(chunkIndex++);
        int chunkX = ChunkPos.getX(key);
        int chunkZ = ChunkPos.getZ(key);

        LevelChunk chunk = tryGetChunk(level, chunkX, chunkZ);

        if (chunk == null) {
            if (config.loadedChunksOnly) {
                skippedChunks++;
                processedChunks++;
                continue;
            }

            requestChunkAsync(level, chunkX, chunkZ);
            // Either defer this chunk, or put it at the end of the queue.
            deferredChunks.add(key);
            continue;
        }

        currentChunkCursor = new ChunkCursor(chunkX, chunkZ);
        return true;
    }

    if (!deferredChunks.isEmpty()) {
        moveDeferredChunksBackToQueue();
        return advanceToNextChunk(level);
    }

    return false;
}
```

### 删除命中方块

```java
private void processBlock(ServerLevel level, BlockPos.MutableBlockPos pos, BlockState state, ServerPlayer player) {
    if (state.isAir()) {
        return;
    }

    if (!target.matches(state)) {
        if (target.shouldRemoveWaterlogged(state)) {
            BlockState dry = state.setValue(BlockStateProperties.WATERLOGGED, false);
            level.setBlock(pos, dry, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            blocksDestroyed++;
        }
        return;
    }

    boolean fluid = state.is(Blocks.WATER)
        || state.is(Blocks.LAVA)
        || state.is(Blocks.BUBBLE_COLUMN);

    boolean withinDropRadius =
        Math.abs(pos.getX() - center.getX()) <= dropRadius
     && Math.abs(pos.getZ() - center.getZ()) <= dropRadius;

    boolean natural =
        !fluid
     && withinDropRadius
     && (maxNaturalBreakBlocks < 0 || naturalBreaks < maxNaturalBreakBlocks);

    if (natural) {
        level.destroyBlock(pos, true, player);
        naturalBreaks++;
    } else {
        level.setBlock(
            pos,
            Blocks.AIR.defaultBlockState(),
            Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE
        );
    }

    blocksDestroyed++;
}
```

## 测试清单

### 基础功能

- 破坏石头，只删除半径内其他石头。
- 破坏泥土，只删除半径内其他泥土。
- `matchBlockType = false` 时删除所有非空气方块。
- 负坐标下 chunk 范围正确。
- 下界、末地、自定义维度路径正确。

### 性能

- 半径 64，确认不会卡顿。
- 半径 512，观察 TPS。
- 半径 3000，只处理已加载 chunk。
- 半径 3000，处理已生成但未加载 chunk。
- 大量未生成 chunk 时确认不会同步生成造成长卡顿。

### 掉落

- 掉落半径内自然掉落。
- 掉落半径外不掉落。
- `maxNaturalBreakBlocks = 0` 时不掉落。
- `maxNaturalBreakBlocks = -1` 时不限自然破坏次数。
- 流体不掉落。

### 物理更新

- 删除沙子时不应导致大规模下落风暴。
- 删除红石旁边方块时不应产生大规模邻居更新。
- 删除水或岩浆时不应触发巨量流体更新。

### 水和含水方块

- 破坏水时删除水方块。
- 破坏水时移除含水楼梯/台阶中的 waterlogged 状态。
- 不应删除含水方块本体。

### 并发与取消

- 同一世界多个任务是否允许由配置控制。
- `/stop` 或等价命令能取消任务。
- 服务器关闭时取消所有任务。
- 玩家离线时任务仍能继续或按配置停止。

## 给 AI 的实现提示词

如果要让另一个 AI 在 NeoForge 中实现，可直接给它下面这段要求。

```text
请在 NeoForge 上实现一个服务端 mod：玩家破坏一个方块后，在指定半径内分批破坏所有同种方块。行为参考 Bukkit 插件 CarvsBlockBreak，但不要逐字照搬 Bukkit API。

必须实现：
1. 监听服务端 BlockEvent.BreakEvent，读取被破坏前的 BlockState 和 BlockPos。
2. 默认按 Block 类型匹配同种方块，不按完整 BlockState 匹配。
3. 以触发点为中心，将 radius 转成 chunk 范围，使用 floor chunk 坐标换算，不能用普通 /16。
4. 生成 chunk 队列，优先处理离触发点近的 chunk。
5. 可选：扫描 region/*.mca header，将磁盘上存在的 chunk 排在前面。
6. 每 server tick 推进任务，使用 System.nanoTime 做 maxMsPerTick 预算。
7. 同时限制 chunksPerTick、blocksPerTick、blocksPerChunkStep。
8. 不允许在后台线程调用 ServerLevel#setBlock、destroyBlock、getBlockState 等世界修改或读取逻辑；所有世界访问都在服务端线程。
9. 可以后台线程读取 .mca header 和排序纯数据，完成后用 server.execute 回到服务器线程。
10. 静默删除使用 Level#setBlock(pos, Blocks.AIR.defaultBlockState(), flags)，flags 包含 UPDATE_CLIENTS、UPDATE_SUPPRESS_DROPS、UPDATE_KNOWN_SHAPE，不包含 UPDATE_NEIGHBORS。
11. 掉落半径内使用 level.destroyBlock(pos, true, player) 或目标版本等价 API；掉落半径外静默删除。
12. maxNaturalBreakBlocks 限制自然破坏次数，不是精确 item entity 数。
13. 水、岩浆、气泡柱作为流体直接静默删除。
14. 如果目标是水，含 WATERLOGGED 属性且为 true 的方块应改成 WATERLOGGED=false，而不是删除方块本体。
15. 支持 loadedChunksOnly 和 allowChunkGeneration 两个独立配置。默认不要生成新区块。
16. 提供任务进度、取消、服务器关闭清理。

不要做：
1. 不要在一次事件里扫描完整世界或完整大半径。
2. 不要用 setBlockAndUpdate 删除大量方块。
3. 不要包含 UPDATE_NEIGHBORS 作为静默删除 flag。
4. 不要从 CompletableFuture 后台线程修改世界。
5. 不要默认生成大量未生成 chunk。
```

## 和原 Bukkit 插件的关键差异建议

NeoForge 复刻时建议保留原插件核心性能思想，但做这些改进：

1. 真正实现 `blocks-per-chunk-tick`。
2. 默认不生成新区块。
3. chunk 未加载时优先延后，而不是同步卡主线程。
4. region header 扫描尽量放后台线程。
5. 权限、冷却、确认逻辑明确实现。
6. `maxDrops` 改名为 `maxNaturalBreakBlocks`，避免语义误导。
7. 对公共服务器提供更保守的默认值。

## 最重要的复刻原则

这个插件高效的本质不是某个单独 API，而是这些原则：

```text
少量多次
+ 主线程限时
+ chunk 队列
+ 命中前轻量读取
+ 命中后才真实修改
+ 远处不掉落
+ 静默删除不触发邻居更新
+ chunk 加载不要阻塞主线程太久
```

NeoForge 实现只要守住这些原则，即使具体 API 和 Bukkit 不同，也能复刻出相同的性能特征。