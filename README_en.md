# AE-Craftroute — Crafting Planner for Applied Energistics 2

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?logo=minecraft)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.169+-orange)
![AE2](https://img.shields.io/badge/AE2-19.2.18+-green)
![Java](https://img.shields.io/badge/Java-21-red)
![License](https://img.shields.io/badge/License-LGPL%20v3-blue)

> **中文版**: [README.md](README.md)

AE-Craftroute is a NeoForge addon for [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2). It replaces AE2's crafting feasibility calculation: the verdict on a crafting request comes from a single search, which yields both the intermediate products required and the order in which they execute, handed to the crafting CPU as an executable program. The artifact is a single jar under `build/libs/`.

All computation is performed by this addon; AE2's pattern definitions and inventory structures are not modified. Interaction with AE2 uses public interfaces and Mixin injection; the injection targets are listed in [ATTRIBUTIONS.md](ATTRIBUTIONS.md).

---

## 1. Three recipe structures recursion does not fit

AE2 decides whether a crafting request can complete by recursively expanding the pattern tree: starting from the target, each material is replaced by the pattern that produces it, until every leaf is covered by stock. That criterion matches execution for most recipes and does not match it for the following three structures.

**Cyclic recipes.** A cyclic recipe consists of patterns that are each other's inputs, for example `A → B` and `B → 2A`. Each consumes the other's output, so the expansion has no naturally terminating leaf: it either continues until the stack overflows or a depth limit is reached, or it reports the recipe as uncraftable at the first cycle. Such a recipe can run continuously and yield a net increment given any participating item as starting capital; its executability depends on that capital and on the net increment of the cycle, not on whether the expansion terminates.

**Net increment.** When a pattern's output is also an input of that pattern or of another, the actual requirement is the net increment. Recursive expansion counts each level as a fresh consumption, so the same material enters the demand total more than once and the reported demand exceeds what execution requires.

**Order dependency.** Feedback items move between assemblers in a fixed order: the output of one step must enter a buffer before it can serve as the input of the next. The product of recursive expansion is a bill of materials carrying no ordering information. A valid bill and an executable plan are two different conditions; the former does not imply the latter.

These three structures produce two kinds of report in game: an executable recipe reported as missing materials, or a plan reported as feasible while CPU progress stays at zero. Both share one mechanism: the criterion being evaluated differs from the criterion execution requires. Recursion evaluates whether the expansion terminates; execution requires whether this batch can be produced in order.

---

## 2. The replacement calculation

The verdict is split into four stages. The output of each stage is the sole input of the next, and no mutable state is shared between stages.

**1. Snapshot.** `RouteSnapshot` reads the network's current pattern definitions and stock and forms an immutable snapshot. Canvas preview and real submission build their snapshot through the same constructor, so both paths use the same input.

**2. Search.** `NetworkProductionPlanner` works backwards from the target and keeps a separate account per material: total demand, the part covered by stock, and the pattern that covers the remainder. Cycles and net increments need no separate branch under this accounting: feedback consumption and regeneration are recorded on the same account, and the net increment follows directly from the bookkeeping.

**3. Replay validation.** A candidate plan found by the search is not returned immediately. The candidate program is replayed forward in order, checking step by step whether the inputs of each step are satisfied at the moment that step executes. A plan that passes the whole replay is returned as a `CertifiedCraftingPlan`. That object carries the starting capital `usedItems` and the execution order `program`; its `missingItems()` is always empty, because passing the replay means the inputs of every step are satisfied at the moment it executes.

**4. Execution.** The CPU bridge dispatches in program order, keeps feedback in the CPU inventory for the next round, and delivers the final output once `waitingFor` is empty and the program is complete. Program and cursor persist with the CPU's NBT; after a server stop and restart, an in-flight job resumes from the save and continues.

### Conditions behind the four outcomes

| Outcome | Condition |
|---------|-----------|
| `feasible` | The search found a plan and the forward replay passed in full; returned with a starting-capital list and an execution program |
| `UNREACHABLE` | Proven infeasible: the required material is short across both stock and producible supply, or no pattern produces it |
| `LIMIT` | The verdict was not reached within the given budget; the plan is neither proven feasible nor proven infeasible |
| `UNSUPPORTED` | Pattern semantics cannot be modelled exactly; the plan took no part in the feasibility verdict |

A missing-material message is produced only by `UNREACHABLE`. `LIMIT` and `UNSUPPORTED` return their own outcome values, and their interface text is not phrased as a material shortage.

---

## 3. Crafting canvas

Beyond the calculation, the addon provides a crafting-tree canvas that renders the result as an editable node graph.

- **Source of verdicts.** Feasibility, starting capital and batch counts shown on the canvas all come from the server-side planner; the client performs no independent estimate. Changing the target issues a new request, so the canvas verdict and the verdict used at submission come from the same source.
- **Per-material pattern locking.** A single material can be pinned to a specific pattern. Locking applies per material, and canvas choices enter the server-side calculation verbatim, without client-side conversion.
- **Whole-canvas atomic apply.** One submission carries every node's choice; the server validates each and applies them at once. No partially applied intermediate state exists.
- **Conflict diagnostics.** When one material is assigned two different patterns, the server names the conflicting material and requires it to be settled first, rather than silently choosing one. A selected pattern that has left the network is likewise reported per material.

---

## 4. Asynchronous planning and budgets

A plan involves pattern-graph traversal, input-variant enumeration and replay validation, with cost growing in network size. Computing it all inside the request thread puts the cost peak directly on the server tick. Planning is therefore implemented as a state machine driven by server ticks.

- **Reads separated from computation.** Grid and stock reads happen only on the server thread; worker threads receive immutable snapshots and hold no grid reference.
- **One budget across the chain.** Queue wait, snapshot capture, variant enumeration and replay validation share a single deadline. Exhausting it at any stage returns `LIMIT`; an unfinished search is not reported as a material shortage.
- **Revalidation before commit.** After the computation, the current network state is validated once before committing. If patterns or stock changed meanwhile, the result is reported as an expired plan and a retry is required; no plan based on stale state is committed.
- **Cancellation propagation.** A cancel cascades to in-flight worker tasks and queued jobs. Closing the menu, switching networks, logging out and stopping the server all invalidate the job.

---

## 5. Modelling pattern semantics

A pattern is not always "some inputs for some outputs". The following three semantics follow one rule: included in the calculation when they can be modelled exactly, otherwise reported as `UNSUPPORTED`.

**Finite-durability tools.** A tool used in crafting loses one durability step per use. The resulting item identity, whether the final break returns an item, and how remaining durability persists with the job are all part of the execution protocol. The current implementation validates transitions against the actual consumed damage state (`FiniteToolTransitions`) and returns `UNSUPPORTED` for cases that protocol cannot describe fully. Flattening a tool count into an amortised, infinitely reusable number yields a plan that passes formally and fails at execution, so that approximation is not used.

**Family-level aggregation of alternative inputs.** When one input slot of a pattern accepts a family of equivalent materials and the slot's demand must be distributed among them, the distribution is solved as capacitated bipartite matching (`FuzzyFamilyAllocator`). Slots with identical candidate sets merge into one demand node, and after solving, the flow is distributed back into the original physical slots. The synthesised family identity is not extracted, not persisted, and not sent to a pattern provider.

**Wrapped patterns.** Outer wrapper patterns (scaled patterns, for instance) are supported only at the level of their public interface (`ScaledPatternCompat`). One outer dispatch corresponds to one task, keeping the outer input and output quantities and the provider identity; the inner pattern does not enter the executable plan.

---

## 6. Installation and use

### Installation

1. Install [NeoForge](https://neoforged.net/) and [Applied Energistics 2 19.2.18+](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2).
2. Place `aecraftroute-<version>_neoforge_1.21.1.jar` in `mods/`. Keep only that one copy; duplicates raise a modId error.
3. Start the game. A build banner in the log indicates it loaded.

The mod id is `aecraftroute` and the package is `com.aecraftroute.addon`; both were renamed from `ae2vm` in `1.13.19-local.34`. Jobs in flight in existing saves are not affected: when a new NBT key is absent, the reader falls back to the old key, and the next save writes the new key and clears the old one. Third-party integrations update their imports to the new package and class names; the public method names are unchanged.

### Dependencies

| Dependency | Kind | Version requirement |
|------------|------|---------------------|
| Minecraft | required | 1.21.1 |
| NeoForge | required | ≥ 21.1.169 (21.1.259 used in development) |
| Applied Energistics 2 | required | ≥ 19 (19.2.18 used in development) |
| AdvancedAE | optional | 1.6.12; provides the quantum-computer execution bridge; without it that mixin group is not applied |
| AE2 Crafting Tree | optional (client) | Canvas integration target (`@Pseudo` mixin); without it that injection is skipped and the canvas uses the built-in UI |
| JEI | optional (client) | ≥ 19.57.0; provides the recipe-page import button |
| Configured | optional | 2.x; provides the in-game config editor, which the config does not depend on |

Optional dependencies are soft: when the corresponding mod is absent, the related entry point does not load, and the main planning and execution paths are unaffected.

### Use

No extra configuration is required.

1. Encode AE2 patterns as usual and place them in pattern providers.
2. Request a craft from an ME terminal.
3. Planning and execution complete automatically.

### Configuration

The config file is `config/aecraftroute-common.toml`. Setting `proxyEnabled=false` disables the takeover and returns calculation to AE2's native recursion; if canvas constraints are set at that point, an explicit error is returned rather than executing around them.

When upgrading from a pre-rename build, if the config directory holds only `ae2vm-common.toml`, the first start copies it to the new config file name and renames the old file to `ae2vm-common.toml.migrated`.

Requests from third-party mods pass through to native logic by default; a mod that wants takeover registers explicitly, as in the next section.

---

## 7. Third-party integration

```java
import com.aecraftroute.addon.api.CraftRouteCrafting;
import com.aecraftroute.addon.api.CraftRouteCraftingRegistry;

// Register in your @Mod constructor: requests from this mod may be taken over
// (marker is a substring of the class name).
CraftRouteCraftingRegistry.register("mymod");

// Optional integration: use this addon's calculation when present, else fall back
if (CraftRouteCrafting.isLoaded()) {
    CompletableFuture<ICraftingPlan> plan = CraftRouteCrafting.calculate(
        grid, requester, what, amount, strategy);
    plan.thenAccept(p -> submitToCpu(p, requester));
} else {
    nativeCalculate(requester, what, amount, strategy);
}
```

- Registration (`register`) decides whether requests this mod issues are taken over; calculation (`calculate`) actively calls this addon's planner. The two are independent.
- AE2's own (`appeng.*`) requests are always taken over; unregistered mods keep their own logic.
- The dependency is `compileOnly`. Without this addon installed, `isLoaded()` returns `false` and caller behaviour is unchanged.
- `calculate()` runs asynchronously; `calculateSync()` blocks the calling thread and refuses to run on the server main thread.

---

## 8. Project structure

```
src/main/java/com/aecraftroute/addon/
├── CraftRouteAddon.java                # Mod entry (build banner, config migration)
├── BuildInfo.java                      # Build stamp and /aecraftroute build command
├── api/                                # Public API and third-party registration
├── compat/                             # Wrapper-pattern compat, external mod adapters (advancedae, thunderbolt stubs)
├── compiler/                           # Finite-durability input interface
├── config/                             # Configuration definitions
├── planner/
│   ├── ProductionPlanner.java          # Backward accounting search, forward replay
│   ├── NetworkProductionPlanner.java   # Network pattern graph → plan
│   ├── CertifiedCraftingPlan.java      # Plan and execution program
│   ├── ProductionPlanCache.java        # Bounded plan cache compared by value
│   ├── PatternVariant(s).java          # Exact combinations of pattern input slots
│   ├── FiniteToolTransitions.java      # Transition validation for finite tools
│   ├── FuzzyFamilyAllocator.java       # Capacitated matching of material families
│   └── ProductionJob.java              # In-CPU execution state (persistable)
├── route/                              # Canvas, planning service and network rules
│   ├── RouteSnapshot.java              # Single snapshot source (patterns and stock)
│   ├── RoutePlanningService.java       # Server-tick driven asynchronous planning
│   ├── RouteCanvasPlanner.java         # Canvas finite paths → certified plan
│   ├── ForcedRoutePlanner.java         # Finite-path execution contract
│   ├── RouteApply.java                 # Whole-canvas atomic apply
│   └── client/                         # Canvas UI
├── telemetry/                          # Outcome recording and log throttling
├── mixin/                              # Crafting service and CPU execution bridges
├── vm/                                 # Feedback-ring detection and net production
└── selftest/                           # Independent-instance self test (not shipped)

src/main/resources/
├── META-INF/neoforge.mods.toml
├── aecraftroute.mixins.json
├── aecraftroute.png
└── assets/ae2/screens/aecraftroute_route.json
```

---

## 9. Building and testing

```bash
# Single build; output in build/libs/
gradlew -p . jar --offline
```

The output is a single `aecraftroute-<version>_neoforge_1.21.1.jar`. The build does not write into a game instance; placing the output in `mods/` is a separate explicit step. Remove any existing `aecraftroute*.jar` and any `ae2vm*.jar` left over from before the rename, otherwise startup fails with a duplicate modId.

Tests run as three separate tasks, keeping correctness regressions apart from performance measurement:

```bash
gradlew test                      # Unit and integration regressions, benchmark tag excluded
gradlew routeFunctionalTest       # Route functional regressions
gradlew productionBenchmark       # Planner micro-benchmarks
```

The current offline regression is 38 suites and 271 tests, with 0 failures, 0 errors and 0 skipped. Real-grid verification runs in an independent instance: a genuine ME network with controller, drive, crafting storage, pattern providers, molecular assemblers and a crafting CPU, using real encoded patterns and real requests. The self-test runs its scenarios one at a time: a net-growth feedback ring (`growth`), cancellation (`cancel`), CPU reload from its own saved NBT (`reload`), an AdvancedAE quantum computer (`advanced`), partial acceptance of output by the requester (`partial`), a finite canvas path (`paths`), and resume across a server stop, split into two runs (`restart1`, `restart2`).

---

## 10. License and acknowledgement

Licensed under LGPL-3.0; the full text is in [LICENSE](LICENSE). Version history is in [CHANGELOG.md](CHANGELOG.md). The use and licensing of third-party work are in [ATTRIBUTIONS.md](ATTRIBUTIONS.md); the history of the earlier implementation is in [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md).
