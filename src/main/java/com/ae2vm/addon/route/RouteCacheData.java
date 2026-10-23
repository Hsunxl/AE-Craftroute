package com.ae2vm.addon.route;

import net.minecraft.nbt.CompoundTag;

/** Persistent buffer bookkeeping without player/world initialization. Never stores client-supplied item contents. */
final class RouteCacheData {
    static final int SIZE=18;
    private static final String TAG="ae2vm_pattern_cache_v1";
    private RouteCacheData() {}
    static CompoundTag read(CompoundTag playerData) {return playerData.getCompound(TAG).copy();}
    static int freeSlot(CompoundTag cache) {
        for(int i=0;i<SIZE;i++)if(!cache.contains(Integer.toString(i)))return i;
        throw new IllegalArgumentException("样板缓存已满，请放入背包或上传到供应器");
    }
    static CompoundTag entry(CompoundTag cache,int slot,String token) {
        if(slot<0 || slot>=SIZE || !cache.contains(Integer.toString(slot)))throw new IllegalArgumentException("缓存槽已变化，请刷新");
        var entry=cache.getCompound(Integer.toString(slot));
        RouteTransferRules.validateToken(slot,SIZE,token,entry.getString("token"));
        return entry;
    }
    static void commit(CompoundTag playerData,CompoundTag cache) {
        cache.putLong("revision",cache.getLong("revision")+1);
        playerData.put(TAG,cache.copy());
    }
    static void clonePlayer(CompoundTag original,CompoundTag replacement) {
        if(original.contains(TAG))replacement.put(TAG,original.getCompound(TAG).copy());
    }
}
