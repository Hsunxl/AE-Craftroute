# AE2 VM — AE2 Crafting Planner

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?logo=minecraft)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.259+-orange)
![AE2](https://img.shields.io/badge/AE2-19.2.18+-green)
![Java](https://img.shields.io/badge/Java-21-red)
![License](https://img.shields.io/badge/License-LGPL%20v3-blue)

> **中文版**: [README.md](README.md)

**AE2 VM** is a NeoForge addon for [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2). It replaces AE2's recursive crafting-tree traversal with **a single provable planning search**: working backwards from the request while accounting for real inventory, it decides *what* to craft and *in which order*, then hands the result — together with an **executable program** — to the crafting CPU.

It targets three kinds of recipes that a recursive algorithm structurally cannot handle:

- **Cyclic recipes** (`A→B`, `B→2A` — feedback loops): recursion either expands forever or simply fails.
- **Incremental / net production** (the output feeds its own recipe, so only the net gain is really required).
- **Real executable ordering** (feedback items must flow between assemblers in sequence; they are not one-shot consumables).

---

## What it actually changes

AE2 decides "can this be crafted?" by recursively expanding the pattern tree. For the three cases above that expansion either blows the stack or produces **a plan that cannot actually be executed** — which is why players see two contradictory symptoms: a craft that is obviously possible is reported as missing materials, or a plan looks feasible but the CPU sits at 0 progress.

AE2 VM replaces that step with **a provable search**:

1. **Snapshot** (`RouteSnapshot`): reads this network's patterns and stock once and uses it as the sole basis for the whole calculation. The canvas preview and the real submission go through the same snapshot builder, so what you see and what runs cannot disagree.
2. **Plan** (`NetworkProductionPlanner`): a backward-coverability search that explicitly tracks, per material, how much is needed, how much stock covers, and which pattern supplies the remainder. Cycles and net production fall out of the inventory accounting naturally.
3. **Proof** (`CertifiedCraftingPlan`): a result is returned only after a forward replay validates that every step's inputs really are available at the moment it executes. The plan carries `usedItems` (the starting capital), `program` (the execution order), and `missingItems()` is always empty — **the plan itself is the proof of executability**.
4. **Execution** (CPU mixin): with a certified program attached, the CPU dispatches in program order, keeps feedback items in the CPU inventory for reuse by the next round, and delivers the final output only after `waitingFor` is empty and the program is complete.

**Four outcomes, kept strictly distinct** (this is the core fix for fake "missing material" reports):

| Outcome | Meaning |
|---------|---------|
| `feasible` | Proven craftable; ships a starting-capital list and an execution program |
| `UNREACHABLE` | Proven **impossible** (genuinely missing / no producer) |
| `LIMIT` | **Undecided** within budget — not a shortage |
| `UNSUPPORTED` | Cannot be modeled precisely — not treated as a shortage |

Only `UNREACHABLE` ever says "missing". Budget exhaustion and unsupported pattern semantics are **never** disguised as shortages.

---

## Crafting canvas

Beyond replacing the algorithm, the mod ships a **crafting-tree canvas** that draws the route AE2 computed, letting you pick which pattern each node uses and how far to expand it.

- **Authoritative verdicts**: the feasibility, starting-capital list and batch counts shown all come from the same certified planner on the server — not a client-side estimate. Changing the target re-requests, so the verdict you see matches the one at submission time.
- **Per-material locking**: pick "this material uses that pattern" per node. Locking is applied per material, and the canvas choices enter the server-side calculation verbatim.
- **Whole-canvas atomic apply**: submit every node's choice as one unit; the server validates each and applies them all at once.
- **Per-node conflict diagnostics**: if one material is assigned two different patterns on the canvas, it does not silently pick one — it names the conflicting material and asks you to settle it first. A selected pattern that has left the network is likewise reported per material.

---

## Verification

Correctness is verified on a **real ME grid in an independent instance**, not by unit tests alone:

- **Offline**: 189 tests pass, covering the planner, canvas preview/apply, payload limits, and a fixed regression built on an ae2cs aggregator chain (8 dust + 8 charged quartz + 32 sand → 32 seeds).
- **Real grid**: a dedicated dev server with a genuine ME network (controller, drive, crafting storage, pattern providers, molecular assemblers, crafting CPU), real encoded patterns and real requests:
  - a three-node feedback loop (emerald→diamond→2 lapis→emerald), asking for 3 diamonds starting from a single emerald → delivers 3, stock drained to zero;
  - cancellation, CPU save roundtrip, and **resume after a real server restart** (a fresh process restores the in-flight job from the save and finishes delivery);
  - partial delivery when the requester accepts only part of the output;
  - the same program executed on an AdvancedAE quantum computer.

---

## Usage

### Installation

1. Install [NeoForge](https://neoforged.net/) and [Applied Energistics 2 19.2.18+](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2)
2. Drop `ae2vm-nodetect-<version>.jar` into `mods/` (**only one** — two copies cause a duplicate modId error)
3. Start the game; a `AE2 VM <version> (built ...)` banner in the log means it loaded

### In game

Works out of the box:

1. Encode AE2 patterns as usual and place them in pattern providers
2. Request a craft from an ME terminal
3. Planning and execution are automatic

**Config switch**: setting `proxyEnabled=false` in `config/ae2vm-common.toml` turns off the takeover and returns to AE2's native recursion (when canvas constraints are set, it reports an explicit error instead of quietly bypassing them).

**Third-party patterns**: requests from other mods (ECO and friends) pass through to native logic by default; a mod that wants takeover can register explicitly (see below).

---

## Third-party integration (development)

```java
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;

// Register in your @Mod constructor (marker = substring of the class name):
// "requests from my mod may be taken over"
AE2VMCraftingRegistry.register("mymod");

// Optional integration: use our planner when present, otherwise fall back
if (AE2VMCrafting.isLoaded()) {
    CompletableFuture<ICraftingPlan> plan = AE2VMCrafting.calculate(
        grid, requester, what, amount, strategy);
    plan.thenAccept(p -> submitToCpu(p, requester));
} else {
    nativeCalculate(requester, what, amount, strategy);
}
```

- **Registration** (`register`) decides whether requests from your mod are taken over; **calculation** (`calculate`) actively calls this mod's planner.
- AE2's own (`appeng.*`) requests are always taken over; unregistered third-party mods keep their own logic.
- The dependency is `compileOnly`; without this mod installed `isLoaded()` returns `false` and everything works as before.
- `calculate()` runs asynchronously; `calculateSync()` blocks the calling thread and is for non-server-main threads only.

---

## Project structure

```
src/main/java/com/ae2vm/addon/
├── AE2VMAddon.java                  # Mod entry (compat checks / build banner)
├── BuildInfo.java                   # Build stamp + /ae2vm build command
├── api/
│   ├── AE2VMCrafting.java           # Public API (calculate / planNow)
│   ├── AE2VMCraftingRegistry.java   # Third-party registration
│   └── DispatchTrace.java           # Which CPU logic attached a certified program
├── planner/
│   ├── ProductionPlanner.java       # Backward coverability + forward replay
│   ├── NetworkProductionPlanner.java# Pattern graph → plan
│   ├── CertifiedCraftingPlan.java   # Plan + executable program
│   ├── PatternVariant.java          # Pattern slot combinations
│   ├── ProductionJob.java           # In-CPU program execution state (persistable)
│   └── ProductionPlanningException.java # The four outcomes
├── route/
│   ├── RouteSnapshot.java           # Single snapshot source (patterns + stock)
│   ├── RoutePlanView.java           # Authoritative plan view for the canvas
│   ├── RouteIntent.java             # Per-material locking + conflict diagnostics
│   ├── RouteApply.java              # Whole-canvas atomic apply
│   ├── RouteNetwork.java            # Canvas network protocol
│   └── client/                      # Canvas UI
├── vm/
│   ├── TwoNodeGrowthPlanner.java    # Two-node feedback ring detection + ordering
│   ├── NetProduction.java           # Net-production check
│   └── FeedbackReservation.java     # Feedback reservation
├── mixin/
│   ├── CraftingServiceMixin.java    # Takes over beginCraftingCalculation
│   ├── ProductionCpuMixin.java      # AE2 crafting CPU execution bridge
│   ├── AdvancedProductionCpuMixin.java # AdvancedAE quantum CPU bridge
│   └── GrowthCpuMixin.java / AdvancedGrowthCpuMixin.java # Fallback for non-certified plans
└── selftest/                        # Independent-instance self test (not shipped)
```

---

## Mod info

| Item | Value |
|------|-------|
| Mod ID | `ae2vm` |
| Name | AE2 VM |
| Package | `com.ae2vm.addon` |

### Dependencies

| Dependency | Version |
|------------|---------|
| Minecraft | 1.21.1 |
| NeoForge | ≥ 21.1.169 (tested on 21.1.259) |
| Applied Energistics 2 | ≥ 19.2.17 (tested on 19.2.18) |
| AdvancedAE (optional) | 1.6.12 |
| Configured (optional, in-game config UI) | 2.x |

---

## Building

```bash
# Single build (output in build/libs/)
gradlew -p . jar --offline -PblockedMode=warn

# Build + bump version + copy into mods
buildBoth.bat
```

> `-PblockedMode=warn` produces the `nodetect` variant. In packs that ship `data_energistics` this variant is **mandatory** — otherwise the mod refuses to start on purpose.

---

## License

LGPL v3, see [LICENSE](LICENSE). Version history in [CHANGELOG.md](CHANGELOG.md).
