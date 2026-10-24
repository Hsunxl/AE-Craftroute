package com.aecraftroute.addon.route.client;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.UUID;

/** Optional ProjectE public API bridge. No hard linkage when ProjectE is absent. */
final class RouteEmcAccess {
    record State(long unit,Boolean learned,BigInteger balance) {
        boolean available() {return unit>0;}
    }
    private static final State NONE=new State(0,null,null);
    private static final Api API=load();
    private record Api(Object emc,Method value,Object transmutation,Method knowledge,
                       Method learned,Method balance) {}
    private static Api load() {
        try {
            var emc=Class.forName("moze_intel.projecte.api.proxy.IEMCProxy");
            var transmutation=Class.forName("moze_intel.projecte.api.proxy.ITransmutationProxy");
            var knowledge=Class.forName("moze_intel.projecte.api.capabilities.IKnowledgeProvider");
            return new Api(emc.getField("INSTANCE").get(null),emc.getMethod("getValue",ItemStack.class),
                    transmutation.getField("INSTANCE").get(null),transmutation.getMethod("getKnowledgeProviderFor",UUID.class),
                    knowledge.getMethod("hasKnowledge",ItemStack.class),knowledge.getMethod("getEmc"));
        } catch(ReflectiveOperationException | LinkageError | RuntimeException e) {return null;}
    }
    static State query(AEKey key) {
        if(API==null || !(key instanceof AEItemKey item))return NONE;
        long unit;
        try {unit=((Number)API.value.invoke(API.emc,item.toStack())).longValue();}
        catch(ReflectiveOperationException | RuntimeException e) {return NONE;}
        if(unit<=0)return NONE;
        var player=Minecraft.getInstance().player;
        if(player!=null)try {
            var knowledge=API.knowledge.invoke(API.transmutation,player.getUUID());
            return new State(unit,(Boolean)API.learned.invoke(knowledge,item.toStack()),
                    (BigInteger)API.balance.invoke(knowledge));
        } catch(ReflectiveOperationException | RuntimeException e) { /* Client state may not yet be synchronized. */ }
        return new State(unit,null,null);
    }
}
