# CarvsBlockBreak Logic Notes and NeoForge Porting Guide

This document explains how the current Bukkit/Paper/Spigot plugin achieves “after breaking one block, batch-break blocks of the same type in the world,” and provides a detailed design for reproducing the logic on NeoForge.

The current directory appears to be the unpacked result of the plugin jar. It only contains `plugin.yml`, `config.yml`, and `.class` files, with no Java source code. Therefore, the Bukkit logic below comes from decompilation analysis of the `.class` files. The key classes are:

- `com.carvs.blockbreak.listeners.BlockBreakListener`
- `com.carvs.blockbreak.queue.DestructionManager`
- `com.carvs.blockbreak.queue.ChunkDestructionTask`
- `com.carvs.blockbreak.config.PluginConfig`

## Target Behavior

When a player breaks a block, the plugin records that block’s type and coordinates, then searches within a specified radius for blocks of the same type and removes them.

Default configuration:

- `destruction.radius: 3000`
- `destruction.match-block-type: true`
- `destruction.default-drop-radius: 50`
- `performance.chunks-per-tick: 200`
- `performance.max-ms-per-tick: 15`
- `performance.tick-delay: 1`
- `performance.loaded-chunks-only: false`

In other words, by default it processes blocks of the same type within a square area of 3000 blocks around the trigger point. The actual horizontal coverage is a `6000 x 6000` area.

## One-Sentence Summary of the Performance Strategy

It does not scan and delete all blocks in the world at once. Instead, it:

1. Splits the target area into a chunk queue.
2. Processes only a small batch of chunks each tick.
3. Enforces a millisecond budget per tick.
4. Uses lightweight snapshots to read block types inside each chunk.
5. Only obtains real `Block` objects for blocks that actually match.
6. Silently removes blocks far away from the player without dropping items.
7. Disables physics updates during silent removal to avoid chain block updates.

Together, these points are why the plugin is relatively resistant to lag.

## Bukkit Plugin Trigger Flow

### 1. `BlockBreakListener.onBlockBreak`

When a player breaks a block:

1. Read the player, world, and configuration.
2. Check the world blacklist.
3. If the current world already has a destruction task and concurrent tasks are not allowed by configuration, reject the trigger.
4. If confirmation is required, require the player to break a block again within 5 seconds.
5. Read the broken block type:
   - `brokenBlockType = event.getBlock().getType()`
6. Read the broken block location:
   - `blockLocation = event.getBlock().getLocation()`
7. If `match-block-type = true`, the target type is this `Material`.
8. Call:
   - `DestructionManager.startDestruction(world, player, targetMaterial, blockLocation)`

Note: The decompiled result shows that the class contains `isPlayerAuthorized`, but it does not appear to be called inside `onBlockBreak`. Therefore, `allowed-players` and permission-related configuration may not actually restrict who can trigger the plugin. The configuration also contains cooldown-related fields and methods, but no actual cooldown check was observed in the main block break event.

### 2. `DestructionManager.startDestruction`

The manager is responsible for creating and storing tasks.

Core behavior:

1. Read this run’s drop radius by player.
2. Read this run’s maximum drop count by player.
3. Create a `ChunkDestructionTask`.
4. Add the task to `activeTasks`:
   - key: world name
   - value: list of currently running tasks in that world
5. Call the task’s `start()`.
6. Remove the task from `activeTasks` when it finishes.

It supports multiple concurrent tasks in the same world because the value of `activeTasks` is a task list, not a single task.

## Complete Workflow of `ChunkDestructionTask`

This is the core class. It is responsible for:

- Generating the chunk queue
- Scheduling work by tick
- Controlling the maximum time spent per tick
- Loading or skipping chunks
- Scanning blocks inside each chunk
- Destroying target blocks
- Tracking progress
- Updating the action bar / boss bar / console log

### 1. Task Initialization

The task stores these key fields:

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

