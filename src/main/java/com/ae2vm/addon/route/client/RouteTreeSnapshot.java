package com.ae2vm.addon.route.client;

import appeng.api.stacks.GenericStack;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.ae2vm.addon.route.RouteView;
import net.minecraft.core.HolderLookup;
import java.util.*;

/** Reads AE2CT's already-synchronized plan, not an invented first network recipe. Optional at runtime. */
final class RouteTreeSnapshot {
    private RouteTreeSnapshot() {}
    static GenericStack output(CraftConfirmMenu menu) {
        return com.ae2vm.addon.route.RouteSynchronizedPlan.output(menu.getPlan(),GenericStack.class);
    }
    @SuppressWarnings("unchecked")
    static Map<String,RouteView.Choice> read(CraftConfirmMenu menu,HolderLookup.Provider registries) {
        var result=new LinkedHashMap<String,RouteView.Choice>();
        var summary=menu.getPlan();
        if(summary==null)return result;
        try {
            Object job=com.ae2vm.addon.route.RouteSynchronizedPlan.job(summary);
            if(job==null)return result;
            var recipes=(List<?>)job.getClass().getField("recipes").get(job);
            int index=0;
            for(Object recipe:recipes) {
                if(index>=4096)break;
                var inputs=(List<GenericStack>)recipe.getClass().getMethod("inputs").invoke(recipe);
                var outputs=(List<GenericStack>)recipe.getClass().getMethod("outputs").invoke(recipe);
                var choice=new RouteView.Choice("plan:"+index++,
                        inputs.stream().map(s->RouteView.Stack.of(s.what(),s.amount(),registries)).toList(),
                        outputs.stream().map(s->RouteView.Stack.of(s.what(),s.amount(),registries)).toList(),false);
                for(var output:choice.outputs())result.putIfAbsent(output.key(),choice);
            }
        } catch(ReflectiveOperationException | RuntimeException ignored) {
            // Without AE2CT's optional summary extension the UI explicitly falls back to a network preview.
        }
        return Map.copyOf(result);
    }
}
