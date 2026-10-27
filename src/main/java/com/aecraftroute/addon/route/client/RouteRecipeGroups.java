package com.aecraftroute.addon.route.client;
import java.util.*;
import java.util.function.Function;

/** Only a known recipe identity AND its full slot alternatives/amounts authorize merging. */
public final class RouteRecipeGroups {
    private RouteRecipeGroups() {}
    public record Ingredient(String key,long amount) {}
    public record Slot(String role,List<Ingredient> alternatives) {
        public Slot {alternatives=alternatives.stream().distinct().sorted(Comparator.comparing(Ingredient::key).thenComparingLong(Ingredient::amount)).toList();}
    }
    public record Key(String recipeId,List<Slot> slots) {public Key {slots=List.copyOf(slots);}}
    public static <T> List<List<T>> group(List<T> entries,Function<T,Key> identity) {
        var result=new ArrayList<List<T>>();var known=new LinkedHashMap<Key,List<T>>();
        for(var entry:entries) {
            var key=identity.apply(entry);
            if(key==null || key.recipeId()==null || key.recipeId().isBlank()) {result.add(new ArrayList<>(List.of(entry)));continue;}
            var group=known.get(key);
            if(group==null) {group=new ArrayList<>();known.put(key,group);result.add(group);}
            group.add(entry);
        }
        return result.stream().map(List::copyOf).toList();
    }
    public static <T,K> List<T> unique(List<T> values,Function<T,K> identity) {
        var result=new LinkedHashMap<K,T>();for(var value:values)result.putIfAbsent(identity.apply(value),value);
        return List.copyOf(result.values());
    }
}