When `targetMaterials` is null or empty, it means all non-air blocks should be destroyed. In normal same-block destruction mode, it contains only one `Material`.

### 2. `start()`

When the task starts:

1. Record `startTime`.
2. Call `queueAllWorldChunks()` to generate the chunk queue.
3. If there are no chunks, finish immediately.
4. Play the start sound and create the boss bar.
5. Log the task target, total chunk count, radius, and world name.
6. Create a `BukkitRunnable`:
   - delay = 0
   - period = `config.tickDelay`
7. On each tick:
   - If cancelled: clean up and stop the task.
   - Call `processChunks()`.
   - Call `preloadChunks()`.
   - If `chunkIndex >= chunkList.size()`, call `complete()` and stop the task.

### 3. `queueAllWorldChunks()`

This method queues chunks within the radius.

#### Coordinate Conversion

It first converts the center block coordinates to chunk coordinates:

```text
centerChunkX = centerBlockX >> 4
centerChunkZ = centerBlockZ >> 4
```

Then it converts the destruction radius into a chunk range:

```text
minChunkX = (centerBlockX - radius) >> 4
maxChunkX = (centerBlockX + radius) >> 4
minChunkZ = (centerBlockZ - radius) >> 4
maxChunkZ = (centerBlockZ + radius) >> 4
```

Note: It uses right shift `>> 4`, which is equivalent to floor division by 16 for chunk coordinate conversion. When reproducing this in NeoForge, do not use normal `/ 16`, because negative coordinates will be wrong. Use `SectionPos.blockToSectionCoord(blockX)` or `Math.floorDiv(blockX, 16)` instead.

#### Scanning `.mca` Region Files

The plugin reads:

```text
<world>/region/*.mca
```

Each `.mca` file name is similar to:

```text
r.<regionX>.<regionZ>.mca
```

A region contains `32 x 32` chunks. The plugin reads the first 4096 bytes of the `.mca` file as the location table:

```text
entryIndex = 4 * (localChunkX + localChunkZ * 32)
offset = header[entryIndex] << 16 | header[entryIndex + 1] << 8 | header[entryIndex + 2]
sectorCount = header[entryIndex + 3]
```

If:

```text
offset != 0 && sectorCount != 0
```

then the chunk is considered to actually exist on disk.

If reading the `.mca` file fails, the plugin conservatively treats all 1024 chunks in that region as existing.

#### Prioritizing Existing Chunks

The plugin divides chunks within the radius into two groups:

1. `existingChunks`
   - chunks confirmed to exist by the `.mca` header
2. `possibleChunks`
   - chunks within the radius but not confirmed by the `.mca` header

Both groups are sorted separately by squared distance from the center chunk:

```text
distance = (chunkX - centerChunkX)^2 + (chunkZ - centerChunkZ)^2
```

Final queue order:

```text
chunkList = existingChunks sorted by distance
chunkList += possibleChunks sorted by distance
```

This way, nearby chunks are processed first, and chunks that have already been generated are prioritized.

### 4. `processChunks()`

This is the main per-tick processing function.

Core throttling logic:

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

This means `chunks-per-tick` is only a safety upper bound; the real anti-lag control is `max-ms-per-tick`.

The current configuration allows at most 15 ms per tick. At 20 TPS, a Minecraft server tick is about 50 ms, so theoretically some main-thread time is left for other logic.

#### Chunk Loading Strategy

Before processing each chunk:

1. Check whether it is loaded:
   - `world.isChunkLoaded(x, z)`
2. If it is not loaded and `loaded-chunks-only = true`:
   - skip the chunk
   - `skippedChunks++`
   - `processedChunks++`
3. If it is not loaded and `loaded-chunks-only = false`:
   - call `world.loadChunk(x, z, true)`
   - then call `world.getChunkAt(x, z)`

Risk: `loadChunk(x, z, true)` may cause synchronous loading or even chunk generation. With a large radius, if many chunks are not loaded, this can still cause lag.

