package com.aecraftroute.addon.selftest;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.aecraftroute.addon.planner.CertifiedCraftingPlan;
import com.aecraftroute.addon.planner.ProductionJob;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Independent verification instance harness (never shipped: excluded from the jar).
 *
 * <p>Runs on an isolated dedicated server with its own game directory and a freshly
 * generated world. It builds a small ME network, installs three chained patterns that
 * form a net-growth loop (powder → crystal → seed → powder), requests an amount that
 * cannot be produced from the starting stock without feeding produced intermediates
 * back into the loop, and then observes delivery, cancellation and CPU save/load.
 *
 * <p>Activated only by {@code -Daecraftroute.selftest}. The scenario is selected with
 * {@code -Daecraftroute.selftest.task=growth|cancel|reload}.
 */
@EventBusSubscriber(modid = "aecraftroute")
public final class SelftestHarness {
    static final Logger LOG = LoggerFactory.getLogger("aecraftroute-selftest");
    static final String PROP = "aecraftroute.selftest";
    static final String TASK_PROP = "aecraftroute.selftest.task";
    private static boolean isScaledProcessingExecution(String task) {
        return "scaled-processing-execution".equals(task) || "scaled-processing-plain-execution".equals(task);
    }

    /** Isolated build site inside spawn chunks, well above the flat surface. */
    static final BlockPos BASE = new BlockPos(4, 120, 4);
    static final BlockPos CONTROLLER = BASE;
    static final BlockPos ENERGY = BASE.east(1);
    static final BlockPos DRIVE = BASE.east(2);
    static final BlockPos STORAGE = BASE.east(3);
    static final BlockPos PROVIDER = BASE.east(4);
    static final BlockPos ASSEMBLER = BASE.east(5);
    // A real, node-backed ICraftingRequester. A fake requester object cannot survive a
    // restart, but an ME interface has a grid node and persists its requested jobs, so
    // it is the requester the restart scenario uses.
    static final BlockPos INTERFACE = ENERGY.south(1);
    // AdvancedAE quantum computer: one cluster formed out of a solid cuboid of
    // crafting-unit blocks. AdvancedAE's own structure check requires the shell to be
    // STRUCTURE blocks, exactly one QUANTUM_CORE strictly inside, and at least one
    // storage unit strictly inside, so the shape needs an interior of at least two
    // blocks - a 4x4x4 cube is the smallest such cuboid.
    static final BlockPos QUANTUM_MIN = new BlockPos(10, 120, 4);
    static final BlockPos QUANTUM_MAX = new BlockPos(13, 123, 7);
    static final BlockPos QUANTUM_CORE_POS = new BlockPos(11, 121, 5);
    static final BlockPos QUANTUM_STORAGE_POS = new BlockPos(12, 122, 6);

    static final String PACK = "aecraftroute_selftest";

    private static Scenario scenario;

    static {
        if (System.getProperty(PROP) != null) {
            try {
                prepareGameDirectory();
            } catch (Throwable t) {
                LOG.error("[SELFTEST] could not prepare game directory", t);
            }
        }
    }

    private SelftestHarness() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (System.getProperty(PROP) == null || scenario != null) {
            return;
        }
        scenario = new Scenario(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (scenario != null) {
            scenario.tick();
        }
    }

    // ------------------------------------------------------------------
    // Datapack: three chained crafting-table recipes forming a growth loop.
    // 1 powder  -> 1 crystal
    // 1 crystal -> 2 seeds
    // 1 seed    -> 1 powder
    // One powder therefore yields one crystal and, through the loop, another powder:
    // producing N crystals from one powder requires re-feeding produced items.
    // ------------------------------------------------------------------

