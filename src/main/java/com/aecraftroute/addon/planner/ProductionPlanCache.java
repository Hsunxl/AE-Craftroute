package com.aecraftroute.addon.planner;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;

/** Bounded memoization of exact, certified production nets, never live patterns/grids.
 * Recipe IDs in a cached witness are positions in the ordered transition list.
 * Callers must bind them to freshly parsed variants, not to an old pattern object.
 * Transitions are compared by value (not a hash alone); a proof may also be
 * reused with different stock when every unit of its certified prefix capital
 * is still available. Only
 * FEASIBLE results are retained: a timeout must not poison a later larger budget.
 */
final class ProductionPlanCache<K> {
    private record Flow<K>(Map<K, Long> inputs, Map<K, Long> outputs) {}
    private record Key<K>(K target, long amount, List<Flow<K>> flows, Map<K, Long> stock) {}
    private record Entry<K>(ProductionPlanner.Result<K, Integer> result, long created, long weight) {}
    record Stats(long hits, long misses, int entries, long weight) {}

    private final LinkedHashMap<Key<K>, Entry<K>> entries = new LinkedHashMap<>(16, .75f, true);
    private final int maxEntries;
    private final long maxWeight, ttlNanos;
    private final LongSupplier clock;
    private long weight, hits, misses;

    ProductionPlanCache() { this(32, 65_536, 60_000_000_000L, System::nanoTime); }

    ProductionPlanCache(int maxEntries, long maxWeight, long ttlNanos, LongSupplier clock) {
        if (maxEntries <= 0 || maxWeight <= 0 || ttlNanos <= 0) throw new IllegalArgumentException("Invalid cache bounds");
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.ttlNanos = ttlNanos;
        this.clock = Objects.requireNonNull(clock);
    }

    ProductionPlanner.Result<K, Integer> solve(K target, long amount,
            List<ProductionPlanner.Recipe<K, Integer>> recipes, Map<K, Long> stock,
            ProductionPlanner.Limits limits) {
        checkInterrupted();
        stock.forEach((key,amountAvailable)-> {
            if(amountAvailable==null || amountAvailable<0) throw new IllegalArgumentException("Negative or null stock");
        });
        var flows = new ArrayList<Flow<K>>(recipes.size());
        for (int i = 0; i < recipes.size(); i++) {
            var recipe = recipes.get(i);
            if (!Integer.valueOf(i).equals(recipe.id())) throw new IllegalArgumentException("Recipe IDs must be ordered positions");
            // Recipe has already frozen these maps.
            flows.add(new Flow<>(recipe.inputs(), recipe.outputs()));
        }
        var key = new Key<>(target, amount, List.copyOf(flows), Map.copyOf(stock));
        var cached = get(key);
        if (cached != null) {
            checkInterrupted();
            return cached;
        }
        // No cache monitor is held during search. Concurrent cold requests may
        // compute twice; cancellation of one request cannot cancel another.
        var result = new ProductionPlanner<>(recipes, key.stock(), limits).solve(target, amount);
        checkInterrupted();
        if (result.status() == ProductionPlanner.Status.FEASIBLE) put(key, result);
        return result;
    }

    private synchronized ProductionPlanner.Result<K, Integer> get(Key<K> key) {
        expire(clock.getAsLong());
        var entry = entries.get(key);
        if (entry == null) {
            Key<K> compatible = null;
            for (var candidate : entries.entrySet()) {
                checkInterrupted();
                var prior = candidate.getKey();
                if (prior.amount()!=key.amount() || !prior.target().equals(key.target()) || !prior.flows().equals(key.flows())) continue;
                boolean enough = true;
                for (var used : candidate.getValue().result().used().entrySet()) {
                    if (key.stock().getOrDefault(used.getKey(),0L)<used.getValue()) { enough=false; break; }
                }
                if (enough) compatible=prior;
            }
            if (compatible==null) { misses++; return null; }
            entry=entries.get(compatible); // refresh LRU after iteration
        }
        hits++;
        return entry.result();
    }

    private synchronized void put(Key<K> key, ProductionPlanner.Result<K, Integer> result) {
        long size = key.stock().size() + key.flows().size() + result.used().size() + result.counts().size() + 1L;
        for (var flow : key.flows()) size += flow.inputs().size() + flow.outputs().size();
        for (var block : result.program()) size += block.word().size() + 1L;
        if (size > maxWeight) return;
        long now = clock.getAsLong();
        expire(now);
        var old = entries.remove(key);
        if (old != null) weight -= old.weight();
        entries.put(key, new Entry<>(result, now, size));
        weight += size;
        var it = entries.entrySet().iterator();
        while (entries.size() > maxEntries || weight > maxWeight) {
            weight -= it.next().getValue().weight();
            it.remove();
        }
    }

    private void expire(long now) {
        var it = entries.values().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.created() >= ttlNanos) { weight -= entry.weight(); it.remove(); }
        }
    }

    synchronized Stats stats() { expire(clock.getAsLong()); return new Stats(hits, misses, entries.size(), weight); }
    synchronized void clear() { entries.clear(); weight = hits = misses = 0; }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Production planning cancelled");
    }
}