### 5. `preloadChunks()`

After each tick’s processing, the plugin requests the next 50 chunks in advance:

```text
start = current chunkIndex
end = min(start + 50, chunkList.size)
for i in start..end:
    if chunk not loaded:
        world.getChunkAtAsync(x, z)
```

This is used to reduce the chance of synchronous chunk loading in later ticks.

Note: It only preloads chunks. It does not modify blocks asynchronously. In Bukkit/Paper, actual world modification must still be performed on the main thread.

### 6. `destroyChunkBlocks(chunk)`

This method performs the actual block removal inside each chunk.

#### Reading the Snapshot

The plugin first obtains a `ChunkSnapshot`:

```java
ChunkSnapshot snapshot = chunk.getChunkSnapshot();
```

Most block reads are then performed from the snapshot:

```java
Material blockType = snapshot.getBlockType(localX, y, localZ);
```

This avoids calling real world objects for every coordinate. Only when a block matches does it call:

```java
Block block = chunk.getBlock(localX, y, localZ);
```

This is an important optimization.

#### Scan Order

It iterates over the `16 x 16` horizontal columns inside the chunk:

```text
for localX in 0..15:
  for localZ in 0..15:
```

For each column, it first finds the highest block:

```java
highestY = world.getHighestBlockYAt(worldX, worldZ, HeightMap.WORLD_SURFACE);
```

Then it scans downward from `highestY` to `world.getMinHeight()`:

```text
for y = highestY down to minY:
```

This avoids scanning air above the world surface.

#### Skipping Air

If the block type in the snapshot is:

- `AIR`
- `CAVE_AIR`
- `VOID_AIR`

it is skipped directly.

#### Matching Target Blocks

If `targetMaterials` is not empty:

```text
Only destroy Materials contained in targetMaterials
```

If `targetMaterials` is empty:

```text
Destroy all non-air blocks
```

The current plugin defaults to same-block mode, so normally only one `Material` is matched.

#### Special Handling for Waterlogged Blocks

When the target material contains `WATER`, the plugin additionally handles waterlogged blocks:

1. If the current block itself is not `WATER`, but its `BlockData` implements `Waterlogged`.
2. And `isWaterlogged() == true`.
3. Then set `waterlogged = false`.
4. Do not delete the block itself.

In other words, when the target is water, the plugin not only deletes water source/flowing water blocks, but also removes water from waterlogged stairs, slabs, and similar blocks.

#### Drop Radius

For each block, it checks whether it is within the drop radius:

```text
dx = abs(worldX - centerX)
dz = abs(worldZ - centerZ)
withinDropRadius = dx <= dropRadius && dz <= dropRadius
```

Note that it only considers X/Z, not Y.

#### Removal Method

If the target block is a fluid:

- `WATER`
- `LAVA`
- `BUBBLE_COLUMN`

it is directly removed silently.

If it is not a fluid, is within the drop radius, and has not exceeded `maxDrops`:

```java
block.breakNaturally();
totalDrops++;
```

Otherwise:

```java
block.setType(Material.AIR, false);
```

The `false` is crucial: it means physics updates are not applied. This prevents redstone, sand, fluids, neighboring blocks, etc. from triggering large chain updates.

#### Real Meaning of `maxDrops`

The configuration name is `max-drops`, but based on the decompiled code, it appears to actually limit the number of blocks for which `breakNaturally()` is called, not the final number of item entities spawned.

One block may drop multiple items, or may drop nothing. Therefore, when reproducing this in NeoForge, it is reasonable to preserve this semantic. Naming it `maxNaturalBreakBlocks` would be more accurate.

## Why This Plugin Is Relatively Lag-Resistant

### 1. Batched by Tick

It does not complete all work inside a single event. Instead, it spreads the work across multiple ticks using scheduled tasks.

### 2. Millisecond Budget

