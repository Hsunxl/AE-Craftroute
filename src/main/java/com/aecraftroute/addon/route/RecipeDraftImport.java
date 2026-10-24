package com.aecraftroute.addon.route;

import java.util.*;
import java.util.function.Function;

/** All-or-nothing import of the JEI display. Non-material roles remain explicit caveats, not consumption. */
public final class RecipeDraftImport {
    public enum Role { INPUT, OUTPUT, OTHER }
    public record Slot(Role role, List<?> alternatives, Object displayed) {}
    public record Result(List<RouteView.Stack> inputs,List<RouteView.Stack> outputs) {}
    public static boolean sameRecipe(RouteView.Draft a,RouteView.Draft b) {
        return a!=null && b!=null && a.recipeId().equals(b.recipeId()) && a.category().equals(b.category());
    }
    /** Preserve each slot's selected alternative while JEI's display animation cycles. */
    public static Object preferred(Slot slot, RouteView.Stack previous, Function<Object,RouteView.Stack> decode) {
        if(previous!=null)for(var candidate:slot.alternatives()) {
            try {if(decode.apply(candidate).key().equals(previous.key()))return candidate;}
            catch(RuntimeException ignored) { /* An unrelated unsupported alternative is not the selected material. */ }
        }
        return slot.displayed();
    }
    public static Result read(List<Slot> slots,Function<Object,RouteView.Stack> decode) {
        var inputs=new ArrayList<RouteView.Stack>();var outputs=new ArrayList<RouteView.Stack>();
        for(var slot:slots) {
            if(slot.role()==Role.OTHER || slot.alternatives().isEmpty())continue;
            Object ingredient=slot.displayed()!=null?slot.displayed():slot.alternatives().get(0);
            var stack=Objects.requireNonNull(decode.apply(ingredient),"未知材料类型");
            if(stack.amount()<=0)throw new IllegalArgumentException("JEI 未提供有效数量，不能导入");
            var dest=slot.role()==Role.INPUT?inputs:outputs;
            if(dest.size()>=128)throw new IllegalArgumentException("配方槽位超过 128");
            dest.add(stack);
        }
        if(outputs.isEmpty())throw new IllegalArgumentException("JEI 没有提供可导入的产物");
        return new Result(List.copyOf(inputs),List.copyOf(outputs));
    }
}
