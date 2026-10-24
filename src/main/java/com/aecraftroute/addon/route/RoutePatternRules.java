package com.aecraftroute.addon.route;

import java.util.*;
import java.util.function.BiPredicate;

/** Pure validation/matching, separated from registries and storage mutations. */
public final class RoutePatternRules {
    private RoutePatternRules() {}
    public static void validate(RouteView.Draft draft) {
        if(draft==null || draft.recipeId()==null || draft.recipeId().length()>256)
            throw new IllegalArgumentException("配方标识无效");
        if(RouteEmcRules.isEmc(draft))throw new IllegalArgumentException("EMC 转换请在 ProjectE 设备中完成，不能编码为 AE2 样板");
        validateStacks(draft.inputs(),81);
        validateStacks(draft.outputs(),26);
    }
    private static void validateStacks(List<RouteView.Stack> stacks,int limit) {
        if(stacks==null || stacks.isEmpty() || stacks.size()>limit)
            throw new IllegalArgumentException("样板槽位不符合编码限制");
        for(var stack:stacks)
            if(stack==null || stack.key()==null || stack.key().isBlank() || stack.key().length()>4096
                    || stack.amount()<=0 || stack.amount()>Integer.MAX_VALUE)
                throw new IllegalArgumentException("样板材料或数量无效");
    }
    /** Perfect matching avoids greedily spending a restricted ingredient on a broad tag slot. */
    public static int[] assign(int count,BiPredicate<Integer,Integer> matches) {
        if(count<0 || count>9)throw new IllegalArgumentException("合成槽位超过 9");
        int[] owner=new int[count]; Arrays.fill(owner,-1);
        for(int slot=0;slot<count;slot++)
            if(!augment(slot,owner,new boolean[count],matches))
                throw new IllegalArgumentException("JEI 显示材料不匹配服务端配方");
        int[] result=new int[count];
        for(int input=0;input<count;input++)result[owner[input]]=input;
        return result;
    }
    private static boolean augment(int slot,int[] owner,boolean[] seen,BiPredicate<Integer,Integer> matches) {
        for(int input=0;input<owner.length;input++) if(!seen[input] && matches.test(slot,input)) {
            seen[input]=true;
            if(owner[input]<0 || augment(owner[input],owner,seen,matches)) {
                owner[input]=slot;return true;
            }
        }
        return false;
    }
}