`max-ms-per-tick` hard-limits the processing time per tick. Even if `chunks-per-tick` is set very high, processing stops once the time budget is exceeded.

### 3. Chunk-Based Rather Than Unordered Whole-World Scanning

The radius is converted into a chunk queue, and each tick processes part of that queue.

### 4. Prioritizes Chunks That Actually Exist

It uses `.mca` headers to determine whether chunks exist, reducing meaningless processing.

### 5. Uses `ChunkSnapshot`

It first reads block types from a snapshot, reducing access to real world objects.

### 6. Performs Real Modification Only on Matching Blocks

Non-target blocks are only read from the snapshot; no real `Block` object is retrieved for them.

### 7. No Drops for Distant Blocks

Blocks far away from the trigger point are directly removed without generating item entities.

### 8. Disables Physics Updates During Removal

`setType(AIR, false)` avoids large amounts of neighbor updates and physics chains.

## Notes and Potential Issues in the Current Plugin

### `blocks-per-chunk-tick` Does Not Appear to Be Used

The configuration file contains:

```yml
performance:
  blocks-per-chunk-tick: 3000
```

`PluginConfig` also reads this value, but it was not observed controlling the loop inside `ChunkDestructionTask.destroyChunkBlocks`. In other words, the original plugin’s actual throttling mainly consists of:

- maximum chunks per tick
- maximum milliseconds per tick

not “maximum blocks per tick.”

When reproducing this in NeoForge, it is recommended to actually implement `blocks-per-chunk-tick`; otherwise, a single dense chunk may still cause micro-lag.

### Synchronous Chunk Loading May Still Cause Lag

When `loaded-chunks-only = false`, the original plugin synchronously calls `loadChunk(x, z, true)`. If the target area contains many unloaded or ungenerated chunks, this carries significant risk.

The NeoForge version should preferably use:

- non-generating loading of existing chunks
- asynchronous chunk requests
- deferring chunks that have not finished loading

Do not synchronously generate a large number of chunks in a single server tick.

### Region File Scanning Happens During Task Startup

`queueAllWorldChunks()` reads `.mca` file headers. If the radius is large and there are many region files, there may also be disk I/O pressure at startup.

In the NeoForge reproduction, region header scanning can be moved to a background thread, and the result can then be submitted back to the server thread to start the task. However, background threads must not access mutable `ServerLevel` world state.

### Permission and Cooldown Configuration May Not Be Effective

The decompiled result shows permission and cooldown-related methods, but no calls to them were observed in the main block break listener. In the NeoForge reproduction, the following should be explicitly implemented:

- who can trigger the task
- cooldown time
- whether concurrent tasks are allowed
- whether secondary confirmation is required

## NeoForge Reproduction Goals

The NeoForge reproduction does not need to copy the Bukkit API line by line. It should preserve these behavior semantics:

1. Start a batch destruction task after a player breaks a block.
2. By default, only destroy blocks of the same type.
3. Use the trigger point as the center and process chunks within the specified radius.
4. Process work each tick according to a time budget.
5. Perform world modification only on the logical server thread.
6. Chunk queue preparation or chunk requests may be asynchronous, but `setBlock` must not be asynchronous.
7. Silently remove distant blocks.
8. Naturally drop nearby blocks.
9. Silent removal must not trigger neighbor updates.
10. Support special handling for water and waterlogged blocks.

## NeoForge Official Concept Mapping

The following NeoForge / Minecraft concepts are needed for the reproduction:

- Register events using `NeoForge.EVENT_BUS`.
- Listen to block breaking via `BlockEvent.BreakEvent`. The official documentation states it is fired server-side during the actual breaking phase.
- Run tasks each tick by listening to server tick or level tick events. The exact event class name may vary by NeoForge version, so confirm it for the target version.
- Modify world blocks using `Level#setBlock(BlockPos, BlockState, int)`.
- The third parameter of `setBlock` is update flags. Do not use flags that trigger massive neighbor updates.
- Do not modify `ServerLevel` from client logic or background threads.

