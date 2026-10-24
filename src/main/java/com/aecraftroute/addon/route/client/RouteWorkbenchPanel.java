package com.aecraftroute.addon.route.client;

import appeng.api.stacks.AEItemKey;
import appeng.client.gui.Icon;
import com.aecraftroute.addon.route.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.BiConsumer;

/** Cache and provider drawer remain in the canvas, using server-authoritative transfer acknowledgements. */
final class RouteWorkbenchPanel {
    private final BiConsumer<String,String> send;
    private RouteWorkbench.State state;
    private RouteWorkbench.CacheSlot dragged;
    private boolean open,compact,collapseCache,cacheExpanded;
    private int cachePage,cacheRows,expandedY;
    private int x,y,w,bottom,drawerX,drawerY,drawerW,columns=8,inventoryRows=1;
    private double dragX,dragY;
    private int mouseX,mouseY;
    boolean hovered() {return contains(mouseX,mouseY);}
    private final List<Button> buttons=new ArrayList<>();
    private final List<CacheHit> cacheHits=new ArrayList<>();
    private final List<SlotHit> slotHits=new ArrayList<>();
    private record Button(int x,int y,int w,String label,Runnable action) {}
    private record CacheHit(int x,int y,RouteWorkbench.CacheSlot item) {}
    private record SlotHit(int x,int y,String provider,RouteWorkbench.ProviderSlot item) {}
    RouteWorkbenchPanel(BiConsumer<String,String> send) {this.send=send;}
    void layout(int x,int w,int bottom,int canvasLeft,int canvasWidth,int canvasTop,boolean compact,boolean collapseCache) {
        this.x=x;this.w=w;this.bottom=bottom;
        this.compact=compact;this.collapseCache=collapseCache;
        int cacheColumns=Math.max(1,w/18);
        cacheRows=compact?1:(RouteWorkbench.CACHE_SIZE+cacheColumns-1)/cacheColumns;
        expandedY=bottom-(18+cacheRows*18+22);
        y=collapseCache && !cacheExpanded?bottom-20:expandedY;
        drawerW=Math.min(320,canvasWidth-8);drawerX=canvasLeft+canvasWidth-drawerW-4;
        drawerY=Math.max(canvasTop+18,bottom-230);
        columns=Math.max(1,Math.min(18,(drawerW-16)/18));
        inventoryRows=Math.max(1,Math.min(12,(bottom-drawerY-93)/18));
    }
    int top() {return collapseCache?bottom-20:y;}
    int dropBottom() {return open?drawerY:bottom;}
    boolean open() {return open;}
    RouteWorkbench.ViewRequest view(int providerPage,int slotPage) {return new RouteWorkbench.ViewRequest(Math.max(0,providerPage),Math.max(0,slotPage),columns,inventoryRows);}
    RouteWorkbench.ViewRequest view() {return view(state==null?0:state.providerPage(),state==null?0:state.slotPage());}
    void refresh() {send.accept("workbench",RouteNetwork.GSON.toJson(view()));}
    void accept(RouteWorkbench.State state) {this.state=state;}
    boolean contains(double mx,double my) {
        return inside(mx,my,x-2,y-3,w+4,bottom-y+3) || open && inside(mx,my,drawerX,drawerY,drawerW,bottom-drawerY);
    }
    private boolean inside(double mx,double my,int x,int y,int w,int h) {return mx>=x && mx<x+w && my>=y && my<y+h;}
    private void label(GuiGraphics g,String s,int x,int y,int max,int color) {
        var font=Minecraft.getInstance().font;g.drawString(font,font.plainSubstrByWidth(s,Math.max(1,max)),x,y,color,false);
    }
    private void button(GuiGraphics g,int x,int y,int w,String text,Runnable action) {
        buttons.add(new Button(x,y,w,text,action));g.fill(x,y,x+w,y+18,0xFF373737);
        g.fill(x+1,y+1,x+w-1,y+17,0xFFADB0C4);g.fill(x+1,y+1,x+w-1,y+2,0xFFFFFFFF);
        label(g,text,x+3,y+5,w-6,0xFF2D2F38);
    }
    private void item(GuiGraphics g,RouteView.Stack stack,int x,int y) {
        if(stack==null)return;
        try {
            var key=stack.decode(Minecraft.getInstance().level.registryAccess());
            appeng.api.client.AEKeyRendering.drawInGui(Minecraft.getInstance(),g,x,y,key);
        } catch(RuntimeException e) {label(g,"?",x+4,y+4,12,0xFFAA3333);}
    }
    private RouteView.Stack preview(RouteView.Stack pattern,RouteView.Stack product) {
        return net.minecraft.client.gui.screens.Screen.hasShiftDown() && product!=null?product:pattern;
    }
    private void slot(GuiGraphics g,int x,int y,RouteView.Stack icon) {
        Icon.SLOT_BACKGROUND.getBlitter().dest(x,y).blit(g);item(g,icon,x+1,y+1);
        if(net.minecraft.client.gui.screens.Screen.hasShiftDown() && icon!=null && icon.amount()>1)
            label(g,RouteGraphMath.format(icon.amount()),x+9,y+10,26,0xFFFFFFFF);
    }
    void render(GuiGraphics g,int mx,int my) {
        mouseX=mx;mouseY=my;
        buttons.clear();cacheHits.clear();slotHits.clear();
        g.fill(x-2,y-3,x+w+2,bottom,0xFFCBCCD4);g.fill(x-2,y-3,x+w+2,y-2,0xFF373737);
        String title="样板缓存 "+(state==null?"…":state.cache().size()+"/"+RouteWorkbench.CACHE_SIZE);
        if(collapseCache)button(g,x,y,w-90,(cacheExpanded?"▴ ":"▾ ")+title,()->{
            cacheExpanded=!cacheExpanded;y=cacheExpanded?expandedY:bottom-20;
        });
        else label(g,title,x,y+3,w-88,0xFF2D2F38);
        button(g,x+w-86,y,86,open?"收起供应器":"上传到供应器",()->{open=!open;if(open)refresh();});
        if(!collapseCache || cacheExpanded) {
            int cols=Math.max(1,w/18),pageSize=cols*cacheRows;
            int pages=Math.max(1,(RouteWorkbench.CACHE_SIZE+pageSize-1)/pageSize);
            cachePage=Math.floorMod(cachePage,pages);
            int first=cachePage*pageSize;
            for(int i=first;i<Math.min(RouteWorkbench.CACHE_SIZE,first+pageSize);i++) {
                int local=i-first,sx=x+(local%cols)*18,sy=y+20+(local/cols)*18;
                RouteWorkbench.CacheSlot entry=null;
                if(state!=null)for(var candidate:state.cache())if(candidate.slot()==i) {entry=candidate;break;}
                slot(g,sx,sy,entry==null || dragged!=null && dragged.slot()==i?null:preview(entry.icon(),entry.product()));
                if(entry!=null)cacheHits.add(new CacheHit(sx,sy,entry));
            }
            if(pages>1) {
                button(g,x,bottom-18,18,"‹",()->cachePage=Math.floorMod(cachePage-1,pages));
                button(g,x+w-18,bottom-18,18,"›",()->cachePage=Math.floorMod(cachePage+1,pages));
                button(g,x+22,bottom-18,w-44,"放入背包 "+(cachePage+1)+"/"+pages,()->send.accept("takecache",RouteNetwork.GSON.toJson(view())));
            } else button(g,x,bottom-18,w,"放入背包",()->send.accept("takecache",RouteNetwork.GSON.toJson(view())));
        }
        if(open)renderProviders(g,mx,my);
        String tip=null;
        for(var hit:cacheHits)if(inside(mx,my,hit.x,hit.y,18,18))tip="已编码样板："+hit.item.output()+"（拖入供应器空槽）";
        for(var hit:slotHits)if(inside(mx,my,hit.x,hit.y,18,18))tip="槽位 "+(hit.item.slot()+1)+" · "+hit.item.output();
        if(tip!=null && dragged==null)g.renderComponentTooltip(Minecraft.getInstance().font,List.of(Component.literal(tip)),mx,my);
    }
    private void renderProviders(GuiGraphics g,int mx,int my) {
        appeng.client.gui.style.BackgroundGenerator.draw(drawerW,bottom-drawerY,g,drawerX,drawerY);
        label(g,"网络供应器 "+(state==null?"…":state.providerCount()),drawerX+6,drawerY+6,drawerW-48,0xFF2D2F38);
        button(g,drawerX+drawerW-24,drawerY+3,18,"×",()->open=false);
        if(state==null || state.providers().isEmpty())label(g,"当前网络无在线供应器",drawerX+6,drawerY+36,drawerW-12,0xFF666666);
        if(state!=null && !state.providers().isEmpty()) {
            var provider=state.providers().get(0);int rowY=drawerY+25;
            item(g,provider.icon(),drawerX+6,rowY);
            label(g,provider.name(),drawerX+26,rowY+3,drawerW-32,0xFF2D2F38);
            label(g,"已用样板槽 "+provider.occupiedSlots()+" / "+provider.totalSlots(),drawerX+6,rowY+20,drawerW-12,0xFF555555);
            for(int j=0;j<provider.slots().size();j++) {
                var cell=provider.slots().get(j);int sx=drawerX+8+(j%columns)*18,sy=rowY+33+(j/columns)*18;
                slot(g,sx,sy,preview(cell.icon(),cell.product()));slotHits.add(new SlotHit(sx,sy,provider.id(),cell));
                if(dragged!=null && cell.icon()==null && inside(mx,my,sx,sy,18,18))g.fill(sx,sy,sx+18,sy+18,0x667E62AA);
            }
            int first=provider.totalSlots()==0?0:provider.slotStart()+1,last=provider.slotStart()+provider.slots().size();
            label(g,"槽位 "+first+"–"+last+" / "+provider.totalSlots()+" · 滚轮滚动槽位",drawerX+6,bottom-30,drawerW-12,0xFF555555);
        }
        button(g,drawerX+6,bottom-20,18,"‹",()->page(-1,0));
        label(g,"设备 "+(state==null?"…":(state.providerPage()+1)+" / "+state.providerCount())+" · 空白处/底栏滚轮切换",drawerX+30,bottom-15,drawerW-60,0xFF404040);
        button(g,drawerX+drawerW-24,bottom-20,18,"›",()->page(1,0));
    }
    private void page(int providers,int slots) {
        if(state==null)return;
        int p=RouteNavigation.cycle(state.providerPage(),providers,state.providerPages());
        int s=providers==0?RouteNavigation.cycle(state.slotPage(),slots,state.slotPages()):0;
        send.accept("workbench",RouteNetwork.GSON.toJson(view(p,s)));
    }
    boolean click(double mx,double my,int mouseButton) {
        if(!contains(mx,my))return false;
        if(mouseButton==0) {
            for(var button:buttons)if(inside(mx,my,button.x,button.y,button.w,18)) {button.action.run();return true;}
            for(var hit:cacheHits)if(inside(mx,my,hit.x,hit.y,18,18)) {dragged=hit.item;dragX=mx;dragY=my;return true;}
        }
        return true;
    }
    boolean drag(double mx,double my) {if(dragged==null)return false;dragX=mx;dragY=my;return true;}
    boolean release(double mx,double my) {
        if(dragged==null)return false;
        for(var hit:slotHits)if(hit.item.icon()==null && inside(mx,my,hit.x,hit.y,18,18)) {
            var upload=new RouteWorkbench.Upload(dragged.slot(),dragged.token(),hit.provider,hit.item.slot(),view());
            send.accept("upload",RouteNetwork.GSON.toJson(upload));break;
        }
        dragged=null;return true;
    }
    void renderDrag(GuiGraphics g) {
        if(dragged==null)return;
        g.pose().pushPose();g.pose().translate(0,0,1200);
        item(g,dragged.icon(),(int)dragX-8,(int)dragY-8);g.pose().popPose();
    }
    boolean scroll(double mx,double my,double dy) {
        if(!contains(mx,my))return false;
        if(open && inside(mx,my,drawerX,drawerY,drawerW,bottom-drawerY) && dy!=0) {
            boolean overSlots=false;
            for(var hit:slotHits)if(inside(mx,my,hit.x,hit.y,18,18)) {overSlots=true;break;}
            int delta=(dy<0?1:-1)*Math.max(1,(int)Math.abs(dy));
            if(overSlots && state!=null && state.slotPages()>1)page(0,delta);
            else page(delta,0);
        }
        return true;
    }
}
