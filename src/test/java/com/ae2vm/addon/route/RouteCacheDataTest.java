package com.ae2vm.addon.route;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteCacheDataTest {
    CompoundTag entry(String token) {
        var entry=new CompoundTag();entry.putString("token",token);
        var item=new CompoundTag();item.putString("id","ae2:crafting_pattern");entry.put("item",item);return entry;
    }
    @Test void cacheSurvivesReloadAndUnrelatedPlayerDataIsUntouched() {
        var player=new CompoundTag();player.putInt("other_mod",42);
        var cache=RouteCacheData.read(player);cache.put("0",entry("a"));RouteCacheData.commit(player,cache);
        var reloaded=RouteCacheData.read(player.copy());
        assertEquals("a",RouteCacheData.entry(reloaded,0,"a").getString("token"));
        assertEquals(42,player.getInt("other_mod"));assertEquals(1,reloaded.getLong("revision"));
    }
    @Test void editingSnapshotDoesNotMutateStoredPlayerData() {
        var player=new CompoundTag();var cache=RouteCacheData.read(player);
        cache.put("0",entry("a"));RouteCacheData.commit(player,cache);cache.remove("0");
        assertTrue(RouteCacheData.read(player).contains("0"));
    }
    @Test void deathCloneCopiesWithoutAliasingAndPreservesToken() {
        var original=new CompoundTag();var data=RouteCacheData.read(original);data.put("0",entry("a"));RouteCacheData.commit(original,data);
        var replacement=new CompoundTag();RouteCacheData.clonePlayer(original,replacement);
        var second=RouteCacheData.read(replacement);assertEquals("a",RouteCacheData.entry(second,0,"a").getString("token"));
        second.remove("0");RouteCacheData.commit(replacement,second);
        assertTrue(RouteCacheData.read(original).contains("0"));assertFalse(RouteCacheData.read(replacement).contains("0"));
    }
    @Test void fullCacheCannotOverwriteAndMalformedSlotRemainsReserved() {
        var cache=new CompoundTag();
        for(int i=0;i<RouteCacheData.SIZE;i++)cache.put(Integer.toString(i),entry("a"+i));
        assertThrows(IllegalArgumentException.class,()->RouteCacheData.freeSlot(cache));
        cache.putString("0","malformed but must not erase");
        assertThrows(IllegalArgumentException.class,()->RouteCacheData.freeSlot(cache));
        cache.remove("8");assertEquals(8,RouteCacheData.freeSlot(cache));
    }
    @Test void staleTokenAndAlreadyMovedItemCannotBeReadAsAValidSource() {
        var cache=new CompoundTag();cache.put("0",entry("new"));
        assertThrows(IllegalArgumentException.class,()->RouteCacheData.entry(cache,0,"old"));
        assertThrows(IllegalArgumentException.class,()->RouteCacheData.entry(cache,1,"new"));
        cache.remove("0");assertThrows(IllegalArgumentException.class,()->RouteCacheData.entry(cache,0,"new"));
    }
}
