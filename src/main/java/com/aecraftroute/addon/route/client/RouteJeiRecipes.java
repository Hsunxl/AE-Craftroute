package com.aecraftroute.addon.route.client;

import appeng.api.stacks.*;
import com.aecraftroute.addon.route.RouteView;
import com.aecraftroute.addon.route.RouteEmcRules;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.*;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** Embed JEI's public recipe-layout API, not a copy of its internal RecipesGui screen. */
final class RouteJeiRecipes implements RouteRecipeBrowser {
    private final IJeiRuntime runtime;
    private final List<Entry<?>> recipes=new ArrayList<>();
    private AEKey key;
    private RouteEmcAccess.State emc=new RouteEmcAccess.State(0,null,null);
    private long demand=1;
    private int emcScroll,emcScrollMax;
    private int count() {return recipes.size()+(emc.available()?1:0);}
    private boolean emcSelected() {return emc.available() && index==recipes.size();}
    public void demand(long amount) {demand=Math.max(1,amount);}
    public boolean selectEmc() {
        emc=RouteEmcAccess.query(key);
        if(!emc.available())return false;
        selectIndex(recipes.size());return true;
    }
    private RouteView.Draft emcDraft() {
        var mc=Minecraft.getInstance();
        return RouteEmcRules.draft(RouteView.Stack.of(key,1,mc.level.registryAccess()),emc.unit());
    }
    private int index,x,y,w,h,bodyHeight,ticks,lastMouseX,lastMouseY;
    private float scale=1;
    private IRecipeLayoutDrawable<?> layout;
    private List<ITypedIngredient<?>> machines=List.of();
    private ITypedIngredient<?> shownMachine;
    private String error="",focusedId;
    private boolean picking,moving;
    private int pickerPage;
    private ITypedIngredient<?> held;
    private RouteView.Draft heldDraft;
    private RouteView.Draft stableDraft;
    private int pageChangedAt=-100;
    private final Map<Object,List<ITypedIngredient<?>>> catalystCache=new HashMap<>();
    private final Map<Integer,ITypedIngredient<?>> optionMachine=new HashMap<>();
    private List<ITypedIngredient<?>> catalysts(Entry<?> entry) {
        var type=entry.category().getRecipeType();
        return catalystCache.computeIfAbsent(type,t->{
            try {return runtime.getRecipeManager().createRecipeCatalystLookup(type).get().toList();}
            catch(RuntimeException ignored) {return List.of();}
        });
    }
    public void renderOption(GuiGraphics g,int index,int x,int y,int width,int mx,int my) {
        if(emc.available() && index==recipes.size()) {
            drawEmcIcon(g,x+1,y+1);
            if(index==this.index)g.fill(x+20,y,x+width,y+18,0xFFC6C6D4);
            var font=Minecraft.getInstance().font;
            g.drawString(font,font.plainSubstrByWidth((index==this.index?"✓ ":"")+(index+1)+". EMC 转换 · "+emc.unit(),Math.max(1,width-23)),x+22,y+5,0x303030,false);
            if(mx>=x && mx<x+width && my>=y && my<y+18)g.renderComponentTooltip(font,List.of(Component.literal("ProjectE · EMC 转换"),Component.literal("每个消耗 "+emc.unit()+" EMC；可选为画布获取路线")),mx,my);
            return;
        }
        if(index<0 || index>=recipes.size())return;
        var entry=recipes.get(index);var candidates=catalysts(entry);
        boolean hover=mx>=x && mx<x+width && my>=y && my<y+18;
        var machine=optionMachine.get(index);
        if(!hover || machine==null)machine=candidates.isEmpty()?null:candidates.get(Math.floorMod(ticks/60,candidates.size()));
        if(machine!=null) {optionMachine.put(index,machine);drawTyped(g,machine,x+1,y+1);}
        else {var icon=entry.category().getIcon();if(icon!=null)icon.draw(g,x+1,y+1);}
        if(index==this.index)g.fill(x+20,y,x+width,y+18,0xFFC6C6D4);
        String text=(index==this.index?"✓ ":"")+(index+1)+". "+entry.category().getTitle().getString();
        g.drawString(Minecraft.getInstance().font,Minecraft.getInstance().font.plainSubstrByWidth(text,Math.max(1,width-23)),x+22,y+5,0x303030,false);
        if(hover)g.renderComponentTooltip(Minecraft.getInstance().font,List.of(Component.literal(text),Component.literal(recipeId(entry)),Component.literal("点击行预览/选择；设备图标查询设备配方")),mx,my);
    }
    public void showOptionMachine(int index) {
        if(emc.available() && index==recipes.size()) {showEmcMachine();return;}
        var machine=optionMachine.get(index);if(machine!=null)showIngredient(machine,RecipeIngredientRole.OUTPUT);
    }
    private record MachineInfo(List<ITypedIngredient<?>> candidates,mezz.jei.api.gui.drawable.IDrawable icon,String title) {}
    private final Map<String,MachineInfo> treeMachines=new HashMap<>();
    private final Map<String,ITypedIngredient<?>> treeShown=new HashMap<>();
    public void renderMachine(String id,GuiGraphics g,int x,int y,int mx,int my) {
        if(RouteEmcRules.isEmc(id)) {drawEmcIcon(g,x+1,y+1);return;}
        var info=treeMachines.get(id);if(info==null) {g.drawString(Minecraft.getInstance().font,"?",x+4,y+4,0x555555,false);return;}
        boolean hover=mx>=x && mx<x+18 && my>=y && my<y+18;
        var machine=treeShown.get(id);
        if(!hover || machine==null)machine=info.candidates.isEmpty()?null:info.candidates.get(Math.floorMod(ticks/60,info.candidates.size()));
        if(machine!=null) {treeShown.put(id,machine);drawTyped(g,machine,x+1,y+1);}
        else if(info.icon!=null)info.icon.draw(g,x+1,y+1);
        if(hover)g.renderComponentTooltip(Minecraft.getInstance().font,List.of(Component.literal(machine==null?info.title:name(machine)),Component.literal("点击查询此设备自身配方")),mx,my);
    }
    public void showMachine(String id) {if(RouteEmcRules.isEmc(id)) {showEmcMachine();return;}var machine=treeShown.get(id);if(machine!=null)showIngredient(machine,RecipeIngredientRole.OUTPUT);}
    private <T> void remember(Entry<T> entry) {
        var id=entry.category.getRegistryName(entry.recipe);
        if(id!=null)treeMachines.put(id.toString(),new MachineInfo(catalysts(entry),entry.category.getIcon(),entry.category.getTitle().getString()));
    }
    private double pressX,pressY,dragX,dragY;
    public List<Option> options() {
        var result=new ArrayList<Option>();
        for(int i=0;i<recipes.size();i++)result.add(new Option(i,label(recipes.get(i))));
        if(emc.available())result.add(new Option(recipes.size(),"EMC 转换"));
        return List.copyOf(result);
    }
    private <T> String label(Entry<T> entry) {return entry.category().getTitle().getString();}
    private <T> String recipeId(Entry<T> entry) {
        var id=entry.category().getRegistryName(entry.recipe());
        return id==null?entry.category().getRecipeType().getUid().toString():id.toString();
    }
    public void selectIndex(int selected) {if(selected>=0 && selected<count()) {index=selected;load();pageChangedAt=ticks;}}
    public RouteView.Draft currentDraft() {
        if(emcSelected())return emcDraft();
        if(layout==null)return null;
        try {stableDraft=RouteJeiPlugin.readDraft(layout,stableDraft,null);return stableDraft;}catch(RuntimeException e) {error=e.getMessage();return null;}
    }
    private AEKey ingredientKey(ITypedIngredient<?> value) {
        try {return RouteJeiConverters.from(value).what();}catch(RuntimeException e) {return null;}
    }
    public boolean release(double mx,double my,int button,IngredientDrop drop) {
        if(held==null)return false;
        var value=held;var draft=heldDraft;held=null;heldDraft=null;
        if(moving || Math.hypot(mx-pressX,my-pressY)>4) {
            var ingredient=ingredientKey(value);
            if(draft!=null && ingredient!=null)drop.accept(draft,ingredient,mx,my);
        } else showIngredient(value,RecipeIngredientRole.OUTPUT);
        moving=false;return true;
    }
    public boolean draggingIngredient() {return held!=null && moving;}
    public void renderDrag(GuiGraphics g) {
        if(held==null || !moving)return;
        g.pose().pushPose();g.pose().translate(0,0,1300);
        try {drawTyped(g,held,(int)dragX-8,(int)dragY-8);}finally {g.pose().popPose();}
    }
    public void focusRecipe(String id) {
        if(Objects.equals(id,focusedId))return;
        focusedId=id;
        if(RouteEmcRules.isEmc(id) && emc.available()) {selectIndex(recipes.size());return;}
        for(int i=0;i<recipes.size();i++)if(matchesId(recipes.get(i),id)) {index=i;load();break;}
    }
    private <T> boolean matchesId(Entry<T> entry,String id) {
        var actual=entry.category().getRegistryName(entry.recipe());return actual!=null && actual.toString().equals(id);
    }
    private record Entry<T>(IRecipeCategory<T> category,T recipe,IFocusGroup focus) {}
    RouteJeiRecipes(IJeiRuntime runtime) {this.runtime=runtime;}
    public void select(AEKey key) {
        if(Objects.equals(key,this.key))return;
        this.key=key;optionMachine.clear();focusedId=null;index=0;picking=false;held=null;recipes.clear();layout=null;error="";
        emc=RouteEmcAccess.query(key);
        if(key==null)return;
        try {
            Object value=RouteJeiConverters.ingredient(key,1);
            if(value==null)return;
            var typed=runtime.getIngredientManager().createTypedIngredient(value,false).orElse(null);
            if(typed==null)return;
            var factory=runtime.getJeiHelpers().getFocusFactory();
            var focus=factory.createFocus(RecipeIngredientRole.OUTPUT,typed);
            var group=factory.createFocusGroup(List.of(focus));
            runtime.getRecipeManager().createRecipeCategoryLookup().limitFocus(List.of(focus)).get().forEach(category->{
                try {collect(category,group);}catch(RuntimeException e) {error="部分 JEI 分类读取失败";}
            });
            load();
        }catch(RuntimeException e) {error="JEI 配方读取失败："+e.getClass().getSimpleName();}
    }
    private <T> void collect(IRecipeCategory<T> category,IFocusGroup focus) {
        runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).limitFocus(focus.getAllFocuses()).get()
                .forEach(recipe->{var entry=new Entry<>(category,recipe,focus);recipes.add(entry);remember(entry);});
    }
    private <T> IRecipeLayoutDrawable<T> create(Entry<T> entry) {
        return runtime.getRecipeManager().createRecipeLayoutDrawable(entry.category(),entry.recipe(),entry.focus()).orElse(null);
    }
    private void load() {
        layout=null;stableDraft=null;shownMachine=null;machines=List.of();bodyHeight=0;error="";emcScroll=0;
        if(emcSelected() || recipes.isEmpty())return;
        try {layout=create(recipes.get(index));}
        catch(RuntimeException e) {error="此插件的原生配方布局无法创建";}
        if(layout!=null)try {machines=runtime.getRecipeManager().createRecipeCatalystLookup(layout.getRecipeCategory().getRecipeType()).get().toList();}
        catch(RuntimeException ignored) {machines=List.of();}
    }
    public void page(int delta) {if(count()>0)selectIndex(Math.max(0,Math.min(index+delta,count()-1)));}
    public void tick() {
        ticks++;
        if(ticks%20==0) {
            boolean wasAvailable=emc.available();emc=RouteEmcAccess.query(key);
            if(wasAvailable!=emc.available()) {index=Math.min(index,Math.max(0,count()-1));load();}
        }
        if(layout!=null)try {layout.tick();}catch(RuntimeException e) {error="配方动画插件更新失败";}
    }
    private boolean bodyContains(double mx,double my) {return layout!=null && bodyHeight>0 && mx>=x && mx<x+w && my>=y+22 && my<y+22+bodyHeight;}
    private double nativeX(double mx) {return (mx-x)/scale;}
    private double nativeY(double my) {return (my-y-22)/scale;}
    public void render(GuiGraphics g,int x,int y,int w,int h,int mx,int my) {
        this.x=x;this.y=y;this.w=w;this.h=h;lastMouseX=mx;lastMouseY=my;
        var font=Minecraft.getInstance().font;
        bodyHeight=Math.max(0,h-64);
        if(emcSelected())renderEmc(g);
        else if(layout==null)g.drawString(font,font.plainSubstrByWidth(error.isEmpty()?"无可展示的 JEI 配方":error,w),x,y+22,0x666666,false);
        else {
            String title=layout.getRecipeCategory().getTitle().getString();
            boolean machineHover=mx>=x+w-20 && mx<x+w-2 && my>=y && my<y+18;
            if(!machineHover || shownMachine==null)shownMachine=machines.isEmpty()?null:machines.get(Math.floorMod(ticks/60,machines.size()));
            String machineName=shownMachine==null?title:name(shownMachine);
            button(g,x,y,w-24,"配方 "+(index+1)+" / "+count()+" ▾ "+font.plainSubstrByWidth(title,Math.max(1,w-110)));
            if(ticks-pageChangedAt<30)g.fill(x,y+18,x+w-24,y+20,0xFF3E6B8A);
            if(mx>=x && mx<x+w-24 && my>=y && my<y+18)g.renderComponentTooltip(font,List.of(Component.literal("当前配方 "+(index+1)+" / "+count()),Component.literal(recipeId(recipes.get(index))),Component.literal("点击打开配方列表；翻页只预览，不替换画布")),mx,my);
            try {drawMachine(g,mx,my,title);}catch(RuntimeException e) {error="JEI 设备图标无法绘制";}
            if(bodyHeight>=24) {
                var border=layout.getRectWithBorder();var recipeRect=layout.getRect();
                scale=Math.min(1F,Math.min((w-8F)/Math.max(1,border.getWidth()),(float)bodyHeight/Math.max(1,border.getHeight())));
                scale=Math.max(0.05F,scale);
                layout.setPosition(4+recipeRect.getX()-border.getX(),recipeRect.getY()-border.getY());
                int rx=(int)nativeX(mx),ry=(int)nativeY(my);
                g.enableScissor(x,y+22,x+w,y+22+bodyHeight);
                g.pose().pushPose();g.pose().translate(x,y+22,0);g.pose().scale(scale,scale,1);
                try {layout.drawRecipe(g,rx,ry);}
                catch(RuntimeException e) {error="此插件原生布局绘制失败，可切换其他配方";}
                finally {g.pose().popPose();g.disableScissor();}
                if(bodyContains(mx,my)) {
                    g.pose().pushPose();g.pose().translate(x,y+22,300);g.pose().scale(scale,scale,1);
                    try {layout.getInputHandler().handleMouseMoved(rx,ry);layout.drawOverlays(g,rx,ry);}
                    catch(RuntimeException e) {error="此插件原生提示绘制失败";}
                    finally {g.pose().popPose();}
                }
            }
        }
        if(layout!=null && !error.isEmpty())g.drawString(font,font.plainSubstrByWidth(error,w),x,y+h-32,0xAA3333,false);
        if(count()>0) {
            button(g,x,y+h-40,w,"当前 "+(index+1)+" / "+count()+(ticks-pageChangedAt<30?" · 已切换":emcSelected()?" · 仅规划获取":" · 点击上方选择"));
            button(g,x,y+h-20,28,index>0?"‹":"—");button(g,x+32,y+h-20,28,index+1<count()?"›":"—");
            if(layout!=null || emcSelected())button(g,x+64,y+h-20,w-64,emcSelected()?"使用 EMC 路线":"使用此配方");
        }
        if(picking) {
            int rows=pickerRows(),start=pickerPage*rows;
            g.pose().pushPose();g.pose().translate(0,0,350);
            g.fill(x,y+20,x+w,y+20+(rows+1)*18,0xFF555555);
            for(int row=0;row<rows && start+row<count();row++) {
                button(g,x,y+20+row*18,w,"");renderOption(g,start+row,x+1,y+20+row*18,w-2,mx,my);
            }
            button(g,x,y+20+rows*18,w,"‹ 上页    "+(pickerPage+1)+"/"+Math.max(1,(count()+rows-1)/rows)+"    下页 ›");
            g.pose().popPose();
        }
    }
    private int pickerRows() {return Math.max(1,Math.min(6,(h-40)/18));}
    private net.minecraft.world.item.ItemStack emcMachine() {
        return new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.parse("projecte:transmutation_table")));
    }
    private void drawEmcIcon(GuiGraphics g,int x,int y) {g.renderItem(emcMachine(),x,y);}
    private void showEmcMachine() {runtime.getRecipesGui().show(List.of(runtime.getJeiHelpers().getFocusFactory().createFocus(
            RecipeIngredientRole.OUTPUT,mezz.jei.api.constants.VanillaTypes.ITEM_STACK,emcMachine())));}
    private void renderEmc(GuiGraphics g) {
        var font=Minecraft.getInstance().font;
        button(g,x,y,w-24,"配方 "+(index+1)+" / "+count()+" ▾ EMC 转换");
        drawEmcIcon(g,x+w-20,y+1);
        if(ticks-pageChangedAt<30)g.fill(x,y+18,x+w-24,y+20,0xFF3E6B8A);
        var total=RouteEmcRules.cost(emc.unit(),demand);
        if(h<=66)return;
        var lines=new ArrayList<String>();
        lines.add("ProjectE · EMC 获取路线");
        lines.add("单个消耗："+emc.unit()+" EMC");
        lines.add("需求数量："+demand);
        lines.add("总消耗："+total+" EMC");
        lines.add(emc.learned()==null?"学习状态：等待同步":emc.learned()?"学习状态：已学习":"学习状态：未学习（需先学习）");
        lines.add(emc.balance()==null?"个人 EMC：等待同步":"个人 EMC："+emc.balance());
        if(emc.balance()!=null && total.compareTo(emc.balance())>0)lines.add("还缺："+total.subtract(emc.balance())+" EMC");
        lines.add("在转换桌/转换台取出；此处仅规划，不扣 EMC、不生成物品。");
        var wrappedLines=new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for(var line:lines)wrappedLines.addAll(font.split(Component.literal(line),Math.max(1,w-12)));
        int area=Math.max(1,h-68);
        emcScrollMax=Math.max(0,wrappedLines.size()*11-area);emcScroll=Math.min(emcScroll,emcScrollMax);
        int ty=y+24-emcScroll;
        g.enableScissor(x,y+22,x+w,y+h-44);
        try {for(var line:wrappedLines) {g.drawString(font,line,x+4,ty,0x303030,false);ty+=11;}}
        finally {g.disableScissor();}
        if(emcScrollMax>0) {
            int thumb=Math.max(6,area*area/(area+emcScrollMax));
            int top=y+24+(area-thumb)*emcScroll/emcScrollMax;
            g.fill(x+w-3,y+24,x+w-1,y+24+area,0xFF878FA5);
            g.fill(x+w-3,top,x+w-1,top+thumb,0xFF3E6B8A);
        }
    }
    private <T> void drawTyped(GuiGraphics g,ITypedIngredient<T> ingredient,int x,int y) {
        var renderer=runtime.getIngredientManager().getIngredientRenderer(ingredient.getType());
        float factor=Math.min(16F/Math.max(1,renderer.getWidth()),16F/Math.max(1,renderer.getHeight()));
        g.pose().pushPose();g.pose().translate(x,y,20);g.pose().scale(factor,factor,1);
        try {renderer.render(g,ingredient.getIngredient());}finally {g.pose().popPose();}
    }
    private <T> String name(ITypedIngredient<T> ingredient) {
        return runtime.getIngredientManager().getIngredientHelper(ingredient.getType()).getDisplayName(ingredient.getIngredient());
    }
    private void drawMachine(GuiGraphics g,int mx,int my,String title) {
        int sx=x+w-20,sy=y;
        appeng.client.gui.Icon.SLOT_BACKGROUND.getBlitter().dest(sx,sy).blit(g);
        if(shownMachine!=null)drawTyped(g,shownMachine,sx+1,sy+1);
        else {
            var icon=layout.getRecipeCategory().getIcon();
            if(icon!=null) {
                float factor=Math.min(16F/Math.max(1,icon.getWidth()),16F/Math.max(1,icon.getHeight()));
                g.pose().pushPose();g.pose().translate(sx+1,sy+1,20);g.pose().scale(factor,factor,1);
                try {icon.draw(g,0,0);}finally {g.pose().popPose();}
            }
        }
        if(mx>=sx && mx<sx+18 && my>=sy && my<sy+18) {
            var lines=List.<Component>of(Component.literal(shownMachine==null?title:name(shownMachine)),Component.literal(
                    shownMachine==null?"分类未提供可查询的设备物品":"点击查看此机器自身的合成配方"));
            g.renderComponentTooltip(Minecraft.getInstance().font,lines,mx,my);
        }
    }
    private <T> void showIngredient(ITypedIngredient<T> ingredient,RecipeIngredientRole role) {
        runtime.getRecipesGui().show(List.of(runtime.getJeiHelpers().getFocusFactory().createFocus(role,ingredient)));
    }
    private IJeiUserInput input(InputConstants.Key key) {
        return new IJeiUserInput() {
            public InputConstants.Key getKey() {return key;}
            public int getModifiers() {return (Screen.hasShiftDown()?1:0)|(Screen.hasControlDown()?2:0)|(Screen.hasAltDown()?4:0);}
            public boolean isSimulate() {return false;}
            public boolean is(KeyMapping mapping) {return mapping.isActiveAndMatches(key);}
            public boolean is(mezz.jei.api.runtime.IJeiKeyMapping mapping) {return mapping.isActiveAndMatches(key);}
        };
    }
    private void button(GuiGraphics g,int x,int y,int w,String text) {
        g.fill(x,y,x+w,y+18,0xFF373737);g.fill(x+1,y+1,x+w-1,y+17,0xFFADB0C4);
        g.fill(x+1,y+1,x+w-1,y+2,0xFFFFFFFF);
        g.drawString(Minecraft.getInstance().font,text,x+4,y+5,0x303030,false);
    }
    public boolean click(double mx,double my,int button,Consumer<RouteView.Draft> choose) {
        if(picking && mx>=x && mx<x+w && my>=y+20 && my<y+20+(pickerRows()+1)*18) {
            int row=(int)(my-y-20)/18,rows=pickerRows();
            if(row==rows)pickerPage=Math.max(0,Math.min(pickerPage+(mx<x+w/2?-1:1),Math.max(0,(count()+rows-1)/rows-1)));
            else {int selected=pickerPage*rows+row;if(mx<x+20)showOptionMachine(selected);else {selectIndex(selected);picking=false;}}
            return true;
        }
        if(button==0 && mx>=x && mx<x+w-24 && my>=y && my<y+18) {picking=!picking;pickerPage=index/pickerRows();return true;}
        picking=false;
        if((layout!=null || emcSelected()) && button==0 && mx>=x+w-20 && mx<x+w-2 && my>=y && my<y+18) {
            if(emcSelected())showEmcMachine();else if(shownMachine!=null)showIngredient(shownMachine,RecipeIngredientRole.OUTPUT);return true;
        }
        if(bodyContains(mx,my)) {
            double rx=nativeX(mx),ry=nativeY(my);
            var clickedSlot=layout.getRecipeSlotUnderMouse(rx,ry);
            if(button==0 && clickedSlot.isPresent() && clickedSlot.get().getRole()==RecipeIngredientRole.INPUT) {
                var displayed=clickedSlot.get().getDisplayedIngredient();
                if(displayed.isPresent() && ingredientKey(displayed.get())!=null) {
                    held=displayed.get();stableDraft=RouteJeiPlugin.readDraft(layout,stableDraft,clickedSlot.get());heldDraft=stableDraft;moving=false;pressX=dragX=mx;pressY=dragY=my;return true;
                }
            }
            if(layout.getInputHandler().handleInput(rx,ry,input(InputConstants.Type.MOUSE.getOrCreate(button))))return true;
            var ingredient=layout.getRecipeSlotUnderMouse(rx,ry).flatMap(slot->slot.getDisplayedIngredient());
            if(ingredient.isPresent() && (button==0 || button==1)) {
                showIngredient(ingredient.get(),button==0?RecipeIngredientRole.OUTPUT:RecipeIngredientRole.INPUT);return true;
            }
        }
        if(button!=0 || mx<x || mx>=x+w || my<y+h-20 || my>=y+h-2)return false;
        if(count()==1 && mx<x+60) {error="JEI 当前仅返回 1 张产出配方；材料替代项不等于另一张配方";return true;}
        if(mx<x+28)page(-1);
        else if(mx<x+60)page(1);
        else if(mx>=x+64 && (layout!=null || emcSelected())) {
            try {var draft=currentDraft();if(draft!=null)choose.accept(draft);}
            catch(RuntimeException e) {error=e.getMessage();layout=null;}
        }
        return true;
    }
    public boolean scroll(double mx,double my,double sx,double sy) {
        if(picking && mx>=x && mx<x+w && my>=y+20 && my<y+20+(pickerRows()+1)*18) {
            pickerPage=Math.max(0,Math.min(pickerPage+(sy<0?1:-1),Math.max(0,(count()+pickerRows()-1)/pickerRows()-1)));return true;
        }
        if(emcSelected() && mx>=x && mx<x+w && my>=y+20 && my<y+h-40) {
            emcScroll=Math.max(0,Math.min(emcScroll+(sy<0?22:-22),emcScrollMax));return true;
        }
        return bodyContains(mx,my) && layout.getInputHandler().handleMouseScrolled(nativeX(mx),nativeY(my),sx,sy);
    }
    public boolean drag(double mx,double my,int button,double dx,double dy) {
        if(held!=null) {dragX=mx;dragY=my;if(Math.hypot(mx-pressX,my-pressY)>4)moving=true;return true;}
        return bodyContains(mx,my) && layout.getInputHandler().handleMouseDragged(nativeX(mx),nativeY(my),InputConstants.Type.MOUSE.getOrCreate(button),dx/scale,dy/scale);
    }
    public boolean key(int keyCode,int scanCode) {
        if(!bodyContains(lastMouseX,lastMouseY))return false;
        double rx=nativeX(lastMouseX),ry=nativeY(lastMouseY);
        if(layout.getInputHandler().handleInput(rx,ry,input(InputConstants.getKey(keyCode,scanCode))))return true;
        if(keyCode!=org.lwjgl.glfw.GLFW.GLFW_KEY_R && keyCode!=org.lwjgl.glfw.GLFW.GLFW_KEY_U)return false;
        var ingredient=layout.getRecipeSlotUnderMouse(rx,ry).flatMap(slot->slot.getDisplayedIngredient());
        if(ingredient.isEmpty())return false;
        showIngredient(ingredient.get(),keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_R?RecipeIngredientRole.OUTPUT:RecipeIngredientRole.INPUT);return true;
    }
}