Reference documentation:

- NeoForge Events: https://docs.neoforged.net/docs/1.21.4/concepts/events/
- NeoForge Block breaking pipeline: https://docs.neoforged.net/docs/1.21.3/blocks/#breaking-a-block
- NeoForge `Level#setBlock` update flags: https://docs.neoforged.net/docs/1.21.4/blocks/states/#levelsetblock

## Recommended NeoForge Class Design

Suggested class layout:

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

Recommended configuration fields:

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

In the NeoForge version, it is recommended to separate `allowChunkGeneration` from the Bukkit behavior instead of directly reproducing `loadChunk(..., true)`. To protect servers, do not generate new chunks by default.

### `DestructionManager`

Responsibilities:

- Store all active tasks.
- Receive events and start tasks.
- Tick tasks every server tick.
- Stop tasks.
- Query progress.
- Store custom drop radius / max drop settings per player.

Recommended data structure:

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

Recommended fields:

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

`LongList` can be from fastutil, or a normal `LongArrayList` can be used. If you do not want to add dependencies, confirm whether fastutil is already available in the project; Minecraft usually includes fastutil, but mod code should still verify this for the target environment.

## NeoForge Event Handling

### Listening to Block Breaking

It is recommended to listen to `BlockEvent.BreakEvent`. The event occurs during the server-side breaking flow and can provide the `BlockState` before the block is broken.

Pseudocode:

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

Notes:

- It is not recommended to cancel the original break event. The original block should be broken by the normal game flow.
- Using a lower priority allows other mods to cancel or adjust the event first.
- If the target version’s event API names differ, replace the class names and getter methods according to the target NeoForge version.

### Advancing Tasks Each Tick

Pseudocode:

```java
@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ServerTickHandler {

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        DestructionManager.INSTANCE.tick(event.getServer());
    }
}
```

If the target version does not have `ServerTickEvent.Post`, use an equivalent server tick or level tick post event. The key requirement is that it runs on the logical server thread.

## NeoForge Chunk Queue Design

### Radius Conversion

Use a square radius, consistent with the original Bukkit plugin:

```java
int centerChunkX = SectionPos.blockToSectionCoord(center.getX());
int centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());

int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
```

Do not use:

```java
center.getX() / 16
```

because it is wrong for negative coordinates.

### Chunk Key

It is recommended to use Minecraft’s built-in `ChunkPos.asLong(x, z)`:

```java
long key = ChunkPos.asLong(chunkX, chunkZ);
int chunkX = ChunkPos.getX(key);
int chunkZ = ChunkPos.getZ(key);
```

If the target version does not have these static methods, use `new ChunkPos(key)` or manually pack the coordinates:

```java
long key = ((long) chunkX & 0xffffffffL) | (((long) chunkZ & 0xffffffffL) << 32);
```

### Region File Scanning

The original plugin uses `.mca` headers to prioritize confirmed existing chunks. NeoForge can reproduce this, but dimension directory paths must be handled carefully:

- Overworld is usually `<world>/region`
- Nether is usually `<world>/DIM-1/region`
- End is usually `<world>/DIM1/region`
- Custom dimensions may be under `<world>/dimensions/<namespace>/<path>/region`

If you do not want to depend on path details, you can first implement a version without region scanning: directly add all chunks within the radius into the queue, then determine whether they exist through non-generating chunk loading. This will be less performant but more robust.

If implementing region scanning, the logic is:

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

Then build the queue:

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

## NeoForge Chunk Loading Strategy

The original Bukkit plugin synchronously loads, or even generates, chunks when needed. The NeoForge reproduction should preferably divide this into three modes:

### Mode A: Only Process Loaded Chunks

Safest and best for performance, but incomplete:

```java
if (!level.hasChunk(chunkX, chunkZ)) {
    skippedChunks++;
    return ChunkResult.SKIPPED;
}
```

