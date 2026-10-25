package com.aecraftroute.addon.route.client;
import com.aecraftroute.addon.route.RouteView;
import java.util.*;

/** Equivalent display only: this never identifies the machine that owns a native AE pattern. */
public final class RouteRecipePreviewMatch {
    private RouteRecipePreviewMatch() {}
    public static boolean matches(RouteView.Choice pattern,RouteView.Draft recipe) {
        if(pattern==null || pattern.alternatives() || recipe==null)return false;
        try {
            var inputs=counts(pattern.inputs());var outputs=counts(pattern.outputs());
            return inputs.equals(counts(recipe.inputs())) &&
                    !outputs.isEmpty() && outputs.equals(counts(recipe.outputs()));
        } catch(IllegalArgumentException | ArithmeticException e) {return false;}
    }
    private static Map<String,Long> counts(List<RouteView.Stack> stacks) {
        if(stacks==null)throw new IllegalArgumentException();
        var result=new HashMap<String,Long>();
        for(var stack:stacks) {
            if(stack==null || stack.key()==null || stack.key().isBlank() || stack.amount()<=0)throw new IllegalArgumentException();
            result.merge(stack.key(),stack.amount(),Math::addExact);
        }
        return result;
    }
}