    private static void prepareGameDirectory() throws Exception {
        Path gameDir = gameDirectory();
        Files.createDirectories(gameDir);
        Files.writeString(gameDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);

        Path properties = gameDir.resolve("server.properties");
        if (!Files.exists(properties)) {
            Files.writeString(properties, String.join("\n",
                    "online-mode=false",
                    "level-type=minecraft:flat",
                    "level-seed=aecraftroute-selftest",
                    "spawn-protection=0",
                    "max-tick-time=-1",
                    "view-distance=4",
                    "simulation-distance=4",
                    "sync-chunk-writes=false",
                    "enable-command-block=false",
                    ""), StandardCharsets.UTF_8);
        }

        // Every scenario except restart2 builds its world from scratch. A world left
        // behind by an earlier run still holds stock, patterns and a busy CPU, which
        // silently changes the starting capital of this run. restart2 is the second
        // half of restart1 and deliberately reuses the world restart1 saved.
        String savedTask=System.getProperty(TASK_PROP,"");
        if (!"restart2".equals(savedTask) && !"advanced-restart2".equals(savedTask)
                && !(savedTask.startsWith("legacy-feedback") && savedTask.endsWith("restart2"))) {
            deleteWorld(gameDir.resolve("world"));
        }

        Path pack = gameDir.resolve("world").resolve("datapacks").resolve(PACK);
        Files.createDirectories(pack.resolve("data/selftest/recipe"));
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":48,\"description\":\"aecraftroute selftest recipes\"}}",
                StandardCharsets.UTF_8);
        if(isScaledProcessingExecution(System.getProperty(TASK_PROP)))
            Files.writeString(pack.resolve("data/selftest/recipe/scaled_smelting.json"),
                    "{\"type\":\"minecraft:smelting\",\"ingredient\":{\"item\":\"minecraft:iron_ingot\"},\"result\":{\"id\":\"minecraft:gold_block\"},\"experience\":0,\"cookingtime\":1}",StandardCharsets.UTF_8);
        writeRecipe(pack, "powder_to_crystal", "minecraft:emerald", 1, "minecraft:diamond", 1);
        writeRecipe(pack, "crystal_to_seed", "minecraft:diamond", 1, "minecraft:lapis_lazuli", 2);
        writeRecipe(pack, "seed_to_powder", "minecraft:lapis_lazuli", 1, "minecraft:emerald", 1);
        if(System.getProperty(TASK_PROP,"").startsWith("legacy-feedback")) {
            Files.writeString(pack.resolve("data/selftest/recipe/legacy_production.json"),
                    "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:diamond\"},{\"item\":\"minecraft:iron_ingot\"}],\"result\":{\"id\":\"minecraft:emerald\",\"count\":2}}",StandardCharsets.UTF_8);
            writeRecipe(pack,"legacy_recycle","minecraft:emerald",1,"minecraft:diamond",1);
            Files.createDirectories(pack.resolve("data/selftest/tags/item"));
            Files.writeString(pack.resolve("data/selftest/tags/item/legacy_refuel.json"),
                    "{\"values\":[\"minecraft:lapis_lazuli\",\"minecraft:emerald\"]}",StandardCharsets.UTF_8);
            Files.writeString(pack.resolve("data/selftest/recipe/legacy_refuel.json"),
                    "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"tag\":\"selftest:legacy_refuel\"}],\"result\":{\"id\":\"minecraft:iron_ingot\",\"count\":1}}",StandardCharsets.UTF_8);
        }
        if ("paths".equals(System.getProperty(TASK_PROP))) {
            writeRecipe(pack,"powder_to_seed","minecraft:emerald",1,"minecraft:lapis_lazuli",1);
            Files.writeString(pack.resolve("data/selftest/recipe/path_final.json"),
                    "{\"type\":\"minecraft:crafting_shapeless\",\"category\":\"misc\",\"ingredients\":[{\"item\":\"minecraft:emerald\"},{\"item\":\"minecraft:lapis_lazuli\"}],\"result\":{\"id\":\"minecraft:iron_block\",\"count\":1}}",StandardCharsets.UTF_8);
        }
        LOG.info("[SELFTEST] datapack written to {}", pack);
    }

    /** Removes a leftover verification world so every scenario starts from the same state. */
    private static void deleteWorld(Path world) throws Exception {
        if (!Files.isDirectory(world)) {
            return;
        }
        try (var paths = Files.walk(world)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        LOG.info("[SELFTEST] removed the previous verification world at {}", world);
    }

    private static void writeRecipe(Path pack, String name, String input, int inputCount,
                                    String output, int outputCount) throws Exception {
        String json = "{\n"
                + "  \"type\": \"minecraft:crafting_shapeless\",\n"
                + "  \"category\": \"misc\",\n"
                + "  \"ingredients\": [{\"item\": \"" + input + "\"}],\n"
                + "  \"result\": {\"id\": \"" + output + "\", \"count\": " + outputCount + "}\n"
                + "}\n";
        Files.writeString(pack.resolve("data/selftest/recipe/" + name + ".json"), json,
                StandardCharsets.UTF_8);
    }

    private static Path gameDirectory() {
        try {
            return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        } catch (Throwable t) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    // ------------------------------------------------------------------

    private enum Phase { BUILD, WAIT, PREPARE, SUBMIT, CALCULATING, RUN, DONE }

    static final class Scenario {
        private final MinecraftServer server;
        private final String task;
        private final List<String> failures = new ArrayList<>();
        private Phase phase = Phase.BUILD;
        private int ticks;
        private int phaseTicks;
        private ServerLevel level;
        private IGrid grid;
        private CraftingCPUCluster cpu;
        private ICraftingCPU selectedCpu;
        private ICraftingCPU executingCpu;
        private int completionTicks;
        private final boolean advanced;
        private final boolean restart1;
        private final boolean restart2;
        private final boolean partial;
        private final boolean paths;
        private final boolean vanilla;
        private final boolean foreign;
        private final boolean fallback;
        private final boolean fallbackSmall;
        private final boolean fallbackBusy;
        private ICraftingRequester foreignRequester;
        private final boolean stockRace;
        private final boolean legacyFeedback;
        private final boolean legacyRestart1,legacyRestart2;
        private Object stockRaceJob;
        private boolean stockRaceDrained;
        private long stockRaceDeadline;
        private CpuTaskObserver pathObserver;
        private IPatternDetails pathDiamondLapis,pathEmeraldLapis;
        private Requester requester;
        private appeng.helpers.InterfaceLogic interfaceLogic;
        private ICraftingLink link;
        private ICraftingPlan plan;
        private java.util.concurrent.Future<ICraftingPlan> pendingCalculation;
        private boolean stocked;
        private boolean patternsInstalled;
        private boolean scaledNativePrepared;
        private List<java.util.concurrent.CompletableFuture<CertifiedCraftingPlan>> planningLoad;
        private boolean planningLoadRecovered;
        private boolean submitted;
        private boolean cancelled;
        private boolean reloaded;
        private boolean legacyReloadPaused;
        private boolean legacyReloadProbeLogged;
        private boolean restartSaved;
        private boolean resumed;
        private int idleTicks;
        private int cancelTick = -1;
        private long requested;
        private boolean finished;

        Scenario(MinecraftServer server) {
            this.server = server;
            this.task = System.getProperty(TASK_PROP, "growth");
            this.advanced = "advanced".equals(this.task) || "advanced-overestimate".equals(this.task)
                    || "advanced-stock-race".equals(this.task) || "foreign-advanced".equals(this.task)
                    || "fallback-small".equals(this.task) || "fallback-busy".equals(this.task)
                    || "vanilla-canvas-advanced".equals(this.task) || "scaled-advanced".equals(this.task)
                    || "foreign-advanced-plus".equals(this.task) || isScaledProcessingExecution(this.task) || "scaled-molecular-execution".equals(this.task)
                    || this.task.startsWith("legacy-feedback-advanced") || this.task.startsWith("advanced-restart");
            this.legacyFeedback=this.task.startsWith("legacy-feedback");
            this.legacyRestart1=legacyFeedback && task.endsWith("restart1");
            this.legacyRestart2=legacyFeedback && task.endsWith("restart2");
            this.restart1 = "restart1".equals(this.task) || "advanced-restart1".equals(this.task);
            this.restart2 = "restart2".equals(this.task) || "advanced-restart2".equals(this.task);
            this.partial = "partial".equals(this.task) || this.task.endsWith("overestimate");
            this.paths = "paths".equals(this.task);
            this.foreign = "foreign".equals(this.task) || "foreign-advanced".equals(this.task)
                    || "scaled-native-plan".equals(this.task) || "foreign-advanced-plus".equals(this.task)
                    || isScaledProcessingExecution(this.task) || "scaled-molecular-execution".equals(this.task);
            this.fallback = "fallback".equals(this.task);
            this.fallbackSmall = "fallback-small".equals(this.task);
            this.fallbackBusy = "fallback-busy".equals(this.task);
            this.vanilla = "vanilla".equals(this.task) || this.task.startsWith("vanilla-canvas")
                    || "planning-load".equals(this.task)
                    || "scaled-strict-roundtrip".equals(this.task)
                    || "scaled-native-plan".equals(this.task)
                    || "scaled-advanced".equals(this.task) || foreign || fallback || fallbackSmall || fallbackBusy;
            this.stockRace=this.task.endsWith("stock-race");
            // Opt this harness in exactly like a cooperating mod would; unregistered
            // third-party requesters are deliberately left on the native path.
            com.aecraftroute.addon.api.CraftRouteCraftingRegistry.register(Requester.class.getName());
            LOG.info("[SELFTEST] start task={} gameDir={}", task,
                    gameDirectory().toAbsolutePath());
        }

        void tick() {
            if (finished) {
                return;
            }
            if("gate-failure".equals(task)) {
                fail("intentional negative control for the Gradle selftest gate");
                finish();return;
            }
            ticks++;
            phaseTicks++;
            try {
                step();
            } catch (Throwable t) {
                fail("unexpected " + t);
                LOG.error("[SELFTEST] exception", t);
                finish();
            }
        }

        private void step() {
            if(legacyRestart2){resumeLegacyFeedback();return;}
            if (restart2) {
                restartResume();
                return;
            }
            switch (phase) {
                case BUILD -> build();
                case WAIT -> awaitNetwork();
                case PREPARE -> prepare();
                case SUBMIT -> submit();
                case CALCULATING -> calculating();
                case RUN -> run();
                case DONE -> {
                }
                default -> {
                }
            }
        }

        // ---------------------------------------------------------- BUILD

        private void build() {
            level = server.overworld();
            if (level == null) {
                return;
            }
            level.getChunk(BASE); // force the chunk to load before placing
            place(CONTROLLER, AEBlocks.CONTROLLER.block());
            place(ENERGY, AEBlocks.CREATIVE_ENERGY_CELL.block());
            place(DRIVE, AEBlocks.DRIVE.block());
            place(STORAGE, AEBlocks.CRAFTING_STORAGE_1K.block());
            place(PROVIDER, AEBlocks.PATTERN_PROVIDER.block());
            place(ASSEMBLER, AEBlocks.MOLECULAR_ASSEMBLER.block());
            if("scaled-molecular-execution".equals(task))buildSuperMatrix();
            if(isScaledProcessingExecution(task)) {
                // Provider -> furnace top -> hopper -> real ME interface. No test
                // code transfers, consumes or manufactures any processing items.
                place(PROVIDER.below(),net.minecraft.world.level.block.Blocks.FURNACE);
                level.setBlock(PROVIDER.below(2),net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.HopperBlock.FACING,net.minecraft.core.Direction.NORTH),3);
                place(PROVIDER.below(2).north(),AEBlocks.INTERFACE.block());
                for(var pos:List.of(STORAGE.below(),STORAGE.below(2),STORAGE.below(2).north()))
                    place(pos,AEBlocks.CREATIVE_ENERGY_CELL.block());
                ((net.minecraft.world.level.block.entity.FurnaceBlockEntity)level.getBlockEntity(PROVIDER.below()))
                        .setItem(1,new ItemStack(Items.COAL));
            }
            if (restart1) {
                place(INTERFACE, AEBlocks.INTERFACE.block());
            }
            LOG.info("[SELFTEST] blocks placed at {}..{}", CONTROLLER, ASSEMBLER);
            if (advanced) {
                placeQuantumComputer();
            }
            for (BlockPos pos : List.of(CONTROLLER, ENERGY, DRIVE, STORAGE, PROVIDER, ASSEMBLER, INTERFACE)) {
                LOG.info("[SELFTEST] {} state={} be={}", pos, level.getBlockState(pos),
                        level.getBlockEntity(pos) == null ? "null"
                                : level.getBlockEntity(pos).getClass().getSimpleName());
            }
            phase = Phase.WAIT;
            phaseTicks = 0;
        }

        private void place(BlockPos pos, net.minecraft.world.level.block.Block block) {
            if (level.getBlockState(pos).is(block)) {
                return;
            }
            level.setBlock(pos, block.defaultBlockState(), 3);
        }
        private void buildSuperMatrix() {
            try {
                var blocks=Class.forName("com.extendedae_plus.init.ModBlocks");
                var frame=(net.minecraft.world.level.block.Block)((java.util.function.Supplier<?>)blocks.getField("SUPER_ASSEMBLER_MATRIX_FRAME").get(null)).get();
                var wall=(net.minecraft.world.level.block.Block)((java.util.function.Supplier<?>)blocks.getField("SUPER_ASSEMBLER_MATRIX_WALL").get(null)).get();
                var hybrid=(net.minecraft.world.level.block.Block)((java.util.function.Supplier<?>)blocks.getField("ASSEMBLER_MATRIX_HYBRID_PLUS").get(null)).get();
                var min=STORAGE.south(4);var max=min.offset(2,2,2);
                for(var pos:BlockPos.betweenClosed(min,max)) {
                    int boundaries=(pos.getX()==min.getX()||pos.getX()==max.getX()?1:0)
                            +(pos.getY()==min.getY()||pos.getY()==max.getY()?1:0)
                            +(pos.getZ()==min.getZ()||pos.getZ()==max.getZ()?1:0);
                    place(pos.immutable(),boundaries>=2?frame:boundaries==1?wall:hybrid);
                }
                for(int z=1;z<4;z++)place(STORAGE.south(z),AEBlocks.CREATIVE_ENERGY_CELL.block());
            } catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        }

        private void placeQuantumComputer() {
            var structure = net.pedroksl.advanced_ae.common.definitions.AAEBlocks.QUANTUM_STRUCTURE.block();
            var unit = net.pedroksl.advanced_ae.common.definitions.AAEBlocks.QUANTUM_UNIT.block();
            var core = net.pedroksl.advanced_ae.common.definitions.AAEBlocks.QUANTUM_CORE.block();
            var storage = net.pedroksl.advanced_ae.common.definitions.AAEBlocks.QUANTUM_STORAGE_128M.block();
            for (BlockPos pos : BlockPos.betweenClosed(QUANTUM_MIN, QUANTUM_MAX)) {
                place(pos.immutable(), onQuantumShell(pos) ? structure : unit);
            }
            place(QUANTUM_CORE_POS, core);
            place(QUANTUM_STORAGE_POS, storage);
            LOG.info("[SELFTEST] quantum computer blocks placed at {}..{} core={} storage={}",
                    QUANTUM_MIN, QUANTUM_MAX, QUANTUM_CORE_POS, QUANTUM_STORAGE_POS);
        }

        private static boolean onQuantumShell(BlockPos pos) {
            return pos.getX() == QUANTUM_MIN.getX() || pos.getX() == QUANTUM_MAX.getX()
                    || pos.getY() == QUANTUM_MIN.getY() || pos.getY() == QUANTUM_MAX.getY()
                    || pos.getZ() == QUANTUM_MIN.getZ() || pos.getZ() == QUANTUM_MAX.getZ();
        }

        // ----------------------------------------------------------- WAIT

        private void awaitNetwork() {
            var controller = level.getBlockEntity(CONTROLLER);
            if (!(controller instanceof IGridConnectedBlockEntity networked)) {
                if (phaseTicks == 600) {
                    LOG.error("[SELFTEST] controller be is {}", controller);
                }
                timeout("controller block entity missing");
                return;
            }
            var node = networked.getMainNode();
            grid = node == null ? null : node.getGrid();
            if (grid == null) {
                timeout("grid not formed (devices may not be connected)");
                return;
            }
            var cpus = grid.getCraftingService().getCpus();
            if (cpus.isEmpty()) {
                timeout("crafting CPU not formed");
                return;
            }
            for (ICraftingCPU candidate : cpus) {
                boolean adv = candidate.getClass().getName().startsWith("net.pedroksl.advanced_ae");
                LOG.info("[SELFTEST] cpu {} advanced={} busy={} storage={}",
                        candidate.getClass().getSimpleName(), adv, candidate.isBusy(),
                        candidate.getAvailableStorage());
                if (adv == advanced && selectedCpu == null) {
                    selectedCpu = candidate;
                }
                if (!adv && cpu == null && candidate instanceof CraftingCPUCluster vanilla) {
                    cpu = vanilla;
                }
            }
            if (selectedCpu == null) {
                if (advanced && phaseTicks % 100 == 0) {
                    var coreBe = level.getBlockEntity(QUANTUM_CORE_POS);
                    LOG.info("[SELFTEST] quantum core state={} be={}", level.getBlockState(QUANTUM_CORE_POS),
                            coreBe == null ? "null" : coreBe.getClass().getName());
                }
                timeout("no " + (advanced ? "AdvancedAE quantum computer" : "AE2 crafting CPU") + " formed");
                return;
            }
            if (cpu == null) {
                timeout("the plain AE2 crafting CPU did not form");
                return;
            }
            if (restart1) {
                var iface = level.getBlockEntity(INTERFACE);
                if (!(iface instanceof appeng.helpers.InterfaceLogicHost host)) {
                    timeout("ME interface missing (it is the restart requester)");
                    return;
                }
                interfaceLogic = host.getInterfaceLogic();
                if(advanced)cpu.getConfigManager().putSetting(appeng.api.config.Settings.CPU_SELECTION_MODE,appeng.api.config.CpuSelectionMode.PLAYER_ONLY);
                LOG.info("[SELFTEST] restart requester is a real ME interface at {} onNode={}",
                        INTERFACE, interfaceLogic.getActionableNode() != null);
            }

            var drive = level.getBlockEntity(DRIVE);
            if (drive instanceof DriveBlockEntity driveBe) {
                driveBe.getInternalInventory().insertItem(0, AEItems.ITEM_CELL_1K.stack(), false);
            }
            long simulated = inventory().insert(AEItemKey.of(Items.EMERALD), 1, Actionable.SIMULATE, IActionSource.empty());
            if (simulated < 1) {
                timeout("network item storage not online");
                return;
            }
            int nodes = 0;
            for (var ignored : grid.getNodes()) {
                nodes++;
            }
            LOG.info("[SELFTEST] network ready: nodes={} cpus={} storageBytes={}",
                    nodes, cpus.size(), cpu.getAvailableStorage());
            phase = Phase.PREPARE;
            phaseTicks = 0;
        }

        // -------------------------------------------------------- PREPARE

        private void prepare() {
            if(legacyFeedback){prepareLegacyFeedback();return;}
            if(vanilla) {prepareVanilla();return;}
            if (!stocked) {
                long accepted = inventory().insert(AEItemKey.of(Items.EMERALD), 1, Actionable.MODULATE, IActionSource.empty());
                if (accepted != 1) {
                    timeout("could not seed starting stock");
                    return;
                }
                stocked = true;
                LOG.info("[SELFTEST] seeded starting stock: 1 emerald");
            }
            if (!patternsInstalled) {
                var provider = level.getBlockEntity(PROVIDER);
                if (!(provider instanceof PatternProviderBlockEntity providerBe)) {
                    fail("pattern provider block entity missing");
                    finish();
                    return;
                }
                var logic = providerBe.getLogic();
                ItemStack first = pattern("powder_to_crystal", Items.EMERALD, Items.DIAMOND);
                ItemStack second = pattern("crystal_to_seed", Items.DIAMOND, Items.LAPIS_LAZULI);
                ItemStack third = pattern("seed_to_powder", Items.LAPIS_LAZULI, Items.EMERALD);
                if (first.isEmpty() || second.isEmpty() || third.isEmpty()) {
                    fail("could not encode the loop patterns (recipe or output missing)");
                    finish();
                    return;
                }
                var inv = logic.getPatternInv();
                inv.setItemDirect(0, first);
                inv.setItemDirect(1, second);
                inv.setItemDirect(2, third);
                if(paths) {
                    var alternative=pattern("powder_to_seed",Items.EMERALD,Items.LAPIS_LAZULI);
                    var holder=recipe("path_final").orElseThrow();
                    var craftingGrid=new ItemStack[9]; java.util.Arrays.fill(craftingGrid,ItemStack.EMPTY);
                    craftingGrid[3]=new ItemStack(Items.EMERALD); craftingGrid[4]=new ItemStack(Items.LAPIS_LAZULI);
                    var last=PatternDetailsHelper.encodeCraftingPattern(holder,craftingGrid,
                            holder.value().getResultItem(level.registryAccess()),false,false);
                    if(alternative.isEmpty() || last.isEmpty()) throw new IllegalStateException("Cannot encode path-lock patterns");
                    inv.setItemDirect(3,alternative); inv.setItemDirect(4,last);
                }
                logic.updatePatterns();
                patternsInstalled = true;
                LOG.info("[SELFTEST] patterns installed: {}", logic.getAvailablePatterns().size());
            }
            var service = grid.getCraftingService();
            if (service.getCraftingFor(AEItemKey.of(Items.DIAMOND)).isEmpty()) {
                timeout("network does not advertise the diamond pattern");
                return;
            }
            if(paths && (service.getCraftingFor(AEItemKey.of(Items.IRON_BLOCK)).isEmpty()
                    || service.getCraftingFor(AEItemKey.of(Items.LAPIS_LAZULI)).size()<2)) {
                timeout("path fixture patterns not yet advertised"); return;
            }
            if(!paths) verifyCanvasApply();
            if (restart1) {
                // Let the interface request the craft itself: only then does AE2 store the
                // crafting link inside the interface's own MultiCraftingTracker, which is
                // what a real world save persists. Submitting by hand leaves the requester
                // side of the link unstored, so a restart would always cancel the job.
                armInterface();
                phase = Phase.RUN;
                phaseTicks = 0;
                return;
            }
            phase = Phase.SUBMIT;
            phaseTicks = 0;
        }

        private void prepareLegacyFeedback() {
            if(!stocked) {
                var seeds=new java.util.ArrayList<>(List.of(Items.DIAMOND,Items.IRON_INGOT));
                if("legacy-feedback-control".equals(task))seeds.add(Items.LAPIS_LAZULI);
                if(task.contains("-seed-"))seeds.add(Items.EMERALD);
                for(var item:seeds)
                    if(inventory().insert(AEItemKey.of(item),1,Actionable.MODULATE,IActionSource.empty())!=1)
                        throw new IllegalStateException("Cannot seed legacy feedback fixture");
                stocked=true;
            }
            if(!patternsInstalled) {
                var provider=(PatternProviderBlockEntity)level.getBlockEntity(PROVIDER);
                var inputs=new ItemStack[9];java.util.Arrays.fill(inputs,ItemStack.EMPTY);
                inputs[3]=new ItemStack(Items.DIAMOND);inputs[4]=new ItemStack(Items.IRON_INGOT);
                var production=recipe("legacy_production").orElseThrow();
                provider.getLogic().getPatternInv().setItemDirect(0,PatternDetailsHelper.encodeCraftingPattern(
                        production,inputs,new ItemStack(Items.EMERALD,2),false,false));
                provider.getLogic().getPatternInv().setItemDirect(1,pattern("legacy_recycle",Items.EMERALD,Items.DIAMOND));
                java.util.Arrays.fill(inputs,ItemStack.EMPTY);inputs[4]=new ItemStack(Items.LAPIS_LAZULI);
                provider.getLogic().getPatternInv().setItemDirect(2,PatternDetailsHelper.encodeCraftingPattern(
                        recipe("legacy_refuel").orElseThrow(),inputs,new ItemStack(Items.IRON_INGOT),true,false));
                provider.getLogic().updatePatterns();patternsInstalled=true;
            }
            for(var item:List.of(Items.EMERALD,Items.DIAMOND,Items.IRON_INGOT))
                if(grid.getCraftingService().getCraftingFor(AEItemKey.of(item)).isEmpty()) {
                    timeout("legacy feedback pattern is not advertised");return;
                }
            phase=Phase.SUBMIT;phaseTicks=0;
        }

        /** Execute the exact pattern encoded by the isolated client QA command. */
        private void prepareVanilla() {
            if("scaled-molecular-execution".equals(task)) {
                if(!stocked){inventory().insert(AEItemKey.of(Items.IRON_INGOT),54,Actionable.MODULATE,IActionSource.empty());stocked=true;}
                if(!patternsInstalled) {
                    try {
                        Object core=level.getBlockEntity(STORAGE.south(4).offset(1,1,1));
                        var inv=(appeng.api.inventories.InternalInventory)core.getClass().getMethod("getPatternInventory").invoke(core);
                        inv.setItemDirect(0,com.aecraftroute.addon.route.RouteQaFixtures.ironBlockPattern(level));
                        core.getClass().getMethod("updatePatterns").invoke(core);patternsInstalled=true;
                    } catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
                }
                if(grid.getCraftingService().getCraftingFor(AEItemKey.of(Items.IRON_BLOCK)).stream()
                        .noneMatch(p->p.getClass().getName().endsWith("StrictMolecularAssemblerPattern"))) {
                    timeout("super matrix strict pattern is not advertised");return;
                }
                phase=Phase.SUBMIT;phaseTicks=0;return;
            }
            if(!stocked) {
                long stockAmount="foreign-advanced-plus".equals(task)?54:fallbackBusy?18:9;
                long accepted=inventory().insert(AEItemKey.of(Items.IRON_INGOT),stockAmount,Actionable.MODULATE,IActionSource.empty());
                if(accepted!=stockAmount) {timeout("could not stock iron ingots");return;}
                stocked=true;
            }
            if(!patternsInstalled) {
                var provider=(PatternProviderBlockEntity)level.getBlockEntity(PROVIDER);
                var encoded=com.aecraftroute.addon.route.RouteQaFixtures.ironBlockPattern(level);
                if(encoded.isEmpty())throw new IllegalStateException("Vanilla QA pattern is empty");
                provider.getLogic().getPatternInv().setItemDirect(0,encoded);
                provider.getLogic().updatePatterns();
                patternsInstalled=true;
            }
            if(grid.getCraftingService().getCraftingFor(AEItemKey.of(Items.IRON_BLOCK)).isEmpty()) {
                timeout("vanilla iron-block pattern is not advertised");return;
            }
            if("scaled-native-plan".equals(task) || "foreign-advanced-plus".equals(task) || isScaledProcessingExecution(task)) {
                if(!scaledNativePrepared) {prepareNativeScaling();scaledNativePrepared=true;}
                if(grid.getCraftingService().getCraftingFor(AEItemKey.of(Items.GOLD_BLOCK)).isEmpty()) {
                    timeout("native AdvancedAE processing pattern is not advertised");return;
                }
            }
            if("scaled-advanced".equals(task)) {
                verifyAdvancedScaledWrapper(1);
                verifyAdvancedScaledWrapper(2);
            }
            if("scaled-strict-roundtrip".equals(task)) {verifyStrictScaledRoundtrip();finish();return;}
            phase=Phase.SUBMIT;phaseTicks=0;
        }

        private void verifyStrictScaledRoundtrip() {
            try {
                var base=PatternDetailsHelper.decodePattern(com.aecraftroute.addon.route.RouteQaFixtures.ironBlockPattern(level),level);
                var type=Class.forName("com.extendedae_plus.util.crafting.StrictMolecularAssemblerPattern");
                var strict=(IPatternDetails)type.getMethod("wrap",appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern.class).invoke(null,base);
                type.getMethod("eap$setAllowScaling",boolean.class).invoke(strict,false);
                type.getMethod("eap$setMultiplierLimit",int.class).invoke(strict,17);
                var scaled=(IPatternDetails)Class.forName("com.extendedae_plus.util.smartDoubling.PatternScaler")
                        .getMethod("createScaled",IPatternDetails.class,long.class).invoke(null,strict,6L);
                var tag=com.aecraftroute.addon.compat.ScaledPatternCompat.encode(scaled,
                        p->p.getDefinition().toTag(level.registryAccess()));
                var restored=com.aecraftroute.addon.compat.ScaledPatternCompat.decode(tag,
                        t->PatternDetailsHelper.decodePattern(AEItemKey.fromTag(level.registryAccess(),t).toStack(),level),level);
                var inner=restored.getClass().getMethod("getOriginal").invoke(restored);
                if(!type.isInstance(inner))throw new IllegalStateException("Strict molecular wrapper lost: "+inner.getClass().getName());
                if((boolean)type.getMethod("eap$allowScaling").invoke(inner)
                        || (int)type.getMethod("eap$getMultiplierLimit").invoke(inner)!=17)
                    throw new IllegalStateException("Strict molecular wrapper flags changed");
                var target=AEItemKey.of(Items.IRON_BLOCK);
                var plan=com.aecraftroute.addon.planner.NetworkProductionPlanner.plan(target,6,
                        k->k.equals(target)?List.of(scaled):List.of(),k->k.equals(AEItemKey.of(Items.IRON_INGOT))?54L:0L);
                var saved=new ProductionJob(plan).save(level.registryAccess());
                var loaded=ProductionJob.load(saved,level.registryAccess(),level);
                if(loaded.invalid() || !loaded.validateRemainingTasks(java.util.Map.of(scaled,1L)))
                    throw new IllegalStateException("Strict molecular certified job did not resume");
                LOG.info("[SELFTEST] strict molecular wrapper and flags restored: outer={} inner={}",restored.getClass().getName(),inner.getClass().getName());
            } catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        }

        /** Leave smart doubling active until the native AE2 planner completes. */
        private void prepareNativeScaling() {
            try {
                ItemStack definition;
                if("scaled-processing-plain-execution".equals(task))
                    definition=PatternDetailsHelper.encodeProcessingPattern(
                            List.of(new appeng.api.stacks.GenericStack(AEItemKey.of(Items.IRON_INGOT),1)),
                            List.of(new appeng.api.stacks.GenericStack(AEItemKey.of(Items.GOLD_BLOCK),1)));
                else {
                    definition=net.pedroksl.advanced_ae.common.definitions.AAEItems.ADV_PROCESSING_PATTERN.stack();
                    net.pedroksl.advanced_ae.common.patterns.AdvProcessingPattern.encode(definition,
                            List.of(new appeng.api.stacks.GenericStack(AEItemKey.of(Items.IRON_INGOT),1)),
                            List.of(new appeng.api.stacks.GenericStack(AEItemKey.of(Items.GOLD_BLOCK),1)),
                            new java.util.HashMap<AEKey,net.minecraft.core.Direction>());
                }
                var provider=(PatternProviderBlockEntity)level.getBlockEntity(PROVIDER);
                provider.getLogic().getPatternInv().setItemDirect(1,definition);
                var setting=Class.forName("com.extendedae_plus.api.config.EAPSettings")
                        .getField("SMART_DOUBLING").get(null);
                var manager=provider.getLogic().getConfigManager();
                manager.getClass().getMethod("putSetting",appeng.api.config.Setting.class,Enum.class)
                        .invoke(manager,setting,appeng.api.config.YesNo.YES);
                Class.forName("com.extendedae_plus.api.smartDoubling.ISmartDoublingHolder")
                        .getMethod("eap$setProviderSmartDoublingLimit",int.class)
                        .invoke(provider.getLogic(),6);
                provider.getLogic().updatePatterns();
            } catch(ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot enable real ExtendedAE Plus smart doubling",error);
            }
        }

        /** This requires the real NeoForge mod loader and both installed addon jars. */
        private void verifyAdvancedScaledWrapper(int sparseSlots) {
            try {
                long inputAmount=6L*sparseSlots;
                var definition=net.pedroksl.advanced_ae.common.definitions.AAEItems.ADV_PROCESSING_PATTERN.stack();
                var inputs=java.util.Collections.nCopies(sparseSlots,
                        new appeng.api.stacks.GenericStack(AEItemKey.of(Items.IRON_INGOT),1));
                var outputs=List.of(new appeng.api.stacks.GenericStack(AEItemKey.of(Items.GOLD_BLOCK),1));
                net.pedroksl.advanced_ae.common.patterns.AdvProcessingPattern.encode(
                        definition,inputs,outputs,new java.util.HashMap<AEKey,net.minecraft.core.Direction>());
                IPatternDetails original=new net.pedroksl.advanced_ae.common.patterns.AdvProcessingPattern(
                        AEItemKey.of(definition));
                var advertised=PatternDetailsHelper.decodePattern(definition,level);
                if(!(advertised instanceof net.pedroksl.advanced_ae.common.patterns.AdvProcessingPattern))
                    throw new IllegalStateException("AdvancedAE encoded pattern did not decode through AE2");
                var type=Class.forName("com.extendedae_plus.api.crafting.ScaledProcessingPatternAdv");
                var scaler=Class.forName("com.extendedae_plus.util.smartDoubling.PatternScaler");
                var wrapped=(IPatternDetails)scaler.getMethod("createScaled",IPatternDetails.class,long.class)
                        .invoke(null,original,6L);
                if(wrapped.getClass()!=type)
                    throw new IllegalStateException("Installed addon did not choose AdvancedAE scaled wrapper");
                var encoded=com.aecraftroute.addon.compat.ScaledPatternCompat.encode(wrapped,p->{
                    if(p!=original)throw new IllegalStateException("Scaled wrapper lost original");
                    return new CompoundTag();
                });
                if(encoded.getList("aecraftrouteScaledWrappers",net.minecraft.nbt.Tag.TAG_COMPOUND)
                        .getCompound(0).getLong("operations")!=6)
                    throw new IllegalStateException("Advanced scaled wrapper multiplier did not serialize");
                var decoded=com.aecraftroute.addon.compat.ScaledPatternCompat.decode(encoded,p->original,level);
                if(decoded.getClass()!=type || type.getMethod("getOriginal").invoke(decoded)!=original
                        || decoded.getOutputs().getFirst().amount()!=6)
                    throw new IllegalStateException("Advanced scaled wrapper did not round-trip");
                var working=new appeng.crafting.inv.ListCraftingInventory(k->{});
                working.insert(AEItemKey.of(Items.IRON_INGOT),inputAmount,Actionable.MODULATE);
                var extracted=appeng.crafting.execution.CraftingCpuHelper.extractPatternInputs(
                        decoded,working,level,new KeyCounter(),new KeyCounter());
                if(extracted==null || working.extract(AEItemKey.of(Items.IRON_INGOT),inputAmount,Actionable.SIMULATE)!=0)
                    throw new IllegalStateException("Advanced scaled wrapper input amount mismatch: "+inputAmount);
                var nativePushed=new KeyCounter();
                decoded.pushInputsToExternalInventory(extracted,nativePushed::add);
                if(nativePushed.get(AEItemKey.of(Items.IRON_INGOT))!=inputAmount)
                    throw new IllegalStateException("Advanced scaled wrapper native push amount mismatch: "+inputAmount);
                var target=AEItemKey.of(Items.GOLD_BLOCK);
                var scaledPlan=com.aecraftroute.addon.planner.NetworkProductionPlanner.plan(
                        target,6,k->k.equals(target)?List.of(wrapped):List.of(),
                        k->k.equals(AEItemKey.of(Items.IRON_INGOT))?inputAmount:0L);
                if(scaledPlan.patternTimes().getOrDefault(wrapped,0L)!=1)
                    throw new IllegalStateException("Advanced scaled wrapper was not planned as one physical push");
                var saved=new ProductionJob(scaledPlan).save(level.registryAccess());
                var loaded=ProductionJob.load(saved,level.registryAccess(),level);
                if(loaded.invalid() || !loaded.validateRemainingTasks(java.util.Map.of(decoded,1L)))
                    throw new IllegalStateException("Advanced scaled wrapper production job did not resume");
                var certifiedStock=new appeng.crafting.inv.ListCraftingInventory(k->{});
                certifiedStock.insert(AEItemKey.of(Items.IRON_INGOT),inputAmount,Actionable.MODULATE);
                var certifiedOutputs=new KeyCounter();
                var certifiedInputs=loaded.extract(decoded,certifiedStock,level,certifiedOutputs,new KeyCounter());
                if(certifiedInputs==null
                        || certifiedStock.extract(AEItemKey.of(Items.IRON_INGOT),inputAmount,Actionable.SIMULATE)!=0
                        || certifiedOutputs.get(target)!=6)
                    throw new IllegalStateException("Restored certified scaled step input/output amount mismatch");
                var certifiedPushed=new KeyCounter();
                decoded.pushInputsToExternalInventory(certifiedInputs,certifiedPushed::add);
                if(certifiedPushed.get(AEItemKey.of(Items.IRON_INGOT))!=inputAmount)
                    throw new IllegalStateException("Advanced scaled wrapper certified push amount mismatch: "+inputAmount);
                loaded.accepted(decoded);
                if(!loaded.done())throw new IllegalStateException("Certified scaled step did not complete");
                var provider=(PatternProviderBlockEntity)level.getBlockEntity(PROVIDER);
                provider.getLogic().getPatternInv().setItemDirect(1,definition);
                provider.getLogic().updatePatterns();
                long advertisedCount=grid.getCraftingService().getCraftingFor(target).stream()
                        .filter(p->p instanceof net.pedroksl.advanced_ae.common.patterns.AdvProcessingPattern).count();
                LOG.info("[SELFTEST] encoded AdvancedAE pattern advertised by plain AE2 provider: count={}",advertisedCount);
                var settings=Class.forName("com.extendedae_plus.api.config.EAPSettings");
                var smart=settings.getField("SMART_DOUBLING").get(null);
                var holder=Class.forName("com.extendedae_plus.api.smartDoubling.ISmartDoublingHolder");
                var manager=provider.getLogic().getConfigManager();
                var put=manager.getClass().getMethod("putSetting",appeng.api.config.Setting.class,Enum.class);
                var setLimit=holder.getMethod("eap$setProviderSmartDoublingLimit",int.class);
                try {
                    put.invoke(manager,smart,appeng.api.config.YesNo.YES);
                    setLimit.invoke(provider.getLogic(),6);
                    provider.getLogic().updatePatterns();
                    LOG.info("[SELFTEST] smart-doubling provider gold patterns: {}",
                            grid.getCraftingService().getCraftingFor(target).stream()
                                    .map(p->p.getClass().getName()).toList());
                } finally {
                    put.invoke(manager,smart,appeng.api.config.YesNo.NO);
                    setLimit.invoke(provider.getLogic(),0);
                    provider.getLogic().updatePatterns();
                }
                LOG.info("[SELFTEST] real AdvancedAE scaled wrapper sparseSlots={} condensedSlots={} inputAmount={} native/certified extraction+push and save/load passed",
                        sparseSlots,original.getInputs().length,inputAmount);
            } catch(ReflectiveOperationException error) {
                throw new IllegalStateException("Real AdvancedAE scaled wrapper unavailable",error);
            }
        }

        /**
         * Drive the whole-canvas apply path against this real grid: the same code the
         * packet handler calls, minus the packet. Two things must hold - all three loop
         * recipes lock cleanly and the verdict is the certified plan; and a canvas that
         * asks for two different recipes on one material is reported, not silently applied.
         */
        private void verifyCanvasApply() {
            var regs = level.registryAccess();
            var target = (AEKey) AEItemKey.of(Items.DIAMOND);
            long want = 3; // this phase runs before the submit phase picks its amount
            var nodes = new java.util.ArrayList<com.aecraftroute.addon.route.RouteApply.Node>();
            var seen = new java.util.HashSet<AEKey>();
            for (var item : java.util.List.of(Items.DIAMOND, Items.LAPIS_LAZULI, Items.EMERALD)) {
                var key = AEItemKey.of(item);
                if (!seen.add(key)) continue;
                var patterns = grid.getCraftingService().getCraftingFor(key);
                if (patterns.isEmpty()) continue;
                var identity = com.aecraftroute.addon.route.RouteIdentity.pattern(patterns.iterator().next(), regs);
                nodes.add(new com.aecraftroute.addon.route.RouteApply.Node(
                        com.aecraftroute.addon.route.RouteView.Stack.of(key, 1, regs).key(),
                        identity, key.getDisplayName().getString()));
            }
            var identities=nodes.stream().map(com.aecraftroute.addon.route.RouteApply.Node::choiceId).collect(java.util.stream.Collectors.toSet());
            var origins=com.aecraftroute.addon.route.RoutePatternSources.find(grid,level,identities);
            for(String identity:identities) {
                var rows=origins.get(identity);
                if(rows==null || rows.isEmpty() || rows.getFirst().icon()==null || rows.getFirst().location().isBlank())
                    fail("native pattern source mapping missing: "+identity);
            }
            LOG.info("[SELFTEST] native pattern sources: providers={} patterns={}",
                    com.aecraftroute.addon.route.RouteProviders.active(grid).size(),origins.size());
            var limits = new com.aecraftroute.addon.planner.ProductionPlanner.Limits(6000, 500_000_000L);
            var clean = com.aecraftroute.addon.route.RouteApply.apply(grid,
                    new com.aecraftroute.addon.route.RoutePolicy<AEKey>(java.util.Map.of()),
                    regs, target, want, nodes, limits);
            LOG.info("[SELFTEST] apply clean: locked={} problems={} verdict={} startup={}",
                    clean.locked(), clean.problems(), clean.plan().verdict(), clean.plan().startup());
            if (!clean.problems().isEmpty()) fail("canvas apply reported problems for a valid canvas: " + clean.problems());
            if (clean.locked().size() != nodes.size()) fail("canvas apply locked " + clean.locked().size() + "/" + nodes.size());
            if (!clean.plan().proven()) fail("canvas apply did not prove the plan: " + clean.plan().message());
            // One search state cannot produce this multi-step loop witness. A
            // successful repeat proves the exact-cache path ran on a real grid,
            // rather than merely another fast cold calculation.
            long hitsBefore=com.aecraftroute.addon.planner.NetworkProductionPlanner.cacheHits();
            var cached = com.aecraftroute.addon.api.CraftRouteCrafting.planNow(grid, clean.policy(), regs, target, want,
                    new com.aecraftroute.addon.planner.ProductionPlanner.Limits(1, 500_000_000L));
            if (cached.usedItems().get(AEItemKey.of(Items.EMERALD)) != 1 || cached.patternTimes().size() != 3)
                fail("cached witness changed startup capital or lost a loop step");
            if(com.aecraftroute.addon.planner.NetworkProductionPlanner.cacheHits()<=hitsBefore)
                fail("repeat plan did not actually hit the exact cache");
            LOG.info("[SELFTEST] exact plan cache hit: one-state budget, startup=1 emerald, patterns={}",
                    cached.patternTimes().size());
            for (var node : nodes) {
                var material = com.aecraftroute.addon.route.RouteView.decodeKey(node.keyTag(), regs);
                if (!node.choiceId().equals(clean.policy().rule(material).locked())) {
                    fail("canvas apply did not lock " + node.label());
                }
            }

            // Returned locks are worthless unless they actually constrain the calculation.
            // Same grid, same target, only the applied rules change: banning the only
            // diamond pattern must make this request undecidable, not still feasible.
            String diamondIdentity = null;
            var diamondKey = AEItemKey.of(Items.DIAMOND);
            for (var node : nodes) {
                if (diamondKey.equals(com.aecraftroute.addon.route.RouteView.decodeKey(node.keyTag(), regs))) {
                    diamondIdentity = node.choiceId();
                }
            }
            if (diamondIdentity == null) {
                fail("canvas apply did not cover the target material");
                return;
            }
            var restricted = clean.policy().change(diamondKey, "ban", diamondIdentity);
            String restrictedVerdict;
            try {
                var restrictedPlan = com.aecraftroute.addon.api.CraftRouteCrafting.planNow(
                        grid, restricted, regs, target, want, limits);
                restrictedVerdict = restrictedPlan != null ? "feasible" : "no plan";
            } catch (com.aecraftroute.addon.planner.ProductionPlanningException unreachable) {
                restrictedVerdict = unreachable.reason().name();
            }
            LOG.info("[SELFTEST] apply policy honoured: banning the diamond pattern turns the same request into {}",
                    restrictedVerdict);
            if ("feasible".equals(restrictedVerdict)) {
                fail("the applied canvas policy did not constrain the calculation");
            }
            if ("no plan".equals(restrictedVerdict)) {
                fail("planning against the applied policy produced no verdict at all");
            }

            // Same material, two different recipes: the canvas cannot express that with
            // per-material locks, so it must come back as a conflict.
            var conflicting = new java.util.ArrayList<com.aecraftroute.addon.route.RouteApply.Node>();
            var diamond = AEItemKey.of(Items.DIAMOND);
            conflicting.add(new com.aecraftroute.addon.route.RouteApply.Node(
                    com.aecraftroute.addon.route.RouteView.Stack.of(diamond, 1, regs).key(), "recipe:one", "钻石"));
            conflicting.add(new com.aecraftroute.addon.route.RouteApply.Node(
                    com.aecraftroute.addon.route.RouteView.Stack.of(diamond, 1, regs).key(), "recipe:two", "钻石"));
            var conflict = com.aecraftroute.addon.route.RouteApply.apply(grid,
                    new com.aecraftroute.addon.route.RoutePolicy<AEKey>(java.util.Map.of()),
                    regs, target, want, conflicting, limits);
            LOG.info("[SELFTEST] apply conflict: locked={} problems={}", conflict.locked(), conflict.problems());
            if (conflict.problems().size() != 1) fail("expected exactly one conflict, got " + conflict.problems());
            if (!conflict.locked().isEmpty()) fail("a conflicting canvas must not lock anything");
        }

        private ItemStack pattern(String recipeName, net.minecraft.world.item.Item in,
                                  net.minecraft.world.item.Item outItem) {
            var holder = recipe(recipeName);
            if (holder.isEmpty()) {
                LOG.error("[SELFTEST] recipe selftest:{} not found", recipeName);
                return ItemStack.EMPTY;
            }
            ItemStack[] grid = new ItemStack[9];
            java.util.Arrays.fill(grid, ItemStack.EMPTY);
            grid[4] = new ItemStack(in);
            ItemStack output = holder.get().value().getResultItem(level.registryAccess());
            return PatternDetailsHelper.encodeCraftingPattern(holder.get(), grid, output, false, false);
        }

        @SuppressWarnings("unchecked")
        private Optional<RecipeHolder<CraftingRecipe>> recipe(String name) {
            var holder = server.getRecipeManager()
                    .byKey(ResourceLocation.fromNamespaceAndPath("selftest", name));
            if (holder.isEmpty() || !(holder.get().value() instanceof CraftingRecipe)) {
                return Optional.empty();
            }
            return Optional.of((RecipeHolder<CraftingRecipe>) holder.get());
        }

        // --------------------------------------------------------- SUBMIT

        private void submit() {
            requested = switch (task) {
                case "cancel" -> 6;
                case "legacy-feedback", "legacy-feedback-advanced", "legacy-feedback-control",
                        "legacy-feedback-reload", "legacy-feedback-advanced-reload",
                        "legacy-feedback-restart1", "legacy-feedback-advanced-restart1",
                        "legacy-feedback-seed-rejectonce", "legacy-feedback-advanced-seed-rejectonce",
                        "legacy-feedback-corrupt", "legacy-feedback-advanced-corrupt" -> 2;
                case "reload" -> 4;
                case "paths" -> 1;
                case "vanilla" -> 1;
                case "planning-load" -> 1;
                case "vanilla-canvas" -> 1;
                case "vanilla-canvas-advanced" -> 1;
                case "scaled-advanced" -> 1;
                case "scaled-native-plan" -> 6;
                case "scaled-processing-execution", "scaled-processing-plain-execution" -> 6;
                case "scaled-molecular-execution" -> 6;
                case "foreign-advanced-plus" -> 6;
                case "foreign" -> 1;
                case "foreign-advanced" -> 1;
                case "fallback" -> 1;
                case "fallback-small" -> 1;
                case "fallback-busy" -> 1;
                default -> 3;
            };
            var controller = (IGridConnectedBlockEntity) level.getBlockEntity(CONTROLLER);
            IGridNode node = controller.getMainNode().getNode();
            // A requester that only takes one item per call proves the CPU keeps the rest
            // and retries instead of losing it or finishing the job early.
            requester = new Requester(node, partial || legacyFeedback ? 1 : Long.MAX_VALUE, task.endsWith("rejectonce"));
            if(foreign) {
                foreignRequester=foreignProxy(requester);
                if(!com.aecraftroute.addon.api.CraftRouteCraftingRegistry.isUnregisteredThirdParty(
                        foreignRequester.getClass().getName()))
                    throw new IllegalStateException("Foreign requester fixture was accidentally registered");
            }
            if (partial) {
                LOG.info("[SELFTEST] partial-accept requester: at most 1 item per delivery call");
            }
            var service = grid.getCraftingService();
            if(legacyFeedback) {
                var counts=new java.util.LinkedHashMap<IPatternDetails,Long>();
                counts.put(service.getCraftingFor(AEItemKey.of(Items.EMERALD)).iterator().next(),2L);
                counts.put(service.getCraftingFor(AEItemKey.of(Items.DIAMOND)).iterator().next(),1L);
                var refuel=service.getCraftingFor(AEItemKey.of(Items.IRON_INGOT)).iterator().next();
                var candidates=refuel.getInputs()[0].getPossibleInputs();
                LOG.info("[SELFTEST] legacy refuel candidate inputs: {}",java.util.Arrays.toString(candidates));
                if(candidates.length<2 || !candidates[0].what().equals(AEItemKey.of(Items.LAPIS_LAZULI))
                        || java.util.Arrays.stream(candidates).noneMatch(s->s.what().equals(AEItemKey.of(Items.EMERALD))))
                    throw new IllegalStateException("Legacy fixture does not expose emerald as a non-primary candidate");
                counts.put(refuel,1L);
                if(!com.aecraftroute.addon.vm.TwoNodeGrowthPlanner.containsRing(counts.keySet()))
                    throw new IllegalStateException("Legacy fixture did not activate supported feedback ring");
                var used=new KeyCounter();used.add(AEItemKey.of(Items.DIAMOND),1);used.add(AEItemKey.of(Items.IRON_INGOT),1);
                if("legacy-feedback-control".equals(task))used.add(AEItemKey.of(Items.LAPIS_LAZULI),1);
                if(task.contains("-seed-"))used.add(AEItemKey.of(Items.EMERALD),1);
                requester.beforeDelivery=key->{if(!key.equals(AEItemKey.of(Items.EMERALD)))throw new IllegalStateException("Wrong legacy delivery key");};
                acceptPlan(new appeng.crafting.CraftingPlan(new appeng.api.stacks.GenericStack(AEItemKey.of(Items.EMERALD),2),
                        advanced?cpu.getAvailableStorage()+1:64,false,false,used,new KeyCounter(),new KeyCounter(),counts));
                return;
            }
            AEKey target = AEItemKey.of(vanilla?Items.IRON_BLOCK:Items.DIAMOND);
            if("planning-load".equals(task)) {startPlanningLoad();return;}
            if("scaled-molecular-execution".equals(task)) {
                pendingCalculation=service.beginCraftingCalculation(level,(ICraftingSimulationRequester)foreignRequester,
                        AEItemKey.of(Items.IRON_BLOCK),6,CalculationStrategy.CRAFT_LESS);
                phase=Phase.CALCULATING;phaseTicks=0;return;
            }
            if("scaled-native-plan".equals(task) || isScaledProcessingExecution(task) || "scaled-molecular-execution".equals(task)) {
                pendingCalculation=service.beginCraftingCalculation(level,
                        (ICraftingSimulationRequester)foreignRequester,AEItemKey.of(Items.GOLD_BLOCK),6,
                        CalculationStrategy.CRAFT_LESS);
                phase=Phase.CALCULATING;phaseTicks=0;
                return;
            }
            if(paths) {
                submitPathCalculation();
                return;
            }
            if(task.startsWith("vanilla-canvas")) {
                submitVanillaCanvasCalculation();
                return;
            }
            if(stockRace){submitStockRaceCalculation();return;}

            // Exercise the canvas preview path on the live grid: same snapshot builder,
            // same planner, short budget. This is what the route screen will call.
            try {
                var view = com.aecraftroute.addon.route.RoutePlanView.of(
                        com.aecraftroute.addon.api.CraftRouteCrafting.planNow(grid,
                                new com.aecraftroute.addon.route.RoutePolicy<AEKey>(java.util.Map.of()),
                                level.registryAccess(), target, requested,
                                new com.aecraftroute.addon.planner.ProductionPlanner.Limits(6000, 500_000_000L)),
                        requested,
                        (k, a) -> com.aecraftroute.addon.route.RouteView.Stack.of(k, a, level.registryAccess()));
                LOG.info("[SELFTEST] preview verdict={} startup={} steps={} bytes={}",
                        view.verdict(), view.startup(), view.steps(), view.bytes());
                if (!view.proven()) fail("preview did not prove the growth loop: " + view.message());
            } catch (com.aecraftroute.addon.planner.ProductionPlanningException e) {
                fail("preview failed: " + e.reason() + " " + e.getMessage());
            }

            try {
                pendingCalculation = service.beginCraftingCalculation(level,
                        foreign?(ICraftingSimulationRequester)foreignRequester:requester,target,
                        requested, CalculationStrategy.CRAFT_LESS);
            } catch (Throwable t) {
                fail("crafting calculation could not start: " + rootCause(t));
                finish();
                return;
            }
            LOG.info("[SELFTEST] calculation requested, waiting off the server thread");
            phase = Phase.CALCULATING;
            phaseTicks = 0;
        }

        private java.util.concurrent.CompletableFuture<CertifiedCraftingPlan> loadRequest() {
            return com.aecraftroute.addon.route.RoutePlanningService.planAsync(grid,
                    new com.aecraftroute.addon.route.RoutePolicy<AEKey>(java.util.Map.of()),level.registryAccess(),
                    AEItemKey.of(Items.IRON_BLOCK),1,new com.aecraftroute.addon.planner.ProductionPlanner.Limits(30_000,5_000_000_000L),()->!finished);
        }
        private void startPlanningLoad() {
            planningLoad=new ArrayList<>();
            for(int i=0;i<32;i++)planningLoad.add(loadRequest());
            var overflow=loadRequest();
            if(!overflow.isCompletedExceptionally())throw new IllegalStateException("33rd request exceeded admission bound without rejection");
            try {overflow.join();throw new IllegalStateException("Admission overflow succeeded");}
            catch(java.util.concurrent.CompletionException rejected) {
                if(!(rejected.getCause() instanceof com.aecraftroute.addon.planner.ProductionPlanningException failure)
                        || failure.reason()!=com.aecraftroute.addon.planner.ProductionPlanningException.Reason.LIMIT)
                    throw new IllegalStateException("Admission overflow was not LIMIT",rejected);
            }
            for(int i=0;i<8;i++)planningLoad.get(i).cancel(true);
            phase=Phase.CALCULATING;phaseTicks=0;
        }
        private boolean awaitPlanningLoad() {
            if(planningLoad.stream().anyMatch(f->!f.isDone())) {
                if(phaseTicks>200)throw new IllegalStateException("Concurrent planning did not terminate");
                return false;
            }
            int passed=0,limited=0,cancelled=0;
            for(var future:planningLoad) {
                if(future.isCancelled()){cancelled++;continue;}
                try {if(future.join()==null)throw new IllegalStateException("Null load plan");passed++;}
                catch(java.util.concurrent.CompletionException rejected) {
                    if(!(rejected.getCause() instanceof com.aecraftroute.addon.planner.ProductionPlanningException failure)
                            || failure.reason()!=com.aecraftroute.addon.planner.ProductionPlanningException.Reason.LIMIT)
                        throw new IllegalStateException("Concurrent planning returned an unexpected failure",rejected);
                    limited++;
                }
            }
            if(cancelled!=8 || passed==0 || passed+limited+cancelled!=32)
                throw new IllegalStateException("Concurrent request accounting inconsistent");
            LOG.info("[SELFTEST] real planning load: submitted=32 passed={} LIMIT={} cancelled={} admissionOverflow=LIMIT",passed,limited,cancelled);
            pendingCalculation=loadRequest().thenApply(p->(ICraftingPlan)p);
            planningLoadRecovered=true;phaseTicks=0;return true;
        }

        /** Same encoded QA pattern, but through whole-canvas apply before CPU submission. */
        private void submitVanillaCanvasCalculation() {
            var target=AEItemKey.of(Items.IRON_BLOCK);
            var regs=level.registryAccess();
            var pattern=grid.getCraftingService().getCraftingFor(target).stream().findFirst()
                    .orElseThrow(()->new IllegalStateException("QA iron-block pattern is not advertised"));
            var node=new com.aecraftroute.addon.route.RouteApply.Node(
                    com.aecraftroute.addon.route.RouteView.Stack.of(target,1,regs).key(),
                    com.aecraftroute.addon.route.RouteIdentity.pattern(pattern,regs),
                    target.getDisplayName().getString(),"root");
            var limits=new com.aecraftroute.addon.planner.ProductionPlanner.Limits(6000,500_000_000L);
            var applied=com.aecraftroute.addon.route.RouteApply.apply(grid,
                    new com.aecraftroute.addon.route.RoutePolicy<AEKey>(java.util.Map.of()),regs,
                    target,1,java.util.List.of(node),limits);
            if(!applied.problems().isEmpty() || !applied.plan().proven()
                    || applied.locked().size()!=1 || applied.policy().canvas()==null)
                throw new IllegalStateException("Vanilla canvas apply failed: "+applied.problems()+" "+applied.plan().message());
            LOG.info("[SELFTEST] vanilla canvas applied: locked={} verdict={}",
                    applied.locked(),applied.plan().verdict());
            pendingCalculation=com.aecraftroute.addon.route.RoutePlanningService.planAsync(
                    grid,applied.policy(),regs,target,1,limits,()->!finished)
                    .thenApply(p->(ICraftingPlan)p);
            phase=Phase.CALCULATING;phaseTicks=0;
        }

        private IPatternDetails pathPattern(net.minecraft.world.item.Item output,net.minecraft.world.item.Item firstInput) {
            return grid.getCraftingService().getCraftingFor(AEItemKey.of(output)).stream()
                    .filter(p->p.getInputs().length>0 && p.getInputs()[0].getPossibleInputs()[0].what().equals(AEItemKey.of(firstInput)))
                    .findFirst().orElseThrow(()->new IllegalStateException("Missing path fixture pattern "+firstInput+" -> "+output));
        }

        /** Same path wire contract as the client canvas, followed by the async
         * planning service and a normal real-CPU submitJob, not a solver-only check. */
        private void submitPathCalculation() {
            var target=AEItemKey.of(Items.IRON_BLOCK); var regs=level.registryAccess();
            var finalPattern=pathPattern(Items.IRON_BLOCK,Items.EMERALD);
            var emeraldPattern=pathPattern(Items.EMERALD,Items.LAPIS_LAZULI);
            pathDiamondLapis=pathPattern(Items.LAPIS_LAZULI,Items.DIAMOND);
            pathEmeraldLapis=pathPattern(Items.LAPIS_LAZULI,Items.EMERALD);
            var nodes=new ArrayList<com.aecraftroute.addon.route.RouteApply.Node>();
            java.util.function.BiConsumer<String,IPatternDetails> add=(path,p)->{
                var output=p.getOutputs().getFirst().what();
                nodes.add(new com.aecraftroute.addon.route.RouteApply.Node(
                        com.aecraftroute.addon.route.RouteView.Stack.of(output,1,regs).key(),
                        com.aecraftroute.addon.route.RouteIdentity.pattern(p,regs),output.getDisplayName().getString(),path));
            };
            add.accept("root",finalPattern);
            add.accept("root/0",emeraldPattern);
            add.accept("root/0/0",pathDiamondLapis);
            add.accept("root/1",pathEmeraldLapis);
            var limits=new com.aecraftroute.addon.planner.ProductionPlanner.Limits(30_000,3_000_000_000L);
            var applied=com.aecraftroute.addon.route.RouteApply.apply(grid,
                    new com.aecraftroute.addon.route.RoutePolicy<AEKey>(java.util.Map.of()),regs,target,1,nodes,limits);
            if(!applied.problems().isEmpty() || !applied.plan().proven() || applied.locked().size()!=4 || applied.policy().canvas()==null)
                throw new IllegalStateException("Path canvas apply failed: "+applied.problems()+" "+applied.plan().message());
            LOG.info("[SELFTEST] paths apply: two lapis occurrences select different physical patterns; locked={} verdict={}",
                    applied.locked(),applied.plan().verdict());
            // Java Future is invariant; the actual result still must remain the
            // certified concrete plan accepted by the normal submission path.
            pendingCalculation=com.aecraftroute.addon.route.RoutePlanningService.planAsync(grid,applied.policy(),regs,target,1,limits,
                    ()->!finished).thenApply(p->(ICraftingPlan)p);
            phase=Phase.CALCULATING; phaseTicks=0;
        }

        private void verifyPathDelivery() {
            if(requester.delivered!=1) fail("paths delivered "+requester.delivered+" instead of 1 iron block");
            if(pathObserver==null) { fail("paths has no native CPU task observer"); finish(); return; }
            long fromDiamond=pathObserver.executed(pathDiamondLapis),fromEmerald=pathObserver.executed(pathEmeraldLapis);
            if(fromDiamond<1 || fromEmerald<1) fail("path-selected lapis patterns did not both dispatch: diamond="+fromDiamond+" emerald="+fromEmerald);
            if(com.aecraftroute.addon.api.DispatchTrace.hosts().getOrDefault("ae2",0)<1) fail("paths did not attach to a real AE2 CPU");
            LOG.info("[SELFTEST] paths native dispatch observed: diamond_to_lapis={} emerald_to_lapis={} deliveredIronBlocks={} physicalPatterns={}",
                    fromDiamond,fromEmerald,requester.delivered,plan.patternTimes().size());
            LOG.info("[SELFTEST] PASS task=paths delivered={}",requester.delivered);
            finish();
        }

        private Object stockRaceField(String name) throws ReflectiveOperationException {
            var field=stockRaceJob.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(stockRaceJob);
        }
        @SuppressWarnings("unchecked")
        private void submitStockRaceCalculation() {
            try {
                var lapis=AEItemKey.of(Items.LAPIS_LAZULI);
                if(inventory().insert(lapis,4,Actionable.MODULATE,IActionSource.empty())!=4)
                    throw new IllegalStateException("cannot seed moving intermediate");
                var type=Class.forName("com.aecraftroute.addon.route.RoutePlanningService$Job");
                var ctor=type.getDeclaredConstructor(IGrid.class,com.aecraftroute.addon.route.RoutePolicy.class,
                        net.minecraft.core.HolderLookup.Provider.class,AEKey.class,long.class,
                        com.aecraftroute.addon.planner.ProductionPlanner.Limits.class,java.util.function.BooleanSupplier.class);
                ctor.setAccessible(true);
                stockRaceJob=ctor.newInstance(grid,new com.aecraftroute.addon.route.RoutePolicy<AEKey>(java.util.Map.of()),
                        level.registryAccess(),AEItemKey.of(Items.DIAMOND),requested,
                        new com.aecraftroute.addon.planner.ProductionPlanner.Limits(6000,5_000_000_000L),
                        (java.util.function.BooleanSupplier)()->!finished);
                stockRaceDeadline=(long)stockRaceField("deadline");
                pendingCalculation=(java.util.concurrent.Future<ICraftingPlan>)stockRaceField("result");
                phase=Phase.CALCULATING;phaseTicks=0;
            } catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        }
        /** Drives the REAL production Job and LiveCapture, but outside the global
         * tick queue so this isolated harness can deterministically remove actual
         * ME stock between worker completion and server-thread validation. */
        private void tickStockRace() {
            try {
                if(!stockRaceDrained && (boolean)stockRaceField("finished") && stockRaceField("plan")!=null) {
                    var before=(CertifiedCraftingPlan)stockRaceField("plan");var lapis=AEItemKey.of(Items.LAPIS_LAZULI);
                    long needed=before.usedItems().get(lapis);
                    if(needed<=0)throw new IllegalStateException("race fixture did not borrow intermediate stock");
                    long removed=inventory().extract(lapis,Long.MAX_VALUE,Actionable.MODULATE,IActionSource.empty());
                    if(removed<needed)throw new IllegalStateException("race fixture did not actually drain its required stock");
                    stockRaceDrained=true;
                    LOG.info("[SELFTEST] drained actual ME intermediate before validation: needed={} removed={}",needed,removed);
                }
                var tick=stockRaceJob.getClass().getDeclaredMethod("tick",long.class);tick.setAccessible(true);
                tick.invoke(stockRaceJob,System.nanoTime()+2_000_000L);
                if((long)stockRaceField("deadline")!=stockRaceDeadline)throw new IllegalStateException("retry extended request deadline");
                if(pendingCalculation.isDone() && !pendingCalculation.isCancelled()) {
                    if(!stockRaceDrained || (int)stockRaceField("stockReplans")!=1)
                        throw new IllegalStateException("real live-stock retry was not exercised exactly once");
                    var after=(CertifiedCraftingPlan)stockRaceField("plan");
                    if(after==null || after.usedItems().get(AEItemKey.of(Items.LAPIS_LAZULI))!=0)
                        throw new IllegalStateException("retry still borrowed the drained intermediate");
                    LOG.info("[SELFTEST] fresh upstream plan validated; original deadline retained; retries=1");
                }
            } catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        }
        /** Never blocks the server thread: AE2's own callers consume futures asynchronously. */
        private void calculating() {
            if("planning-load".equals(task) && !planningLoadRecovered) {awaitPlanningLoad();return;}
            if(stockRace)tickStockRace();
            if (pendingCalculation.isDone()) {
                ICraftingPlan calculated;
                try {
                    calculated = pendingCalculation.get();
                } catch (Throwable t) {
                    fail("crafting calculation failed: " + rootCause(t));
                    finish();
                    return;
                }
                acceptPlan(calculated);
                return;
            }
            if (phaseTicks > 1200) {
                dumpCalculatorThreads();
                fail("calculation did not finish within 1200 ticks");
                finish();
            }
        }

        private void acceptPlan(ICraftingPlan calculated) {
            plan = calculated;
            if(advanced && foreign && plan instanceof appeng.crafting.CraftingPlan nativePlan) {
                // QA-only capacity requirement excludes the plain 1k CPU. Executor
                // ownership is still verified independently through the crafting link.
                plan=new appeng.crafting.CraftingPlan(nativePlan.finalOutput(),cpu.getAvailableStorage()+1,
                        nativePlan.simulation(),nativePlan.multiplePaths(),nativePlan.usedItems(),
                        nativePlan.emittedItems(),nativePlan.missingItems(),nativePlan.patternTimes());
            }
            if("scaled-native-plan".equals(task) || isScaledProcessingExecution(task) || "scaled-molecular-execution".equals(task)) {
                if(plan instanceof CertifiedCraftingPlan)
                    fail("unregistered requester was forced into certified planning");
                String wrapper="com.extendedae_plus.api.crafting.ScaledProcessingPattern"
                        +("scaled-processing-plain-execution".equals(task)?"":"Adv");
                if("scaled-molecular-execution".equals(task))wrapper="com.extendedae_plus.api.crafting.ScaledMolecularAssemblerPattern";
                final String expectedWrapper=wrapper;
                var scaled=plan.patternTimes().entrySet().stream().filter(e->e.getKey().getClass().getName()
                        .equals(expectedWrapper)).toList();
                if(scaled.size()!=1 || scaled.getFirst().getValue()!=1
                        || scaled.getFirst().getKey().getOutputs().getFirst().amount()!=6)
                    fail("ExtendedAE Plus native planner did not emit one six-output "+wrapper);
                if(com.aecraftroute.addon.api.DispatchTrace.certifiedAttaches()!=0)
                    fail("native smart-doubling plan attached a certified executor");
                LOG.info("[SELFTEST] native smart-doubling plan={} scaled={}",plan.getClass().getName(),scaled);
                if("scaled-native-plan".equals(task)){finish();return;}
            }
            if("foreign-advanced-plus".equals(task))
                LOG.info("[SELFTEST] foreign AdvancedAE/ExtendedAE Plus plan patterns: {}",plan.patternTimes());
            if(fallbackSmall && plan instanceof CertifiedCraftingPlan certified)
                plan=new CertifiedCraftingPlan(certified.finalOutput(),cpu.getAvailableStorage()+1,
                        certified.usedItems(),certified.patternTimes(),certified.program());
            if (foreign && plan instanceof CertifiedCraftingPlan) {
                fail("unregistered third-party requester was forced into certified planning");
                finish();return;
            }
            if (!foreign && !legacyFeedback && !(plan instanceof CertifiedCraftingPlan)) {
                fail("calculation did not return the certified planner result: "
                        + (plan == null ? "null" : plan.getClass().getName()));
                finish();
                return;
            }
            LOG.info("[SELFTEST] plan: bytes={} simulation={} emitted={} patterns={}",
                    plan.bytes(), plan.simulation(), describe(plan.emittedItems()),
                    plan.patternTimes().size());
            for (var entry : plan.patternTimes().entrySet()) {
                LOG.info("[SELFTEST] plan executes {} x{}", outputs(entry.getKey()), entry.getValue());
            }
            if(paths) {
                if(plan.patternTimes().getOrDefault(pathDiamondLapis,0L)<1 || plan.patternTimes().getOrDefault(pathEmeraldLapis,0L)<1)
                    throw new IllegalStateException("Certified path program omitted a selected physical lapis pattern");
                for(var block:((CertifiedCraftingPlan)plan).program()) for(var step:block.word()) {
                    if(step.recipe().pattern().getDefinition()==null) throw new IllegalStateException("Virtual pattern survived into CPU program");
                    for(var slot:step.recipe().slots()) for(var portion:slot.portions())
                        if(!(portion.template().what() instanceof AEItemKey)) throw new IllegalStateException("Virtual token survived into CPU input allocation");
                }
            }

            ICraftingRequester submitter = foreign?foreignRequester:restart1 && interfaceLogic != null ? interfaceLogic : requester;
            if(isScaledProcessingExecution(task))
                requester.beforeDelivery=key->{if(!key.equals(AEItemKey.of(Items.GOLD_BLOCK)))
                    throw new IllegalStateException("Scaled processor delivered the wrong material");};
            if("scaled-molecular-execution".equals(task))
                requester.beforeDelivery=key->{if(!key.equals(AEItemKey.of(Items.IRON_BLOCK)))
                    throw new IllegalStateException("Super matrix delivered the wrong material");};
            if(fallbackBusy) {
                var blocker=new Requester(((IGridConnectedBlockEntity)level.getBlockEntity(CONTROLLER)).getMainNode().getNode());
                var occupied=grid.getCraftingService().submitJob(plan,blocker,cpu,false,blocker.getActionSource());
                if(!occupied.successful() || !cpu.isBusy())
                    throw new IllegalStateException("Could not occupy selected AE2 CPU: "+occupied.errorCode());
                if(occupied.link()!=null)blocker.jobStateChange(occupied.link());
            }
            ICraftingCPU requestedCpu=fallback
                    ?new CraftingCPUCluster(BASE.offset(30,0,0),BASE.offset(30,0,0))
                    :fallbackSmall || fallbackBusy?cpu:selectedCpu;
            if(fallback && ((CraftingCPUCluster)requestedCpu).isActive())
                throw new IllegalStateException("Fallback fixture CPU is not offline");
            ICraftingSubmitResult result = grid.getCraftingService().submitJob(plan, submitter, requestedCpu,
                    false, requester.getActionSource());
            if (!result.successful()) {
                fail("job submission refused: " + result.errorCode());
                finish();
                return;
            }
            if(foreign || fallbackBusy || legacyFeedback) {
                try {executingCpu=findNativeExecutingCpu(result.link());}
                catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
                boolean actualAdvanced=executingCpu.getClass().getName().startsWith("net.pedroksl.advanced_ae.");
                if(actualAdvanced!=advanced)throw new IllegalStateException("Native plan ran on wrong executor family: "+executingCpu.getClass().getName());
                LOG.info("[SELFTEST] native-protocol actual CPU confirmed busy: {}",executingCpu.getClass().getName());
            }
            else {
                try {certifiedLogic();}catch(Throwable failure){fail("cannot observe actual executing CPU: "+rootCause(failure));finish();return;}
                verifyExternalBatchIsolation();
                verifyThunderboltBatchIsolation();
            }
            if(task.endsWith("overestimate")) addOverestimatedReturns();
            link = result.link();
            if (link != null) {
                requester.jobStateChange(link);
                if (submitter != requester) {
                    submitter.jobStateChange(link);
                }
            }
            submitted = true;
            LOG.info("[SELFTEST] job submitted: target={} x{}",plan.finalOutput().what(),requested);
            if(paths) {
                pathObserver=new CpuTaskObserver(cpu,plan.patternTimes());
                requester.beforeDelivery=(what)->{
                    if(!what.equals(AEItemKey.of(Items.IRON_BLOCK))) throw new IllegalStateException("CPU attempted virtual/wrong-key delivery: "+what);
                    pathObserver.sample();
                };
                pathObserver.sample();
                LOG.info("[SELFTEST] paths certified program submitted to real AE2 CPU; target=iron_block x1");
            }
            phase = Phase.RUN;
            phaseTicks = 0;
        }

        /** Runs against the actual Mixin-transformed NeoECO dispatcher and live CPU.
         * A poison job fails on any task access: the ownership guard must return before
         * the foreign batch path can read or mutate a certified job. Development only. */
        private void verifyExternalBatchIsolation() {
            if(!net.neoforged.fml.ModList.get().isLoaded("neoecoae"))return;
            try {
                Object owner=null;
                for(var candidate:grid.getCraftingService().getCpus()) {
                    if(!candidate.isBusy())continue;
                    Object logic=candidate.getClass().getField("craftingLogic").get(candidate);
                    if(com.aecraftroute.addon.compat.CertifiedExecutionOwner.blocksExternalBatch(logic)) {owner=logic;break;}
                }
                if(owner==null)throw new AssertionError("Live certified CPU has no execution ownership marker");
                Class<?> jobType=Class.forName("cn.dancingsnow.neoecoae.crafting.execution.ECOExternalCpuJob");
                Object poison=java.lang.reflect.Proxy.newProxyInstance(jobType.getClassLoader(),new Class<?>[]{jobType},
                        (proxy,method,args)->{throw new AssertionError("Foreign batch dispatcher accessed certified job: "+method.getName());});
                Class<?> type=Class.forName("cn.dancingsnow.neoecoae.crafting.execution.ECOExternalCpuFastPath");
                Object dispatcher=type.getConstructor(Runnable.class).newInstance((Runnable)()->{throw new AssertionError("Foreign batch mutated CPU");});
                Object result=type.getMethod("execute",Object.class,Object.class,appeng.crafting.inv.ListCraftingInventory.class,
                        int.class,appeng.me.service.CraftingService.class,appeng.api.networking.energy.IEnergyService.class,net.minecraft.world.level.Level.class)
                        .invoke(dispatcher,owner,poison,null,1,null,null,null);
                if(!Integer.valueOf(0).equals(result))throw new AssertionError("Expected native-dispatch fallback, got "+result);
                LOG.info("[SELFTEST] external batch bypass blocked before task access; cpu={}",owner.getClass().getName());
            } catch(Throwable failure) {fail("external batch isolation failed: "+rootCause(failure));}
        }

        private Object certifiedLogic() throws ReflectiveOperationException {
            for(var candidate:grid.getCraftingService().getCpus()) {
                if(!candidate.isBusy())continue;
                Object logic=candidate.getClass().getField("craftingLogic").get(candidate);
                if(com.aecraftroute.addon.compat.CertifiedExecutionOwner.blocksExternalBatch(logic)){executingCpu=candidate;return logic;}
            }
            throw new IllegalStateException("No active certified CPU found");
        }
        private void verifyThunderboltBatchIsolation() {
            if(!net.neoforged.fml.ModList.get().isLoaded("thunderbolt"))return;
            try {
                Object owner=certifiedLogic();
                Class<?> type=Class.forName("com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor");
                var eligibility=type.getDeclaredMethod("isBatchEligible",IPatternDetails.class);eligibility.setAccessible(true);
                var pattern=plan.patternTimes().keySet().iterator().next();
                boolean ordinary=(boolean)eligibility.invoke(null,pattern);
                com.aecraftroute.addon.compat.CertifiedExecutionScope.run(owner,()->{
                    try {if((boolean)eligibility.invoke(null,pattern))throw new AssertionError("External batch dispatcher retained ownership");}
                    catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
                });
                if((boolean)eligibility.invoke(null,pattern)!=ordinary)throw new AssertionError("Ordinary batch eligibility changed after scope exit");
                LOG.info("[SELFTEST] registered batch eligibility isolated; cpu={} ordinaryRestored={}",owner.getClass().getName(),ordinary);
            } catch(Throwable failure){fail("batch eligibility isolation failed: "+rootCause(failure));}
        }
        /** Real production still comes from real providers. Only the estimate is
         * deliberately wrong; it must not withhold actual goal stock or CPU release. */
        private void addOverestimatedReturns() {
            try {
                Object logic=certifiedLogic();var field=logic.getClass().getDeclaredField("job");field.setAccessible(true);
                Object nativeJob=field.get(logic);appeng.crafting.inv.ListCraftingInventory waiting;
                if(nativeJob instanceof com.aecraftroute.addon.mixin.GrowthJobAccessor access)waiting=access.aecraftroute$getWaitingFor();
                else waiting=((com.aecraftroute.addon.mixin.AdvancedGrowthJobAccessor)nativeJob).aecraftroute$getWaitingFor();
                waiting.insert(plan.finalOutput().what(),17,Actionable.MODULATE);
                waiting.insert(AEItemKey.of(Items.STICK),13,Actionable.MODULATE);
                LOG.info("[SELFTEST] injected overestimated target/byproduct ledger only: targetExtra=17 byproductExtra=13");
            } catch(Throwable failure){fail("cannot arrange overestimated return test: "+rootCause(failure));}
        }

        // ------------------------------------------------------------ RUN

        private void run() {
            if(paths && pathObserver!=null) pathObserver.sample();
            if(legacyFeedback && (task.endsWith("reload") || task.endsWith("corrupt") || legacyRestart1) && !reloaded) {
                reloadLegacyFeedbackIfBuffered();
                if(finished)return;
            }
            if (restart1) {
                executingCpu=interfaceExecutingCpu();
                if (executingCpu!=null && executingCpu.isBusy()) {
                    if (phaseTicks > 20 && !restartSaved) {
                        saveForRestart();
                    }
                    return;
                }
                if (phaseTicks > 600) {
                    fail("the restarted job never started running");
                    finish();
                }
                return;
            }
            if (task.equals("reload") && !reloaded && phaseTicks > 40) {
                reloadCpu();
            }
            if (task.equals("cancel") && !cancelled && phaseTicks > 60) {
                cancelled = true;
                cancelTick = phaseTicks;
                if (link != null) {
                    link.cancel();
                }
                LOG.info("[SELFTEST] job cancellation requested");
            }

            if (cancelled) {
                if (!cpu.isBusy() || phaseTicks > cancelTick + 400) {
                    verifyCancel();
                }
                return;
            }

            if (requester.delivered >= requested) {
                if(executingCpu!=null && executingCpu.isBusy()) {
                    if(++completionTicks>100){fail("target delivered but the actual CPU did not release the job");finish();}
                    return;
                }
                LOG.info("[SELFTEST] actual executing CPU released after target delivery");
                verifyDelivery();
                return;
            }
            if (phaseTicks > (legacyFeedback?600:2400)) {
                fail("timed out waiting for delivery: delivered=" + requester.delivered
                        + "/" + requested + " executingCpu=" + com.aecraftroute.addon.telemetry.CpuProbe.describe(
                                executingCpu!=null?executingCpu:selectedCpu)
                        + " stock=" + stock());
                finish();
            }
        }

        /** Snapshot only after an actual feedback packet is held, not merely after submit. */
        private void reloadLegacyFeedbackIfBuffered() {
            try {
                var write=executingCpu.getClass().getMethod("writeToNBT",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);
                var read=executingCpu.getClass().getMethod("readFromNBT",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);
                var tag=new CompoundTag();write.invoke(executingCpu,tag,level.registryAccess());
                if(!legacyReloadProbeLogged) {
                    legacyReloadProbeLogged=true;
                    LOG.info("[SELFTEST] legacy reload probe: executingBusy={} job={} keys={}",
                            executingCpu.isBusy(),legacyNativeJob(),tag.getAllKeys());
                }
                long held=tag.getLong("aecraftrouteDeferredHeld");
                if(held<=0) {
                    if(!legacyReloadPaused) {
                        Object nativeJob=legacyNativeJob();
                        if(nativeJob!=null) {
                            var waiting=nativeJob instanceof com.aecraftroute.addon.mixin.GrowthJobAccessor access
                                    ?access.aecraftroute$getWaitingFor()
                                    :((com.aecraftroute.addon.mixin.AdvancedGrowthJobAccessor)nativeJob).aecraftroute$getWaitingFor();
                            if(waiting.extract(AEItemKey.of(Items.EMERALD),Long.MAX_VALUE,Actionable.SIMULATE)>0) {
                                var suspended=nativeJob.getClass().getDeclaredField("suspended");suspended.setAccessible(true);
                                suspended.setBoolean(nativeJob,true);legacyReloadPaused=true;
                                LOG.info("[SELFTEST] legacy feedback paused after dispatch, before return; cpu={}",executingCpu.getClass().getName());
                            }
                        }
                    }
                    return;
                }
                if(!tag.getBoolean("aecraftrouteDeferredFeedback") || !tag.contains("job"))
                    throw new IllegalStateException("Buffered feedback has no saved protocol/job marker");
                if(task.endsWith("corrupt")) {
                    tag.putLong("aecraftrouteDeferredHeld",Math.addExact(held,1));
                    read.invoke(executingCpu,tag,level.registryAccess());
                    long returned=inventory().extract(AEItemKey.of(Items.EMERALD),Long.MAX_VALUE,Actionable.SIMULATE,IActionSource.empty());
                    if(executingCpu.isBusy() || requester.delivered!=0 || returned!=held || !link.isCanceled())
                        fail("Corrupt feedback save did not cancel and return only actual held stock: busy="
                                +executingCpu.isBusy()+" delivered="+requester.delivered+" ME="+returned+" held="+held+" cancelled="+link.isCanceled());
                    LOG.info("[SELFTEST] corrupt deferred save rejected: actualCpu={} actualHeld={} returnedME={} requesterDelivered={}",
                            executingCpu.getClass().getName(),held,returned,requester.delivered);
                    finish();return;
                }
                if(legacyRestart1) {
                    if(!legacyReloadPaused || requester.delivered!=0)
                        throw new IllegalStateException("Restart snapshot must precede delivery and remain suspended");
                    var fixture=new CompoundTag();var requesterTag=new CompoundTag();
                    ((appeng.crafting.CraftingLink)link).writeToNBT(requesterTag);
                    fixture.put("link",requesterTag);fixture.putLong("held",held);fixture.putBoolean("advanced",advanced);
                    try(var output=new java.io.DataOutputStream(Files.newOutputStream(gameDirectory().resolve("legacy-feedback-requester.nbt")))) {
                        net.minecraft.nbt.NbtIo.write(fixture,output);
                    }
                    server.saveEverything(true,true,true);restartSaved=true;
                    LOG.info("[SELFTEST] legacy restart1 world saved: actualCpu={} held={} link={} requesterDelivered=0",
                            executingCpu.getClass().getName(),held,link.getCraftingID());
                    finish();return;
                }
                var bytes=new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.write(tag,new java.io.DataOutputStream(bytes));
                var restored=net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(
                        new java.io.ByteArrayInputStream(bytes.toByteArray())),net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                read.invoke(executingCpu,restored,level.registryAccess());
                var checked=new CompoundTag();write.invoke(executingCpu,checked,level.registryAccess());
                if(!executingCpu.isBusy() || !checked.getBoolean("aecraftrouteDeferredFeedback")
                        || checked.getLong("aecraftrouteDeferredHeld")!=held)
                    throw new IllegalStateException("Reload lost live deferred feedback credit");
                if(legacyReloadPaused) {
                    Object nativeJob=legacyNativeJob();
                    var suspended=nativeJob.getClass().getDeclaredField("suspended");suspended.setAccessible(true);
                    suspended.setBoolean(nativeJob,false);
                }
                reloaded=true;
                LOG.info("[SELFTEST] legacy feedback CPU NBT bytes round-trip: cpu={} held={} bytes={}",
                        executingCpu.getClass().getName(),held,bytes.size());
            } catch(ReflectiveOperationException | java.io.IOException failure) {throw new IllegalStateException(failure);}
        }

        private Object legacyNativeJob() throws ReflectiveOperationException {
            Object logic=executingCpu.getClass().getField("craftingLogic").get(executingCpu);
            var field=logic.getClass().getDeclaredField("job");field.setAccessible(true);return field.get(logic);
        }

        /** Fresh process: CPU comes from the world; only the QA requester/link is reconstructed. */
        private void resumeLegacyFeedback() {
            if(resumed){run();return;}
            try {
                level=server.overworld();level.getChunk(BASE);
                if(advanced){level.getChunk(QUANTUM_MIN);level.getChunk(QUANTUM_MAX);}
                if(!(level.getBlockEntity(CONTROLLER) instanceof IGridConnectedBlockEntity controller)
                        || controller.getMainNode().getNode()==null) {timeout("legacy restart grid is not ready");return;}
                grid=controller.getMainNode().getNode().getGrid();
                var fixture=new CompoundTag();
                try(var input=new java.io.DataInputStream(Files.newInputStream(gameDirectory().resolve("legacy-feedback-requester.nbt")))) {
                    fixture=net.minecraft.nbt.NbtIo.read(input,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                }
                if(fixture.getBoolean("advanced")!=advanced)throw new IllegalStateException("Legacy restart executor fixture mismatch");
                if(requester==null) {
                    requester=new Requester(controller.getMainNode().getNode(),1);requested=2;
                    link=new appeng.crafting.CraftingLink(fixture.getCompound("link"),requester);
                    requester.jobStateChange(link);
                    ((appeng.me.service.CraftingService)grid.getCraftingService()).addLink((appeng.crafting.CraftingLink)link);
                }
                try {executingCpu=findNativeExecutingCpu(link);}
                catch(IllegalStateException notReady){timeout("legacy restart CPU/link not restored yet");return;}
                if(executingCpu.getClass().getName().startsWith("net.pedroksl.advanced_ae.")!=advanced)
                    throw new IllegalStateException("Legacy restart restored wrong executor family");
                selectedCpu=executingCpu;
                if(executingCpu instanceof CraftingCPUCluster nativeCpu)cpu=nativeCpu;
                var tag=new CompoundTag();
                executingCpu.getClass().getMethod("writeToNBT",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class)
                        .invoke(executingCpu,tag,level.registryAccess());
                if(!tag.getBoolean("aecraftrouteDeferredFeedback") || tag.getLong("aecraftrouteDeferredHeld")!=fixture.getLong("held")
                        || fixture.getLong("held")<=0)
                    throw new IllegalStateException("Legacy restart lost held feedback from the actual world save");
                Object nativeJob=legacyNativeJob();
                var suspended=nativeJob.getClass().getDeclaredField("suspended");suspended.setAccessible(true);
                if(!suspended.getBoolean(nativeJob))throw new IllegalStateException("Legacy restart job was not suspended in save");
                suspended.setBoolean(nativeJob,false);
                resumed=true;phaseTicks=0;
                LOG.info("[SELFTEST] legacy restart2 resumed world CPU={} held={} link={}",
                        executingCpu.getClass().getName(),fixture.getLong("held"),link.getCraftingID());
            } catch(ReflectiveOperationException | java.io.IOException failure) {throw new IllegalStateException(failure);}
        }

        private ICraftingCPU findNativeExecutingCpu(ICraftingLink submitted) throws ReflectiveOperationException {
            if(submitted==null)throw new IllegalStateException("Native QA requester has no submitted link");
            for(var candidate:grid.getCraftingService().getCpus()) {
                if(!candidate.isBusy())continue;
                Object logic=candidate.getClass().getField("craftingLogic").get(candidate);
                var field=logic.getClass().getDeclaredField("job");field.setAccessible(true);
                Object nativeJob=field.get(logic);if(nativeJob==null)continue;
                var cpuLink=nativeJob instanceof com.aecraftroute.addon.mixin.GrowthJobAccessor access
                        ?access.aecraftroute$getLink()
                        :((com.aecraftroute.addon.mixin.AdvancedGrowthJobAccessor)nativeJob).aecraftroute$getLink();
                if(cpuLink.getCraftingID().equals(submitted.getCraftingID()))return candidate;
            }
            throw new IllegalStateException("No busy CPU owns the submitted native crafting link");
        }

        private void reloadCpu() {
            reloaded = true;
            CompoundTag tag = new CompoundTag();
            cpu.writeToNBT(tag, level.registryAccess());
            boolean hasJob = tag.contains("job");
            boolean hasProduction = tag.contains(ProductionJob.TAG);
            long deliveredBefore = requester.delivered;
            LOG.info("[SELFTEST] CPU saved to NBT: job={} productionProgram={} bytes={}",
                    hasJob, hasProduction, tag.size());
            if (!hasJob) {
                fail("CPU NBT has no job while a job is in flight");
            }
            if (!hasProduction) {
                fail("CPU NBT has no certified production program");
            }
            cpu.readFromNBT(tag, level.registryAccess());
            LOG.info("[SELFTEST] CPU reloaded from the same NBT: delivered so far={}", deliveredBefore);
        }

        /**
         * Make the real ME interface request the craft on its own: a configured slot plus a
         * crafting card is exactly what a player does. AE2 then stores the crafting link in
         * the interface's own MultiCraftingTracker, which is the only copy a world save keeps.
         */
        private void armInterface() {
            var config = interfaceLogic.getConfig();
            config.setStack(0, new appeng.api.stacks.GenericStack(AEItemKey.of(Items.DIAMOND), 3));
            var upgrades = interfaceLogic.getUpgrades();
            if (!upgrades.isInstalled(AEItems.CRAFTING_CARD)) {
                upgrades.addItems(AEItems.CRAFTING_CARD.stack());
            }
            LOG.info("[SELFTEST] restart1: ME interface asks for diamond x3 by itself (config={} craftingCard={})",
                    config.getStack(0), upgrades.isInstalled(AEItems.CRAFTING_CARD));
        }

        /**
         * First half of the real-restart scenario: stop the server while a certified job is
         * in flight, after proving the CPU NBT carries both the native job and our program.
         */
        private void saveForRestart() {
            restartSaved = true;
            CompoundTag tag = new CompoundTag();
            try {executingCpu.getClass().getMethod("writeToNBT",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class)
                    .invoke(executingCpu,tag,level.registryAccess());}
            catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
            boolean hasJob = tag.contains("job");
            boolean hasProduction = tag.contains(ProductionJob.TAG);
            int interfaceJobs = interfaceLogic.getRequestedJobs().size();
            int attaches = com.aecraftroute.addon.api.DispatchTrace.certifiedAttaches();
            LOG.info("[SELFTEST] restart1: cpu busy={} NBT job={} productionProgram={} bytes={} interfaceJobs={} certified={}",
                    executingCpu.isBusy(), hasJob, hasProduction, tag.size(), interfaceJobs, attaches);
            if (attaches < 1) {
                fail("the interface's own calculation did not receive a certified program");
            }
            if (!executingCpu.isBusy()) {
                fail("the CPU was already idle when the restart snapshot was taken");
            }
            if (!hasJob) {
                fail("CPU NBT has no job when saving for a restart");
            }
            if (!hasProduction) {
                fail("CPU NBT has no certified production program when saving for a restart");
            }
            if (interfaceJobs != 1) {
                fail("the requesting ME interface stored " + interfaceJobs + " jobs, expected 1");
            }
            var savedLink=interfaceLogic.getRequestedJobs().iterator().next().getCraftingID();
            try {Files.writeString(gameDirectory().resolve("machine-restart-link.txt"),savedLink.toString(),StandardCharsets.UTF_8);}
            catch(java.io.IOException error){throw new IllegalStateException(error);}
            LOG.info("[SELFTEST] restart1: persisted machine link={} actualCpu={}",savedLink,executingCpu.getClass().getName());
            server.saveEverything(true, true, true);
            LOG.info("[SELFTEST] restart1: world saved with the job still running, stopping the server");
            finish();
        }
        private ICraftingCPU interfaceExecutingCpu() {
            for(var request:interfaceLogic.getRequestedJobs())try {
                var actual=findNativeExecutingCpu(request);
                if(actual.getClass().getName().startsWith("net.pedroksl.advanced_ae.")!=advanced)
                    throw new AssertionError("ME interface job ran on wrong CPU family: "+actual.getClass().getName());
                return actual;
            } catch(IllegalStateException notYetLinked) {
                // The machine may publish its requester link before the CPU is visible.
            } catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
            return null;
        }

        /**
         * Second half of the real-restart scenario: the world was saved while a certified
         * job was still running and the server was stopped. On this fresh process the CPU
         * must come back busy with the same program (its NBT survived), finish the job and
         * deliver to the same ME interface, which persists its requested jobs.
         */
        private void restartResume() {
            try {
                level = server.overworld();
                if (level == null) {
                    return;
                }
                level.getChunk(BASE);
                var controller = level.getBlockEntity(CONTROLLER);
                if (!(controller instanceof IGridConnectedBlockEntity networked)
                        || networked.getMainNode() == null || networked.getMainNode().getGrid() == null) {
                    timeout("network did not come back after the restart");
                    return;
                }
                grid = networked.getMainNode().getGrid();
                var iface = level.getBlockEntity(INTERFACE);
                if (!(iface instanceof appeng.helpers.InterfaceLogicHost host)) {
                    timeout("the ME interface did not come back after the restart");
                    return;
                }
                interfaceLogic = host.getInterfaceLogic();
                if (!resumed) {
                    String expectedLink=Files.readString(gameDirectory().resolve("machine-restart-link.txt"),StandardCharsets.UTF_8).trim();
                    if(interfaceLogic.getRequestedJobs().stream().noneMatch(l->l.getCraftingID().toString().equals(expectedLink))) {
                        timeout("the persisted machine link has not returned; refusing to accept a newly requested replacement job");return;
                    }
                    executingCpu=interfaceExecutingCpu();
                    if (executingCpu!=null && executingCpu.isBusy()) {
                        var restored=new CompoundTag();
                        executingCpu.getClass().getMethod("writeToNBT",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class)
                                .invoke(executingCpu,restored,level.registryAccess());
                        if(!restored.contains("job") || !restored.contains(ProductionJob.TAG))
                            throw new IllegalStateException("Restored machine CPU lost its certified program");
                        resumed = true;
                        phaseTicks = 0;
                        LOG.info("[SELFTEST] restart2: machine-linked CPU restored in new process: cpu={} interfaceJobs={}",
                                executingCpu.getClass().getName(),interfaceLogic.getRequestedJobs().size());
                        LOG.info("[SELFTEST] restart2: original machine link={} certifiedProgram=true",expectedLink);
                    } else {
                        timeout("the job was not restored from the saved world");
                    }
                    return;
                }
                idleTicks = executingCpu.isBusy() ? 0 : idleTicks + 1;
                if (idleTicks < 10) {
                    if (phaseTicks > 2400) {
                        fail("the restarted job did not finish: busy=" + executingCpu.isBusy() + " stock=" + stock());
                        finish();
                    }
                    return;
                }
                long held = interfaceLogic.getStorage().extract(AEItemKey.of(Items.DIAMOND),
                        Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
                long inNetwork = inventory().extract(AEItemKey.of(Items.DIAMOND),
                        Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
                LOG.info("[SELFTEST] restart2: diamonds held by the restarted job's requester={} in network={} stock={}",
                        held, inNetwork, stock());
                long delivered = held + inNetwork;
                if (held != 3 || inNetwork != 0) {
                    fail("the restarted machine must receive exactly 3 diamonds with none rerouted to ME: held="+held+" network="+inNetwork);
                }
                LOG.info("[SELFTEST] PASS task={} delivered={}",task,delivered);
                finish();
            } catch (Throwable t) {
                fail("unexpected " + t);
                LOG.error("[SELFTEST] exception", t);
                finish();
            }
        }

        private void verifyDelivery() {
            if(legacyFeedback) {
                if(task.endsWith("reload") && !reloaded)fail("Legacy feedback never saved/reloaded a held packet");
                if(requester.delivered!=2 || com.aecraftroute.addon.api.DispatchTrace.certifiedAttaches()!=0)
                    fail("Legacy native feedback did not deliver two emeralds without certified execution");
                if(task.endsWith("rejectonce") && requester.zeroInserts!=1)
                    fail("Legacy requester did not reject exactly once");
                if(task.contains("-seed-")) {
                    long restored=inventory().extract(AEItemKey.of(Items.EMERALD),Long.MAX_VALUE,Actionable.SIMULATE,IActionSource.empty());
                    if(restored!=1)fail("Legacy feedback startup target seed was not restored exactly once: "+restored);
                    LOG.info("[SELFTEST] legacy feedback target seed in ME={} requesterRejected={} delivered={}",
                            restored,requester.zeroInserts,requester.delivered);
                }
                LOG.info("[SELFTEST] legacy feedback delivered={} cpu={}",requester.delivered,executingCpu.getClass().getName());
                finish();return;
            }
            if(foreign) {
                long expected="foreign-advanced-plus".equals(task) || isScaledProcessingExecution(task) || "scaled-molecular-execution".equals(task)?6:1;
                if(requester.delivered!=expected)fail("foreign requester received "+requester.delivered+" instead of "+expected+" of "+plan.finalOutput().what());
                if("scaled-molecular-execution".equals(task)) {
                    for(var item:List.of(Items.IRON_INGOT,Items.IRON_BLOCK))
                        if(inventory().extract(AEItemKey.of(item),Long.MAX_VALUE,Actionable.SIMULATE,IActionSource.empty())!=0)
                            fail("Super matrix left unexpected material in ME: "+item);
                    LOG.info("[SELFTEST] real super matrix conserved 54 iron ingots into delivered iron blocks={}",requester.delivered);
                }
                if(isScaledProcessingExecution(task)) {
                    long iron=inventory().extract(AEItemKey.of(Items.IRON_INGOT),Long.MAX_VALUE,Actionable.SIMULATE,IActionSource.empty());
                    long gold=inventory().extract(AEItemKey.of(Items.GOLD_BLOCK),Long.MAX_VALUE,Actionable.SIMULATE,IActionSource.empty());
                    var furnace=(net.minecraft.world.level.block.entity.FurnaceBlockEntity)level.getBlockEntity(PROVIDER.below());
                    var hopper=(net.minecraft.world.level.block.entity.HopperBlockEntity)level.getBlockEntity(PROVIDER.below(2));
                    if(iron!=3 || gold!=0 || !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty() || !hopper.isEmpty())
                        fail("Scaled physical processing conservation failed: iron="+iron+" gold="+gold+" furnaceInput="+furnace.getItem(0)+" furnaceOutput="+furnace.getItem(2)+" hopperEmpty="+hopper.isEmpty());
                    LOG.info("[SELFTEST] real furnace/hopper/interface processing complete: ironLeft={} goldInME={} delivered={}",iron,gold,requester.delivered);
                }
                if(com.aecraftroute.addon.api.DispatchTrace.certifiedAttaches()!=0)
                    fail("foreign native job attached a certified execution program");
                LOG.info("[SELFTEST] PASS task={} nativePlan={} cpu={} delivered={}",task,plan.getClass().getName(),
                        executingCpu.getClass().getName(),requester.delivered);
                finish();return;
            }
            if(vanilla) {
                if(requester.delivered!=1)fail("vanilla fixture delivered "+requester.delivered+" instead of one iron block");
                if(plan==null || plan.patternTimes().size()!=1 || plan.patternTimes().values().iterator().next()!=1)
                    fail("vanilla fixture did not execute exactly one physical iron-block pattern");
                String host=fallbackSmall || fallbackBusy || "vanilla-canvas-advanced".equals(task)
                        || "scaled-advanced".equals(task)?"advanced_ae":"ae2";
                if(com.aecraftroute.addon.api.DispatchTrace.hosts().getOrDefault(host,0)<1)
                    fail("vanilla fixture did not attach to expected "+host+" CPU");
                if(fallbackSmall && com.aecraftroute.addon.api.DispatchTrace.hosts().getOrDefault("ae2",0)>0)
                    fail("undersized AE2 CPU unexpectedly attached the certified program");
                LOG.info("[SELFTEST] PASS task={} cpu={} delivered={}",task,host,requester.delivered);
                finish();return;
            }
            if(paths) {
                verifyPathDelivery();
                return;
            }
            LOG.info("[SELFTEST] delivered {} x{}; stock={}", AEItemKey.of(Items.DIAMOND),
                    requester.delivered, stock());
            if (requester.delivered != requested) {
                fail("delivered " + requester.delivered + " of " + requested);
            }
            if (plan != null) {
                long seedStock = plan.emittedItems().get(AEItemKey.of(Items.EMERALD));                if (seedStock > 1) {
                    fail("plan consumed more starting stock than was available: " + seedStock);
                }
                boolean looped = plan.patternTimes().keySet().stream()
                        .anyMatch(p -> p.getOutputs().stream()
                                .anyMatch(o -> o.what().equals(AEItemKey.of(Items.LAPIS_LAZULI))));
                if (requested > 1 && !looped) {
                    fail("plan produced the target without re-feeding the loop");
                }
                LOG.info("[SELFTEST] loop patterns present in plan: {}", looped);
            }
            if (partial) {
                if (requester.partialInserts < 1) {
                    fail("the partial-accept requester never actually accepted a partial delivery");
                }
                LOG.info("[SELFTEST] partial delivery handled: calls={} partialCalls={} delivered={}",
                        requester.inserts, requester.partialInserts, requester.delivered);
            }
            if (task.equals("rejectonce") && requester.zeroInserts != 1)
                fail("requester did not reject exactly one delivery attempt before retry");
            if (task.equals("reload") && !reloaded) {
                fail("reload scenario never exercised CPU save/load");
            }
            var hosts = com.aecraftroute.addon.api.DispatchTrace.hosts();
            LOG.info("[SELFTEST] certified programs attached: count={} hosts={}",
                    com.aecraftroute.addon.api.DispatchTrace.certifiedAttaches(), hosts);
            if (advanced) {
                if (hosts.getOrDefault("advanced_ae", 0) < 1) {
                    fail("the AdvancedAE quantum computer never attached the certified program");
                }
                if (hosts.getOrDefault("ae2", 0) > 0) {
                    fail("the plain AE2 crafting CPU also attached a certified program");
                }
                LOG.info("[SELFTEST] advanced CPU used: {}", selectedCpu.getClass().getName());
            } else if (hosts.getOrDefault("ae2", 0) < 1) {
                fail("the AE2 crafting CPU never attached the certified program");
            }
            LOG.info("[SELFTEST] PASS task={} delivered={}", task, requester.delivered);
            finish();
        }

        /** An unregistered JVM proxy models a foreign mod's own requester class. */
        private static ICraftingRequester foreignProxy(Requester delegate) {
            return (ICraftingRequester)java.lang.reflect.Proxy.newProxyInstance(
                    SelftestHarness.class.getClassLoader(),
                    new Class<?>[]{ICraftingRequester.class,ICraftingSimulationRequester.class},
                    (proxy,method,args)->{
                        try{return method.invoke(delegate,args);}
                        catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });
        }

        private void verifyCancel() {
            LOG.info("[SELFTEST] after cancel: delivered={} cpuBusy={} stock={}",
                    requester.delivered, cpu.isBusy(), stock());
            if (requester.delivered != 0) {
                fail("cancelled job still delivered " + requester.delivered);
            }
            if (cpu.isBusy()) {
                fail("CPU still busy long after cancellation");
            }
            LOG.info("[SELFTEST] PASS task=cancel delivered=0 cpuIdle={}", !cpu.isBusy());
            finish();
        }

        // ----------------------------------------------------------- utils

        private appeng.api.storage.MEStorage inventory() {
            return grid.getStorageService().getInventory();
        }

        private String stock() {
            KeyCounter counter = new KeyCounter();
            inventory().getAvailableStacks(counter);
            StringBuilder sb = new StringBuilder("[");
            for (var key : List.of(AEItemKey.of(Items.EMERALD), AEItemKey.of(Items.DIAMOND),
                    AEItemKey.of(Items.LAPIS_LAZULI))) {
                if (!sb.toString().equals("[")) {
                    sb.append(", ");
                }
                sb.append(key.getDisplayName().getString()).append('=')
                        .append(counter.get(key));
            }
            return sb.append(']').toString();
        }

        private static String describe(KeyCounter items) {
            StringBuilder sb = new StringBuilder("{");
            for (var entry : items) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(entry.getKey().getDisplayName().getString()).append('=')
                        .append(entry.getLongValue());
            }
            return sb.append('}').toString();
        }

        private static String outputs(IPatternDetails pattern) {
            StringBuilder sb = new StringBuilder();
            for (var out : pattern.getOutputs()) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append(out.what().getDisplayName().getString()).append('x').append(out.amount());
            }
            return sb.toString();
        }

        private void timeout(String message) {
            if (phaseTicks > 600) {
                fail("phase " + phase + " timed out: " + message);
                finish();
            }
        }

        private void dumpCalculatorThreads() {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                String name = entry.getKey().getName();
                if (!name.startsWith("aecraftroute-calc") && !name.contains("aecraftroute")) {
                    continue;
                }
                LOG.error("[SELFTEST] thread {} state={}", name, entry.getKey().getState());
                for (var frame : entry.getValue()) {
                    LOG.error("[SELFTEST]     at {}", frame);
                }
            }
        }

        private void fail(String message) {
            failures.add(message);
            LOG.error("[SELFTEST] FAIL {}", message);
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if(stockRace && pendingCalculation!=null && !pendingCalculation.isDone())pendingCalculation.cancel(true);
            if (failures.isEmpty()) {
                LOG.info("[SELFTEST] RESULT=PASS task={} ticks={}", task, ticks);
            } else {
                LOG.error("[SELFTEST] RESULT=FAIL task={} ticks={} failures={}", task, ticks, failures);
            }
            try {
                Files.writeString(gameDirectory().resolve("selftest-result.txt"),
                        (failures.isEmpty()?"PASS":"FAIL")+" task="+task+"\n",StandardCharsets.UTF_8);
            } catch(java.io.IOException failure) {
                LOG.error("[SELFTEST] cannot write result receipt; Gradle must reject this run",failure);
            }
            if (Boolean.getBoolean("aecraftroute.selftest.exit")) {
                LOG.info("[SELFTEST] stopping the verification server");
                server.halt(false);
            }
        }

        private static String rootCause(Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    /** Records deliveries; the CPU delivers only after the whole program has run. */
    static final class Requester implements ICraftingRequester, ICraftingSimulationRequester {
        private final IGridNode node;
        private final IActionSource source;
        private final Set<ICraftingLink> links = new LinkedHashSet<>();
        /** When set, accept at most this many items per call and report back the truth. */
        private final long acceptAtMost;
        java.util.function.Consumer<AEKey> beforeDelivery;
        long delivered;
        int inserts;
        int partialInserts;
        int zeroInserts;
        final boolean rejectFirst;

        Requester(IGridNode node) {
            this(node, Long.MAX_VALUE, false);
        }

        Requester(IGridNode node, long acceptAtMost) {
            this(node, acceptAtMost, false);
        }

        Requester(IGridNode node, long acceptAtMost, boolean rejectFirst) {
            this.node = node;
            this.acceptAtMost = acceptAtMost;
            this.rejectFirst = rejectFirst;
            this.source = IActionSource.ofMachine(this);
        }

        @Override
        public IGridNode getActionableNode() {
            return node;
        }

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            synchronized (links) {
                return ImmutableSet.copyOf(links);
            }
        }

        @Override
        public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
            if (mode == Actionable.MODULATE && rejectFirst && zeroInserts == 0) {
                zeroInserts++;
                LOG.info("[SELFTEST] requester rejected first {} x{} delivery attempt", what, amount);
                return 0;
            }
            long accepted = Math.min(amount, acceptAtMost);
            if (mode == Actionable.MODULATE) {
                if(beforeDelivery!=null) beforeDelivery.accept(what);
                delivered += accepted;
                inserts++;
                if (accepted < amount) {
                    partialInserts++;
                    LOG.info("[SELFTEST] requester accepted {} of {} x{} (partial by design)", accepted, amount, what);
                    return accepted;
                }
                LOG.info("[SELFTEST] delivered {} x{}", what, accepted);
            }
            return accepted;
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
            if (link != null) {
                synchronized (links) {
                    links.add(link);
                }
            }
        }

        @Override
        public IActionSource getActionSource() {
            return source;
        }
    }
}