Suitable when server stability is the priority.

### Mode B: Only Load Existing Chunks, Do Not Generate

Recommended default mode. The goal is to cover chunks that already exist on disk without generating new terrain.

Pseudocode:

```java
ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
if (!(chunk instanceof LevelChunk levelChunk)) {
    requestAsyncLoadIfAvailable(chunkX, chunkZ);
    return ChunkResult.DEFERRED;
}
```

The exact API names vary by Minecraft / NeoForge version. Confirm for the target version:

- how to check whether a chunk is already loaded
- how to get a chunk without generation
- how to request a chunk asynchronously
- how to return to the server thread after the future completes

### Mode C: Allow Chunk Generation

This is closest to the original plugin’s `loadChunk(x, z, true)`, but it has the highest risk.

Only enable it when it is explicitly necessary for “ungenerated chunks within the radius should also count as part of the world”:

```java
if (config.allowChunkGeneration) {
    // request or load chunk with generation allowed
}
```

Do not enable this by default for large radii.

## NeoForge Block Scanning Strategy

NeoForge does not have a complete equivalent of Bukkit’s `ChunkSnapshot`. Two strategies can be used.

### Strategy A: Simple and Safe Version

Use `ServerLevel#getBlockState(pos)` to read blocks, and strictly limit the number of blocks and the time spent per tick.

Advantages:

- Simple to implement.
- Stable API.
- Does not depend on internal chunk section data structures.

Disadvantages:

- Slower than `ChunkSnapshot` / section palette scanning.

Pseudocode:

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

This version must implement `ChunkCursor`; do not scan a full chunk all at once.

### Strategy B: High-Performance Section Version

Use `LevelChunk` section data structures and skip sections that cannot contain matching blocks.

Core idea:

1. Get the `LevelChunk`.
2. Iterate through chunk sections.
3. If a section is all air, skip it.
4. If the target is a specific block, use an API similar to `section.maybeHas(state -> state.is(targetBlock))` to skip sections that do not contain the target.
5. Scan `16 x 16 x 16` inside sections that may contain matches.
6. After a match is found, call `level.setBlock` or `level.destroyBlock`.

Pseudocode:

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

This approach is closest to the Bukkit plugin’s idea of “lightweight reads first, real modification only after a match.” However, internal Minecraft classes and method names can change between versions, so they should be confirmed according to the target NeoForge version.

## `ChunkCursor`: Actually Implementing `blocks-per-chunk-tick`

The original plugin does not truly use `blocks-per-chunk-tick`. The NeoForge reproduction should add this properly.

`ChunkCursor` stores the current scan progress inside a chunk:

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

Scan order reproducing the original plugin:

1. `localX` from 0 to 15
2. `localZ` from 0 to 15
3. For each column, scan from the heightmap’s highest Y down to `minBuildHeight`

Section scanning can also be used instead, but it must be pausable per tick and resumable next tick.

The main loop each tick should simultaneously limit:

```text
maxMsPerTick
chunksPerTick
blocksPerTick
blocksPerChunkStep
```

Recommended logic:

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

## Target Matching in NeoForge

Bukkit’s `Material` is closer to a broad “type.” In NeoForge, it is recommended to match by `Block`, not by full `BlockState`; otherwise, state properties such as facing direction, waterlogged state, or growth age may cause “same block type” matching to fail.

### Normal Blocks

```java
public boolean matches(BlockState state) {
    return !state.isAir() && state.is(targetBlock);
}
```

### Destroying All Blocks

```java
public boolean matches(BlockState state) {
    return !state.isAir();
}
```

### Water and Lava

Water is special. The original Bukkit plugin uses `Material.WATER` and also handles waterlogged blocks. NeoForge can abstract this as:

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

Handling waterlogged blocks:

```java
if (target.shouldRemoveWaterlogged(state)) {
    BlockState dry = state.setValue(BlockStateProperties.WATERLOGGED, false);
    level.setBlock(pos, dry, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    blocksDestroyed++;
    return;
}
```

