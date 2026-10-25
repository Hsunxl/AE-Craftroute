package com.aecraftroute.addon.route.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/** Hover an icon to open; moving into the adjacent tray keeps it open for individual clicks. */
public final class RouteMachineTray<T> {
    @FunctionalInterface public interface Draw<T> {void draw(GuiGraphics g,T value,int x,int y);}
    private List<T> values=List.of();
    private RouteMachineGrid grid;
    private int ax,ay,aw,ah,first;
    private int lastMouseX=-1,lastMouseY=-1;
    public void clear() {grid=null;values=List.of();first=0;}
    public void offer(List<T> values,int x,int y,int width,int height,int screenWidth,int screenHeight) {
        if(values.isEmpty() || grid!=null && grid.contains(lastMouseX,lastMouseY))return;
        if(!this.values.equals(values) || ax!=x || ay!=y)first=0;
        this.values=List.copyOf(values);ax=x;ay=y;aw=width;ah=height;
        grid=RouteMachineGrid.of(x,y,width,values.size(),screenWidth,screenHeight);
    }
    public void offerWorld(GuiGraphics g,List<T> values,int x,int y) {
        var matrix=g.pose().last().pose();
        var a=matrix.transformPosition(x,y,0,new org.joml.Vector3f());
        var b=matrix.transformPosition(x+18,y+18,0,new org.joml.Vector3f());
        var screen=Minecraft.getInstance().screen;if(screen==null)return;
        offer(values,(int)Math.floor(a.x),(int)Math.floor(a.y),Math.max(1,(int)Math.ceil(b.x-a.x)),
                Math.max(1,(int)Math.ceil(b.y-a.y)),screen.width,screen.height);
    }
    public void render(GuiGraphics g,int mx,int my,Draw<T> draw,Function<T,String> name) {
        lastMouseX=mx;lastMouseY=my;
        if(grid==null)return;
        boolean anchor=mx>=ax && mx<ax+aw && my>=ay && my<ay+ah;
        if(!anchor && !grid.contains(mx,my)) {clear();return;}
        var font=Minecraft.getInstance().font;
        g.pose().pushPose();g.pose().translate(0,0,2000);
        try {
            g.fill(grid.x(),grid.y(),grid.x()+grid.width(),grid.y()+grid.height(),0xFF373737);
            g.fill(grid.x()+1,grid.y()+1,grid.x()+grid.width()-1,grid.y()+grid.height()-1,0xFFCBCCD4);
            g.drawString(font,"机器 "+values.size()+(values.size()>grid.capacity()?" · 滚轮":""),grid.x()+4,grid.y()+5,0x303030,false);
            for(int i=0;i<grid.capacity() && first+i<values.size();i++) {
                int x=grid.x()+4+i%grid.columns()*20,y=grid.y()+18+i/grid.columns()*20;
                appeng.client.gui.Icon.SLOT_BACKGROUND.getBlitter().dest(x,y).blit(g);
                draw.draw(g,values.get(first+i),x+1,y+1);
            }
            int hit=grid.hit(mx,my,first);
            if(hit>=0)g.renderComponentTooltip(font,List.of(Component.literal(name.apply(values.get(hit))),Component.literal("点击查询此机器配方")),mx,my);
        } finally {g.pose().popPose();}
    }
    public boolean click(double mx,double my,int button,Consumer<T> show) {
        if(grid==null || !grid.contains(mx,my))return false;
        int hit=grid.hit(mx,my,first);if(button==0 && hit>=0)show.accept(values.get(hit));return true;
    }
    public boolean scroll(double mx,double my,double delta) {
        if(grid==null || !grid.contains(mx,my))return false;
        int max=Math.max(0,((values.size()+grid.columns()-1)/grid.columns()-grid.rows())*grid.columns());
        first=Math.max(0,Math.min(max,first+(delta<0?grid.columns():delta>0?-grid.columns():0)));return true;
    }
}
