package com.aecraftroute.addon.route.client;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;

/** AppliedE EMC is a numeric resource, never a machine catalyst or a personal ProjectE balance. */
public final class RouteEmcPresentation {
    private RouteEmcPresentation() {}
    public static boolean isTransmutationDevice(String id) {return "projecte:transmutation_table".equals(id)||"projecte:transmutation_tablet".equals(id);}
    public static boolean isCurrencyType(String type) {return "appliede:emc".equals(type);}
    public static boolean isCurrency(AEKey key) {return key!=null && isCurrencyType(key.getType().getId().toString());}
    public static BigInteger cost(long units,long batches) {
        if(units<0 || batches<0)throw new IllegalArgumentException("negative EMC cost");
        return BigInteger.valueOf(units).multiply(BigInteger.valueOf(batches));
    }
    public static boolean inline(boolean currency,boolean hasRecipe,boolean cycle) {return currency && !hasRecipe && !cycle;}
}
