package com.aecraftroute.addon.route;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.AEBaseMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Real server-owned pattern inventory. Client icons are never used as transfer contents. */
@EventBusSubscriber(modid="aecraftroute")
public final class RouteWorkbench {
    public static final int CACHE_SIZE=RouteCacheData.SIZE;
    private static final int PROVIDERS_PER_PAGE=1;
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    private static final class Session {
        final IGrid grid; final int menu;
        final IdentityHashMap<PatternContainer,String> ids=new IdentityHashMap<>();
        Session(IGrid grid,int menu) {this.grid=grid;this.menu=menu;}
    }
    public record ViewRequest(int providerPage,int slotPage,int columns,int rows) {
        public ViewRequest(int providerPage,int slotPage,int columns) {this(providerPage,slotPage,columns,1);}
        public ViewRequest { if(rows==0)rows=1;if(rows<1 || rows>12)throw new IllegalArgumentException("供应器行数无效");if(providerPage<0 || slotPage<0 || columns<1 || columns>18)throw new IllegalArgumentException("供应器分页参数无效"); }
        public static ViewRequest first() {return new ViewRequest(0,0,8);}
    }
    public record Upload(int cacheSlot,String token,String provider,int slot,ViewRequest view) {
        public Upload {
            if(cacheSlot<0 || cacheSlot>=CACHE_SIZE || slot<0 || slot>65535 || token==null || token.length()!=36 || provider==null || provider.length()!=36)
                throw new IllegalArgumentException("缓存上传参数无效");
            if(view==null)view=ViewRequest.first();
        }
    }
    public record CacheSlot(int slot,String token,RouteView.Stack icon,String output,RouteView.Stack product) {}
    public record ProviderSlot(int slot,RouteView.Stack icon,String output,RouteView.Stack product) {}
    public record Provider(String id,String name,RouteView.Stack icon,int totalSlots,int slotStart,List<ProviderSlot> slots,int occupiedSlots) {}
    public record State(boolean workbench,String message,long revision,List<CacheSlot> cache,List<Provider> providers,
                        int providerPage,int providerPages,int providerCount,int slotPage,int slotPages) {}
    private RouteWorkbench() {}

