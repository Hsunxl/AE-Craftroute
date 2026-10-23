package com.ae2vm.addon.route;

import java.math.BigInteger;
import java.util.List;

/** EMC acquisition is a planning source, not an AE2 processing recipe. */
public final class RouteEmcRules {
    public static final String PREFIX="ae2vm:emc/";
    private RouteEmcRules() {}
    public static boolean isEmc(String id) {return id!=null && id.startsWith(PREFIX);}
    public static boolean isEmc(RouteView.Draft draft) {return draft!=null && isEmc(draft.recipeId());}
    public static BigInteger cost(long unit,long demand) {
        if(unit<=0 || demand<0)throw new IllegalArgumentException("EMC 数值或需求无效");
        return BigInteger.valueOf(unit).multiply(BigInteger.valueOf(demand));
    }
    public static RouteView.Draft draft(RouteView.Stack output,long unit) {
        cost(unit,1);
        return new RouteView.Draft(PREFIX+Integer.toUnsignedString(output.key().hashCode(),16),"EMC 转换",
                List.of(),List.of(new RouteView.Stack(output.key(),output.name(),1)),Long.toString(unit));
    }
    public static long unit(RouteView.Draft draft) {
        if(!isEmc(draft))return 0;
        try {return Math.max(0,Long.parseLong(draft.note()));}catch(RuntimeException e) {return 0;}
    }
}