## NeoForge Block Removal Strategy

### Silent Removal

Equivalent to Bukkit:

```java
block.setType(Material.AIR, false);
```

Recommended NeoForge version:

```java
int flags = Block.UPDATE_CLIENTS
          | Block.UPDATE_SUPPRESS_DROPS
          | Block.UPDATE_KNOWN_SHAPE;

level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
```

Key points:

- Include `UPDATE_CLIENTS` so clients see the block disappear.
- Include `UPDATE_SUPPRESS_DROPS` to avoid drops.
- Do not include `UPDATE_NEIGHBORS`, avoiding neighbor update storms.
- `UPDATE_KNOWN_SHAPE` may be included to reduce recursive shape updates.

Do not use:

```java
level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
```

because it is equivalent to stronger updates and triggers neighbor updates, which is a performance risk.

### Natural Destruction with Drops

Equivalent to Bukkit:

```java
block.breakNaturally();
```

NeoForge can use:

```java
level.destroyBlock(pos, true, player);
```

Confirm the exact signature for the target Minecraft version. Some versions may require more parameters.

Before triggering natural drops, check:

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

If `mayDrop`:

```java
level.destroyBlock(pos, true, player);
naturalBreaks++;
blocksDestroyed++;
```

Otherwise:

```java
silentRemove(level, pos);
blocksDestroyed++;
```

## Threading Rules

Must be followed:

- `ServerLevel#getBlockState`
- `ServerLevel#setBlock`
- `ServerLevel#destroyBlock`
- entity spawning
- item drop spawning
- boss bar / player messages

All of these must execute on the server thread.

Allowed on background threads:

- Reading `.mca` headers.
- Building chunk key lists.
- Sorting pure data.

After a background thread finishes, return to the server thread:

```java
server.execute(() -> {
    DestructionManager.INSTANCE.attachPreparedQueue(...);
});
```

Do not directly modify the world inside asynchronous callbacks from `CompletableFuture`.

## Progress Statistics

The original plugin calculates progress by chunk:

```text
progress = processedChunks / totalChunks
```

If the NeoForge version implements step-by-step scanning within chunks, progress can still be based on chunks, or block-level progress can be added:

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

## Recommended Configuration Values

Development testing:

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

Recording or single-player server:

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

Extreme reproduction of the original plugin:

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

The extreme configuration is not recommended for public servers.

## Overview of NeoForge Reproduction Pseudocode

### Starting a Task

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

If you do not want asynchronous region scanning, the queue can also be built synchronously, but a large radius may cause a startup lag spike.

### Advancing Each Tick

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

### Processing a Chunk

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

### Removing a Matching Block

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

## Test Checklist

### Basic Functionality

- Breaking stone only deletes other stone within the radius.
- Breaking dirt only deletes other dirt within the radius.
- When `matchBlockType = false`, all non-air blocks are deleted.
- Chunk range is correct at negative coordinates.
- Nether, End, and custom dimension paths are correct.

### Performance

- Radius 64: confirm there is no lag.
- Radius 512: observe TPS.
- Radius 3000: only process loaded chunks.
- Radius 3000: process generated but unloaded chunks.
- When many chunks are ungenerated, confirm that no long lag spike is caused by synchronous generation.

### Drops

- Natural drops occur inside the drop radius.
- No drops occur outside the drop radius.
- `maxNaturalBreakBlocks = 0` means no drops.
- `maxNaturalBreakBlocks = -1` means unlimited natural break count.
- Fluids do not drop items.

### Physics Updates

- Removing sand should not cause a massive falling-block storm.
- Removing blocks next to redstone should not cause massive neighbor updates.
- Removing water or lava should not trigger huge fluid updates.

### Water and Waterlogged Blocks

- Breaking water deletes water blocks.
- Breaking water removes the `waterlogged` state from waterlogged stairs/slabs.
- The waterlogged block itself should not be deleted.