    private static Session session(ServerPlayer player,AEBaseMenu menu,IGrid grid) {
        return SESSIONS.compute(player.getUUID(),(id,old)->old!=null && old.menu==menu.containerId && old.grid==grid?old:new Session(grid,menu.containerId));
    }
    private static CompoundTag cache(ServerPlayer player) {return RouteCacheData.read(player.getPersistentData());}
    private static void commit(ServerPlayer player,CompoundTag cache) {RouteCacheData.commit(player.getPersistentData(),cache);}
    private static ItemStack stack(ServerPlayer player,CompoundTag entry) {
        var result=ItemStack.parse(player.registryAccess(),entry.getCompound("item"))
                .orElseThrow(()->new IllegalArgumentException("缓存样板数据无法读取，已保留原始数据"));
        if(result.isEmpty() || result.getCount()!=1)throw new IllegalArgumentException("缓存样板数量无效，已保留原始数据");
        return result;
    }
    public static void storeEncoded(ServerPlayer player,AEBaseMenu menu,IGrid grid,ItemStack encoded) {
        if(encoded.isEmpty() || encoded.getCount()!=1 || PatternDetailsHelper.decodePattern(encoded,player.serverLevel())==null)
            throw new IllegalArgumentException("不能缓存无效样板");
        var cache=cache(player); int slot=RouteCacheData.freeSlot(cache);
        // Serialize before debiting a blank, so an unsupported codec cannot consume materials.
        var entry=new CompoundTag();entry.putString("token",UUID.randomUUID().toString());
        entry.put("item",encoded.save(player.registryAccess()));cache.put(Integer.toString(slot),entry);
        var storage=grid.getStorageService().getInventory();var blank=AEItemKey.of(AEItems.BLANK_PATTERN.stack());
        if(storage.extract(blank,1,Actionable.SIMULATE,menu.getActionSource())!=1)
            throw new IllegalArgumentException("网络内没有可提取的空白样板");
        if(storage.extract(blank,1,Actionable.MODULATE,menu.getActionSource())!=1)
            throw new IllegalArgumentException("空白样板提取失败");
        commit(player,cache);
    }
    /** Only a reference from this player's last view can authorize moving this exact cached item. */
    private static CompoundTag validateCacheSlot(CompoundTag cache,int slot,String token) {return RouteCacheData.entry(cache,slot,token);}
    public static String takeToBackpack(ServerPlayer player,AEBaseMenu menu) {
        int moved=0;var cache=cache(player);
        for(int i=0;i<CACHE_SIZE;i++)if(cache.contains(Integer.toString(i))) {
            var item=stack(player,cache.getCompound(Integer.toString(i)));
            boolean room=false;
            for(int j=0;j<36;j++) {
                var existing=player.getInventory().getItem(j);
                if(existing.isEmpty() || ItemStack.isSameItemSameComponents(existing,item)
                        && existing.getCount()<Math.min(existing.getMaxStackSize(),player.getInventory().getMaxStackSize())) {room=true;break;}
            }
            // Inventory.add may discard overflow in creative mode; don't call it without actual capacity.
            if(!room)continue;
            var remaining=item.copy();
            player.getInventory().add(remaining);
            if(remaining.isEmpty()) {cache.remove(Integer.toString(i));commit(player,cache);moved++;}
            else if(remaining.getCount()!=item.getCount())throw new IllegalStateException("样板缓存必须每槽一张");
        }
        player.getInventory().setChanged();menu.broadcastChanges();
        return "已放入背包 "+moved+" 张样板；放不下的仍留在缓存";
    }
    private static List<PatternContainer> activeProviders(IGrid grid) {
        var set=Collections.newSetFromMap(new IdentityHashMap<PatternContainer,Boolean>());
        for(Class<?> machine:grid.getMachineClasses())if(PatternContainer.class.isAssignableFrom(machine))
            collect(grid,machine.asSubclass(PatternContainer.class),set);
        var result=new ArrayList<>(set);
        result.sort(Comparator.comparingLong(PatternContainer::getTerminalSortOrder));
        return result;
    }
    private static <T extends PatternContainer> void collect(IGrid grid,Class<T> machine,Set<PatternContainer> result) {
        for(var provider:grid.getActiveMachines(machine))if(RouteProviderWindow.visible(provider.getGrid()==grid,provider.isVisibleInTerminal(),provider.getTerminalPatternInventory().size()))result.add(provider);
    }
    public static String upload(ServerPlayer player,AEBaseMenu menu,IGrid grid,Upload upload) {
        if(upload==null || upload.provider()==null)throw new IllegalArgumentException("未选择供应器");
        var cache=cache(player);var entry=validateCacheSlot(cache,upload.cacheSlot(),upload.token());
        var session=session(player,menu,grid);
        PatternContainer provider=null;
        for(var candidate:activeProviders(grid))if(upload.provider().equals(session.ids.get(candidate))) {provider=candidate;break;}
        if(provider==null)throw new IllegalArgumentException("供应器已离线或已离开当前网络，样板仍在缓存");
        var inventory=provider.getTerminalPatternInventory();int slot=upload.slot();
        if(slot<0 || slot>=inventory.size())throw new IllegalArgumentException("供应器槽位已变化");
        var item=stack(player,entry);
        RouteTransferRules.transferOne(item.getCount(),inventory.getStackInSlot(slot).isEmpty(),inventory.isItemValid(slot,item),
                ()->inventory.insertItem(slot,item.copy(),true).isEmpty(),
                ()->inventory.insertItem(slot,item.copy(),false).isEmpty(),
                ()->{cache.remove(Integer.toString(upload.cacheSlot()));commit(player,cache);});
        return "样板已放入所选供应器第 "+(slot+1)+" 槽";
    }
    private static RouteView.Stack icon(ServerPlayer player,ItemStack item) {
        return item.isEmpty()?null:RouteView.Stack.of(AEItemKey.of(new ItemStack(item.getItem())),item.getCount(),player.registryAccess());
    }
    private static String output(ServerPlayer player,ItemStack item) {
        try {
            var details=PatternDetailsHelper.decodePattern(item,player.serverLevel());
            return details==null?item.getHoverName().getString():details.getPrimaryOutput().what().getDisplayName().getString();
        } catch(RuntimeException e) {return item.getHoverName().getString();}
    }
    private static RouteView.Stack product(ServerPlayer player,ItemStack pattern) {
        if(pattern.isEmpty())return null;
        try {
            var details=PatternDetailsHelper.decodePattern(pattern,player.serverLevel());
            if(details==null)return null;var output=details.getPrimaryOutput();
            return RouteView.Stack.of(output.what(),output.amount(),player.registryAccess());
        }catch(RuntimeException e) {return null;}
    }
    public static State view(ServerPlayer player,AEBaseMenu menu,IGrid grid,ViewRequest request,String message) {
        if(request==null)request=ViewRequest.first();
        var session=session(player,menu,grid);var providers=activeProviders(grid);
        session.ids.keySet().removeIf(p->!providers.contains(p));
        for(var provider:providers)session.ids.computeIfAbsent(provider,p->UUID.randomUUID().toString());
        int pages=Math.max(1,(providers.size()+PROVIDERS_PER_PAGE-1)/PROVIDERS_PER_PAGE);
        int page=Math.min(request.providerPage(),pages-1),start=page*PROVIDERS_PER_PAGE;
        var shown=providers.subList(start,Math.min(start+PROVIDERS_PER_PAGE,providers.size()));
        int slotPages=shown.isEmpty()?1:RouteProviderWindow.of(shown.get(0).getTerminalPatternInventory().size(),request.columns(),request.rows(),request.slotPage()).pages();
        int slotPage=Math.min(request.slotPage(),slotPages-1);
        var rows=new ArrayList<Provider>();
        for(var provider:shown) {
            var inv=provider.getTerminalPatternInventory();var group=provider.getTerminalGroup();
            var window=RouteProviderWindow.of(inv.size(),request.columns(),request.rows(),slotPage);
            int slotStart=window.first();
            var slots=new ArrayList<ProviderSlot>();
            for(int i=slotStart;i<window.end();i++) {
                var item=inv.getStackInSlot(i);slots.add(new ProviderSlot(i,icon(player,item),item.isEmpty()?"空槽":output(player,item),product(player,item)));
            }
            int occupied=0;for(int i=0;i<inv.size();i++)if(!inv.getStackInSlot(i).isEmpty())occupied++;
            rows.add(new Provider(session.ids.get(provider),group.name().getString(),group.icon()==null?null:
                    RouteView.Stack.of(group.icon(),1,player.registryAccess()),inv.size(),slotStart,List.copyOf(slots),occupied));
        }
        var cache=cache(player);var cached=new ArrayList<CacheSlot>();
        for(int i=0;i<CACHE_SIZE;i++)if(cache.contains(Integer.toString(i))) {
            var entry=cache.getCompound(Integer.toString(i));var item=stack(player,entry);
            cached.add(new CacheSlot(i,entry.getString("token"),icon(player,item),output(player,item),product(player,item)));
        }
        return new State(true,message,cache.getLong("revision"),List.copyOf(cached),List.copyOf(rows),page,pages,providers.size(),slotPage,slotPages);
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone event) {
        // A GUI-owned buffer must not disappear when a player dies or changes player entity.
        RouteCacheData.clonePlayer(event.getOriginal().getPersistentData(),event.getEntity().getPersistentData());
        SESSIONS.remove(event.getOriginal().getUUID());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {SESSIONS.remove(event.getEntity().getUUID());}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {SESSIONS.clear();}
}
