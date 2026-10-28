package com.aecraftroute.addon.route;

import java.util.*;
import java.util.function.Function;

/** Immutable hard constraints. Empty results must NEVER fall back to automatic recipes. */
public record RoutePolicy<K>(Map<K, Rule> rules, Canvas<K> canvas) {
    /** Path contracts are request-specific, immutable, and consumed by the real CPU planner. */
    public record Path<K>(String path, K key, String choiceId, RecipePatternMapping.Recipe recipe,RouteView.Draft draft) {
        public Path(String path,K key,String choiceId){this(path,key,choiceId,null,null);}
        public Path(String path,K key,String choiceId,RecipePatternMapping.Recipe recipe){this(path,key,choiceId,recipe,null);}
    }
    public record Canvas<K>(K target, long amount, List<Path<K>> paths) {
        public Canvas { paths=List.copyOf(paths); }
    }
    public RoutePolicy(Map<K,Rule> rules) { this(rules,null); }
    public boolean empty() { return rules.isEmpty() && canvas==null; }
    /** Undo a forced canvas without deleting independent per-material route rules. */
    public RoutePolicy<K> withoutCanvas() { return canvas==null ? this : new RoutePolicy<>(rules,null); }
    public record Rule(String locked, Set<String> banned, boolean stockOnly) {
        public Rule { banned = Set.copyOf(banned); }
        public static Rule automatic() { return new Rule(null, Set.of(), false); }
    }
    public RoutePolicy { rules = Map.copyOf(rules); }
    public Rule rule(K key) { return key == null ? Rule.automatic() : rules.getOrDefault(key, Rule.automatic()); }
    public <P> List<P> filter(K key, Collection<P> candidates, Function<P, String> identity) {
        Rule rule = rule(key);
        if (rule.stockOnly()) return List.of();
        if (rule.locked() == null && rule.banned().isEmpty()) return List.copyOf(candidates);
        return candidates.stream().filter(p -> {
            String id = identity.apply(p);
            return (rule.locked() == null || rule.locked().equals(id)) && !rule.banned().contains(id);
        }).toList();
    }
    public RoutePolicy<K> change(K key, String action, String id) {
        var copy = new HashMap<>(rules);
        Rule old = rule(key);
        switch (action) {
            case "auto" -> copy.remove(key);
            case "stock" -> copy.put(key, new Rule(null, Set.of(), true));
            case "lock" -> {
                if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing pattern identity");
                copy.put(key, new Rule(id, Set.of(), false));
            }
            case "ban" -> {
                if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing pattern identity");
                var banned = new HashSet<>(old.banned());
                if (!banned.add(id)) banned.remove(id);
                if (banned.size() > 128) throw new IllegalArgumentException("单个材料最多禁用 128 个样板");
                copy.put(key, new Rule(Objects.equals(old.locked(), id) ? null : old.locked(), banned, false));
            }
            default -> throw new IllegalArgumentException("Unknown route action");
        }
        if (Rule.automatic().equals(copy.get(key))) copy.remove(key);
        if (copy.size() > 128) throw new IllegalArgumentException("最多设置 128 个材料的路线");
        return new RoutePolicy<>(copy);
    }
}
