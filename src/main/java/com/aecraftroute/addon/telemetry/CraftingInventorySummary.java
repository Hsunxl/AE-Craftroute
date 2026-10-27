package com.aecraftroute.addon.telemetry;

import appeng.api.stacks.KeyCounter;
import java.util.*;

/** Diagnostic values, not KeyCounter's opaque default object identity. */
public final class CraftingInventorySummary {
    private CraftingInventorySummary() {}
    public static List<String> of(KeyCounter counter) {
        var result=new ArrayList<String>();
        for(var entry:counter) {
            if(entry.getLongValue()==0)continue;
            if(result.size()==32){result.add("...");break;}
            result.add(entry.getKey()+" x"+entry.getLongValue());
        }
        return List.copyOf(result);
    }
}
