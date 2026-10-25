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
        if (!"restart2".equals(System.getProperty(TASK_PROP))) {
            deleteWorld(gameDir.resolve("world"));
        }

        Path pack = gameDir.resolve("world").resolve("datapacks").resolve(PACK);
        Files.createDirectories(pack.resolve("data/selftest/recipe"));
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":48,\"description\":\"aecraftroute selftest recipes\"}}",
                StandardCharsets.UTF_8);
        writeRecipe(pack, "powder_to_crystal", "minecraft:emerald", 1, "minecraft:diamond", 1);
        writeRecipe(pack, "crystal_to_seed", "minecraft:diamond", 1, "minecraft:lapis_lazuli", 2);
        writeRecipe(pack, "seed_to_powder", "minecraft:lapis_lazuli", 1, "minecraft:emerald", 1);
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
        private final boolean advanced;
        private final boolean restart1;
        private final boolean restart2;
        private final boolean partial;
        private final boolean paths;
        private CpuTaskObserver pathObserver;
        private IPatternDetails pathDiamondLapis,pathEmeraldLapis;
        private Requester requester;
        private appeng.helpers.InterfaceLogic interfaceLogic;
        private ICraftingLink link;
        private ICraftingPlan plan;
        private java.util.concurrent.Future<ICraftingPlan> pendingCalculation;
        private boolean stocked;
        private boolean patternsInstalled;
        private boolean submitted;
        private boolean cancelled;
        private boolean reloaded;
        private boolean restartSaved;
        private boolean resumed;
        private int idleTicks;
        private int cancelTick = -1;
        private long requested;
        private boolean finished;

        Scenario(MinecraftServer server) {
            this.server = server;
            this.task = System.getProperty(TASK_PROP, "growth");
            this.advanced = "advanced".equals(this.task);
            this.restart1 = "restart1".equals(this.task);
            this.restart2 = "restart2".equals(this.task);
            this.partial = "partial".equals(this.task);
            this.paths = "paths".equals(this.task);
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
                case "reload" -> 4;
                case "paths" -> 1;
                default -> 3;
            };
            var controller = (IGridConnectedBlockEntity) level.getBlockEntity(CONTROLLER);
            IGridNode node = controller.getMainNode().getNode();
            // A requester that only takes one item per call proves the CPU keeps the rest
            // and retries instead of losing it or finishing the job early.
            requester = new Requester(node, partial ? 1 : Long.MAX_VALUE);
            if (partial) {
                LOG.info("[SELFTEST] partial-accept requester: at most 1 item per delivery call");
            }
            var service = grid.getCraftingService();
            AEKey target = AEItemKey.of(Items.DIAMOND);
            if(paths) {
                submitPathCalculation();
                return;
            }

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
                pendingCalculation = service.beginCraftingCalculation(level, requester, target,
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

        /** Never blocks the server thread: AE2's own callers consume futures asynchronously. */
        private void calculating() {
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
            if (!(plan instanceof CertifiedCraftingPlan)) {
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

            ICraftingRequester submitter = restart1 && interfaceLogic != null ? interfaceLogic : requester;
            ICraftingSubmitResult result = grid.getCraftingService().submitJob(plan, submitter, selectedCpu,
                    false, requester.getActionSource());
            if (!result.successful()) {
                fail("job submission refused: " + result.errorCode());
                finish();
                return;
            }
            link = result.link();
            if (link != null) {
                requester.jobStateChange(link);
                if (submitter != requester) {
                    submitter.jobStateChange(link);
                }
            }
            submitted = true;
            LOG.info("[SELFTEST] job submitted: target=diamond x{}", requested);
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

        // ------------------------------------------------------------ RUN

        private void run() {
            if(paths && pathObserver!=null) pathObserver.sample();
            if (restart1) {
                if (cpu.isBusy()) {
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
                verifyDelivery();
                return;
            }
            if (phaseTicks > 2400) {
                fail("timed out waiting for delivery: delivered=" + requester.delivered
                        + "/" + requested + " cpuBusy=" + cpu.isBusy()
                        + " stock=" + stock());
                finish();
            }
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
            cpu.writeToNBT(tag, level.registryAccess());
            boolean hasJob = tag.contains("job");
            boolean hasProduction = tag.contains(ProductionJob.TAG);
            int interfaceJobs = interfaceLogic.getRequestedJobs().size();
            int attaches = com.aecraftroute.addon.api.DispatchTrace.certifiedAttaches();
            LOG.info("[SELFTEST] restart1: cpu busy={} NBT job={} productionProgram={} bytes={} interfaceJobs={} certified={}",
                    cpu.isBusy(), hasJob, hasProduction, tag.size(), interfaceJobs, attaches);
            if (attaches < 1) {
                fail("the interface's own calculation did not receive a certified program");
            }
            if (!cpu.isBusy()) {
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
            server.saveEverything(true, true, true);
            LOG.info("[SELFTEST] restart1: world saved with the job still running, stopping the server");
            finish();
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
                ICraftingCPU vanilla = null;
                for (ICraftingCPU candidate : grid.getCraftingService().getCpus()) {
                    if (candidate instanceof CraftingCPUCluster cluster) {
                        vanilla = cluster;
                    }
                }
                if (vanilla == null) {
                    timeout("the crafting CPU did not come back after the restart");
                    return;
                }
                cpu = (CraftingCPUCluster) vanilla;
                var iface = level.getBlockEntity(INTERFACE);
                if (!(iface instanceof appeng.helpers.InterfaceLogicHost host)) {
                    timeout("the ME interface did not come back after the restart");
                    return;
                }
                interfaceLogic = host.getInterfaceLogic();
                if (!resumed) {
                    if (phaseTicks % 100 == 0) {
                        LOG.info("[SELFTEST] restart2: waiting for the restored job, interfaceJobs={} cpuLink={} busy={} storage={}",
                                interfaceLogic.getRequestedJobs().size(),
                                cpu.craftingLogic.getLastLink() != null, cpu.isBusy(), cpu.getAvailableStorage());
                    }
                    if (cpu.isBusy()) {
                        resumed = true;
                        phaseTicks = 0;
                        LOG.info("[SELFTEST] restart2: the certified job is running in a new process, interfaceJobs={} cpuLink={}",
                                interfaceLogic.getRequestedJobs().size(), cpu.craftingLogic.getLastLink() != null);
                    } else {
                        timeout("the job was not restored from the saved world");
                    }
                    return;
                }
                idleTicks = cpu.isBusy() ? 0 : idleTicks + 1;
                if (idleTicks < 10) {
                    if (phaseTicks > 2400) {
                        fail("the restarted job did not finish: busy=" + cpu.isBusy() + " stock=" + stock());
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
                if (delivered < 3) {
                    fail("the restarted job delivered " + delivered + " of 3 diamonds");
                }
                LOG.info("[SELFTEST] PASS task=restart2 delivered={}", delivered);
                finish();
            } catch (Throwable t) {
                fail("unexpected " + t);
                LOG.error("[SELFTEST] exception", t);
                finish();
            }
        }

        private void verifyDelivery() {
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
            if (failures.isEmpty()) {
                LOG.info("[SELFTEST] RESULT=PASS task={} ticks={}", task, ticks);
            } else {
                LOG.error("[SELFTEST] RESULT=FAIL task={} ticks={} failures={}", task, ticks, failures);
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

        Requester(IGridNode node) {
            this(node, Long.MAX_VALUE);
        }

        Requester(IGridNode node, long acceptAtMost) {
            this.node = node;
            this.acceptAtMost = acceptAtMost;
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
