# Upstream-fed growth regression

This is a **synthetic six-recipe fixture**, not an exported or inferred live AE2 recipe graph.
The screenshot-supported dust/seed/pure growth shape is exercised with explicit, finite external supply:

| Recipe | Inputs | Outputs |
| --- | --- | --- |
| germinate | dust 8, charged 8, sand 32 | seed 32 |
| purify | seed 1 | pure 1 |
| grind | pure 1 | dust 1 |
| sand-from-emc | emc 128 | sand 128 |
| emc-from-feed | feed 1 | emc 128 |
| charge-quartz | quartz 8 | charged 8 |

Initial inventory is dust 40 or 84, charged 4, emc 16, feed 1,000,000 and quartz 1,000,000. All other inventories are zero. The emc/feed and charged/quartz recipes are synthetic upstream choices, not claims about installed recipes. External stocks are sufficient for all tested quantities, and target amounts are **new net production**, not inventory delivery.

## Original planner measurement

Measured before changing `ProductionPlanner.java`, using the Java 21.0.12.1 runtime bundled beside the project, `-Xmx512m`, 30,000 state limit and 3,000 ms time limit. One sample per scenario in the listed order, one JVM; first sample includes cold/JIT effects. These are bounded diagnostic measurements, not statistically stable speed claims. The elapsed measurement includes planner construction. No Minecraft instance was started.

Baseline revision: `e292a2f58c84c83e3fb76b026a7425dfee3fde62`; planner source SHA-256: `3C2BA126EBFC80D418F2D0CEC693779BBD11DCEF5D3B50CC8C6489CDE6AEE33D`.

| Initial dust | Increment | Status | Explored | Elapsed ms |
| ---: | ---: | --- | ---: | ---: |
| 40 | 100 | FEASIBLE | 854 | 496.141 |
| 40 | 1000 | LIMIT | 2566 | 3002.603 |
| 40 | 1360 | LIMIT | 2595 | 3004.144 |
| 40 | 2000 | LIMIT | 2467 | 3000.302 |
| 84 | 100 | FEASIBLE | 1100 | 285.712 |
| 84 | 1000 | LIMIT | 3156 | 3000.433 |
| 84 | 1360 | LIMIT | 2949 | 3000.468 |
| 84 | 2000 | LIMIT | 2991 | 3000.469 |

With the same graph but a singleton quartz 1 -> charged 1 recipe, even increment 100 exhausted a 1,500 ms budget. This demonstrates sensitivity to upstream packet sizes, without asserting those packet sizes occur in the live network.

## Reproduction and assertions

`ProductionPlannerUpstreamFixture` is deliberately JUnit-independent. Compile it together with the production planner into an isolated diagnostics directory and run its `main`; its first optional argument is the per-scenario millisecond budget (default 500), and the second is the number of repetitions (default 1). The CSV includes a sample column; each repetition preserves the scenario order above. Repeated small-sample diagnostics still do not constitute stable speedup estimates. The original source and raw CSV were captured under ignored `build/diagnostics/upstream-baseline/` while diagnosing; the table above is the durable baseline.

```powershell
$jdk = '../runtime/jdk-21.0.12.1+1/bin'
$out = 'build/diagnostics/upstream-current'
New-Item -ItemType Directory -Force $out | Out-Null
& "$jdk/javac.exe" -d $out `
  'src/main/java/com/aecraftroute/addon/planner/ProductionPlanner.java' `
  'src/test/java/com/aecraftroute/addon/planner/ProductionPlannerUpstreamFixture.java'
& "$jdk/java.exe" -Xmx512m -cp $out com.aecraftroute.addon.planner.ProductionPlannerUpstreamFixture 3000 3
```

`ProductionPlannerUpstreamTest` requires these eight feasible cases to solve with the default limits, without changing those limits or adding timing assertions. It independently replays every emitted recipe firing from the actually claimed starting capital, checks each prefix for shortages and signed-long bounds, compares recipe counts, and verifies the requested net production. Additional cases prevent fabricated starting capital, zero/negative-gain acceleration and overflow certificates. Resource-limited negative cases may return LIMIT, never a fabricated FEASIBLE certificate.

Two further execution-semantics regressions verify that a preferred alternative consuming shared raw material cannot poison fallback inventory, and that a returned catalyst is extracted only once for a 1,000-firing batch. Both use the same independent scalar replay and were verified against the baseline planner; the optimized implementation must retain this behavior.

```powershell
pwsh -File ../build.ps1 -Task test -Extra '--tests com.aecraftroute.addon.planner.ProductionPlannerUpstreamTest'
```

## Pre-VM constructive probe measurement (historical intermediate build)

The table below belongs to the earlier BigInteger-only probe, before the final
64-batch cap and numeric VM integration. It is retained as historical evidence,
not a measurement of the current source or release artifact. The current probe
caps candidate words at 64 steps and falls back to compressed backward macros
for longer witnesses; the earlier 1,024-step allowance described below is not
the shipped limit.

Measured with the same synthetic topology and unchanged 30,000-state / 3,000 ms limits, using the same Java 21 runtime. Two repetitions in one JVM; the first 40/100 sample includes cold/JIT effects. All 16 samples were FEASIBLE. These small standalone measurements exclude network capture, pattern enumeration, server ticks and commit validation; they are not live-game latency or a statistical speedup guarantee.

Optimized planner source SHA-256: `8AEDFBABFA9DA5927C6D6FF821F548B94B44A535C87E8B6675BC173142D601DA`.

| Initial dust | Increment | Status | Samples | Min ms | Max ms | Explored |
| ---: | ---: | --- | ---: | ---: | ---: | ---: |
| 40 | 100 | FEASIBLE | 2 | 0.794 | 30.170 | 12 |
| 40 | 1000 | FEASIBLE | 2 | 1.352 | 2.661 | 24 |
| 40 | 1360 | FEASIBLE | 2 | 1.556 | 2.713 | 31 |
| 40 | 2000 | FEASIBLE | 2 | 1.513 | 2.141 | 34 |
| 84 | 100 | FEASIBLE | 2 | 0.653 | 0.849 | 12 |
| 84 | 1000 | FEASIBLE | 2 | 1.004 | 1.243 | 21 |
| 84 | 1360 | FEASIBLE | 2 | 1.434 | 1.622 | 28 |
| 84 | 2000 | FEASIBLE | 2 | 1.358 | 1.660 | 34 |

The fast path constructs a bounded executable witness from current deficits, then uses the existing independent certificate checks. A failed probe does not mutate initial inventory and falls back to the original search. It uses at most 50 ms or 1/16 of the existing time budget, and at most 1,024 or 1/8 of the state budget. It does not raise those limits.

A 256-stage chain can still exhaust the cold probe allowance and fall back (about 605–619 ms in two cold diagnostics; a later probe-success run was about 134 ms including certification). No universal latency bound is claimed. Existing-stock regression also verifies that one new target on top of 1,000,000 existing targets consumes only one raw input, not a million.