### Concurrency and Cancellation

- Whether multiple tasks in the same world are allowed should be controlled by configuration.
- `/stop` or an equivalent command can cancel tasks.
- All tasks are cancelled when the server shuts down.
- When the player goes offline, the task should either continue or stop according to configuration.

## Implementation Prompt for AI

If you want another AI to implement this in NeoForge, you can directly give it the following requirements.

```text
Please implement a server-side mod on NeoForge: after a player breaks one block, batch-break all blocks of the same type within a specified radius. The behavior should reference the Bukkit plugin CarvsBlockBreak, but do not copy Bukkit APIs literally.

Must implement:
1. Listen to server-side BlockEvent.BreakEvent and read the BlockState and BlockPos before the block is broken.
2. By default, match the same block by Block type, not by full BlockState.
3. Use the trigger point as the center, convert radius into a chunk range, and use floor chunk coordinate conversion. Do not use normal /16.
4. Generate a chunk queue and prioritize chunks closer to the trigger point.
5. Optional: scan region/*.mca headers and place chunks that exist on disk earlier in the queue.
6. Advance tasks every server tick and use System.nanoTime as the maxMsPerTick budget.
7. Simultaneously limit chunksPerTick, blocksPerTick, and blocksPerChunkStep.
8. Do not call ServerLevel#setBlock, destroyBlock, getBlockState, or other world modification/read logic from a background thread. All world access must happen on the server thread.
9. It is allowed to read .mca headers and sort pure data on a background thread; after completion, return to the server thread using server.execute.
10. Silent removal should use Level#setBlock(pos, Blocks.AIR.defaultBlockState(), flags), where flags include UPDATE_CLIENTS, UPDATE_SUPPRESS_DROPS, and UPDATE_KNOWN_SHAPE, but not UPDATE_NEIGHBORS.
11. Inside the drop radius, use level.destroyBlock(pos, true, player) or the equivalent API for the target version; outside the drop radius, silently remove blocks.
12. maxNaturalBreakBlocks limits the number of natural block breaks, not the exact number of item entities.
13. Water, lava, and bubble columns should be treated as fluids and silently removed directly.
14. If the target is water, blocks with the WATERLOGGED property set to true should be changed to WATERLOGGED=false instead of deleting the block itself.
15. Support two separate config options: loadedChunksOnly and allowChunkGeneration. Do not generate new chunks by default.
16. Provide task progress, cancellation, and cleanup on server shutdown.

Do not:
1. Do not scan the entire world or the entire large radius inside a single event.
2. Do not use setBlockAndUpdate to remove large amounts of blocks.
3. Do not include UPDATE_NEIGHBORS as a silent removal flag.
4. Do not modify the world from a CompletableFuture background thread.
5. Do not generate large amounts of ungenerated chunks by default.
```

## Recommended Key Differences from the Original Bukkit Plugin

When reproducing this in NeoForge, preserve the original plugin’s core performance ideas, but make these improvements:

1. Actually implement `blocks-per-chunk-tick`.
2. Do not generate new chunks by default.
3. When chunks are not loaded, defer them instead of synchronously blocking the main thread.
4. Move region header scanning to a background thread where possible.
5. Explicitly implement permission, cooldown, and confirmation logic.
6. Rename `maxDrops` to `maxNaturalBreakBlocks` to avoid misleading semantics.
7. Provide more conservative default values for public servers.

## Most Important Reproduction Principles

The efficiency of this plugin does not come from one specific API, but from these principles:

```text
small batches over many ticks
+ main-thread time limit
+ chunk queue
+ lightweight reads before matches
+ real modification only after a match
+ no drops for distant blocks
+ silent removal without neighbor updates
+ chunk loading should not block the main thread for too long
```

As long as a NeoForge implementation follows these principles, it can reproduce the same performance characteristics even if the concrete APIs differ from Bukkit.