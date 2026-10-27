package com.aecraftroute.addon.route.client;

import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.route.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Independent, read-only browser: hovering never changes the inspector's selected recipe. */
final class RouteRecipeHover {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RouteRecipeHover.class);
    private RouteRecipePreviewBox box;
    private RouteRecipeBrowser preview;
    private RouteView.Choice choice;
    private RouteView.Draft draft;
    private String identity,id,title;
    private boolean failed;
    private boolean equivalent;
    private int ax,ay,aw,ah;
    void clear() {box=null;preview=null;choice=null;draft=null;identity=null;failed=false;}
    boolean contains(double mx,double my) {return box!=null && box.contains(mx,my);}
    boolean visible() {return box!=null;}
    void offer(String identity,AEKey target,String id,RouteView.Choice choice,RouteView.Draft draft,
               int x,int y,int width,int height,int sw,int sh) {
        if(!Objects.equals(this.identity,identity) || !Objects.equals(this.choice,choice) || !Objects.equals(this.draft,draft)) {
            clear();this.identity=identity;this.choice=choice;this.draft=draft;this.id=id;
            title=target==null?"配方预览":target.getDisplayName().getString();
            equivalent=id==null;
            if(!RouteEmcRules.isEmc(id) && RouteClient.recipeBrowser!=null)try {
                preview=RouteClient.recipeBrowser.get();preview.select(target);
                this.id=preview.previewRecipeId(choice,id);
                if(this.id==null && !preview.previewRecipeSearchPending())preview=null;
                else if(this.id!=null)preview.focusRecipe(this.id);
            } catch(RuntimeException e) {failure(e);}
        }
        ax=x;ay=y;aw=width;ah=height;box=RouteRecipePreviewBox.of(x,y,width,sw,sh);
    }
    private void failure(RuntimeException e) {
        if(!failed)LOG.warn("Recipe hover preview failed for {}; using actual pattern inputs/outputs",id,e);
        failed=true;preview=null;
    }
    void tick() {if(preview!=null)try {
        if(id==null && preview.previewRecipeSearchPending()) {
            id=preview.previewRecipeId(choice,null);
            if(id!=null)preview.focusRecipe(id);
            else if(!preview.previewRecipeSearchPending()) {preview=null;return;}
        }
        preview.tick();
    }catch(RuntimeException e){failure(e);}}
    void render(GuiGraphics g,int mx,int my) {
        if(box==null)return;
        if(!(mx>=ax && mx<ax+aw && my>=ay && my<ay+ah) && !box.contains(mx,my)) {clear();return;}
        var font=Minecraft.getInstance().font;int x=box.x(),y=box.y(),w=box.width(),h=box.height();
        g.pose().pushPose();
        try {
            g.pose().translate(0,0,1800);
            g.fill(x,y,x+w,y+h,0xFF373737);g.fill(x+1,y+1,x+w-1,y+h-1,0xFFCBCCD4);
            g.drawString(font,font.plainSubstrByWidth(title,w-12),x+6,y+6,0xFF2D2F38,false);
            boolean nativeLayout=false;
            if(preview!=null)try {nativeLayout=preview.renderPreview(id,g,x+4,y+20,w-8,h-40,mx,my);}
            catch(RuntimeException e) {failure(e);}
            if(!nativeLayout)fallback(g,x+6,y+22,w-12,mx,my);
            String hint=nativeLayout?(equivalent?"等价 JEI 配方（不代表样板所在机器）":"JEI 原生配方 · 点击徽标在侧栏查看"):RouteEmcRules.isEmc(draft)?"EMC 获取路线 · 无机器处理动画":"网络样板输入 / 输出（非合成格位）";
            g.drawString(font,font.plainSubstrByWidth(hint,w-12),x+6,y+h-13,0xFF4B4F63,false);
        } finally {g.pose().popPose();}
    }
    private void fallback(GuiGraphics g,int x,int y,int width,int mx,int my) {
        if(choice==null)return;
        var font=Minecraft.getInstance().font;
        if(RouteEmcRules.isEmc(draft)) {
            g.drawString(font,font.plainSubstrByWidth("EMC × "+RouteEmcRules.unit(draft)+" / 个 →",width),x,y,0xFF3E6B8A,false);
            slots(g,choice.outputs(),x,y+22,width,mx,my);return;
        }
        g.drawString(font,"输入",x,y,0xFF4B4F63,false);slots(g,choice.inputs(),x,y+12,width,mx,my);
        g.drawString(font,"↓ 输出",x,y+77,0xFF4B4F63,false);slots(g,choice.outputs(),x,y+89,width,mx,my);
    }
    private void slots(GuiGraphics g,List<RouteView.Stack> stacks,int x,int y,int width,int mx,int my) {
        if(stacks==null)return;
        var mc=Minecraft.getInstance();int columns=Math.max(1,width/22),limit=Math.min(stacks.size(),columns*2);
        for(int i=0;i<limit;i++) {
            var stack=stacks.get(i);if(stack==null)continue;
            int sx=x+i%columns*22,sy=y+i/columns*22;
            g.fill(sx,sy,sx+20,sy+20,0xFF9A9FB4);
            try {var key=mc.level==null?null:stack.decode(mc.level.registryAccess());if(key!=null)appeng.api.client.AEKeyRendering.drawInGui(mc,g,sx+2,sy+2,key);}
            catch(RuntimeException e) {g.drawString(mc.font,"?",sx+6,sy+5,0xFF4B4F63,false);}
            if(stack.amount()!=1)g.drawString(mc.font,RouteGraphMath.format(stack.amount()),sx+1,sy+12,0xFFFFFFFF,true);
            if(mx>=sx && mx<sx+20 && my>=sy && my<sy+20)g.renderComponentTooltip(mc.font,
                    List.of(Component.literal(stack.name()+" × "+stack.amount())),mx,my);
        }
        if(stacks.size()>limit)g.drawString(mc.font,"另有 "+(stacks.size()-limit)+" 项，点击徽标查看",x,y+46,0xFF4B4F63,false);
    }
}
