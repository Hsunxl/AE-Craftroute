package com.aecraftroute.addon.route.client;

import appeng.api.stacks.*;
import com.aecraftroute.addon.route.*;
import mezz.jei.api.*;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.*;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.*;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.*;

@JeiPlugin
public final class RouteJeiPlugin implements IModPlugin {
    private static IJeiRuntime currentRuntime;
    public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath("aecraftroute","manual_routes"); }
    @Override public void onRuntimeAvailable(IJeiRuntime runtime) {
        currentRuntime=runtime;
        RouteClient.recipeBrowser = () -> new RouteJeiRecipes(runtime);
        RouteClient.showJei = key -> {
            if (Minecraft.getInstance().screen instanceof RouteScreen screen) { screen.showRecipes(key); return; }
            if (key instanceof AEItemKey item) runtime.getRecipesGui().show(List.of(runtime.getJeiHelpers().getFocusFactory()
                    .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, item.toStack())));
            else Minecraft.getInstance().player.displayClientMessage(Component.literal("流体配方请从 JEI 界面查找并点击 R 导入"),false);
        };
    }
    @Override public void onRuntimeUnavailable() { RouteClient.showJei=null; RouteClient.recipeBrowser=null; currentRuntime=null; }
    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(RouteScreen.class, screen -> {
            var window=Minecraft.getInstance().getWindow();
            // Opening events run before Screen.init. Snapshot one consistent geometry for JEI.
            var layout=RouteLayout.forScreen(screen.width,screen.height,
                    window.getGuiScaledWidth(),window.getGuiScaledHeight());
            return new mezz.jei.api.gui.handlers.IGuiProperties() {
            public Class<? extends net.minecraft.client.gui.screens.Screen> screenClass() { return RouteScreen.class; }
            public int guiLeft() { return 0; }
            public int guiTop() { return 0; }
            public int guiXSize() { return layout.contentRight(); }
            public int guiYSize() { return layout.height(); }
            public int screenWidth() { return layout.width(); }
            public int screenHeight() { return layout.height(); }
            };
        });
        registration.addGhostIngredientHandler(RouteScreen.class, new IGhostIngredientHandler<RouteScreen>() {
            @Override
            public <I> List<IGhostIngredientHandler.Target<I>> getTargetsTyped(RouteScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
                Object raw = ingredient.getIngredient();
                AEKey key = null;
                if (raw instanceof ItemStack stack) key = AEItemKey.of(stack);
                else if (raw instanceof FluidStack fluid) key = AEFluidKey.of(fluid);
                if (key == null) return List.of();
                AEKey target = key;
                return List.of(new IGhostIngredientHandler.Target<>() {
                    @Override public Rect2i getArea() { return screen.getDropArea(); }
                    @Override public void accept(I ignored) { screen.acceptDroppedIngredient(target); }
                });
            }

            @Override public void onComplete() {}
        });
    }

    @Override public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new IRecipeButtonControllerFactory() {
            @Override public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
                return new IIconButtonController() {
                    public void initState(IButtonState state) {
                        state.setIcon(new IDrawable() {
                            public int getWidth(){return 12;}
                            public int getHeight(){return 12;}
                            public void draw(GuiGraphics g,int x,int y){g.drawString(Minecraft.getInstance().font,"R",x+3,y+2,0x88EECC);}
                        });
                        updateState(state);
                    }
                    public void updateState(IButtonState state) { state.setVisible(RouteClient.available()); state.setActive(RouteClient.available()); }
                    public void getTooltips(ITooltipBuilder b) {
                        b.add(Component.literal("把这组 JEI 配方放进合成路线"));
                        b.add(Component.literal("也可以把左边的物品直接拖到路线画布"));
                    }
                    public boolean onPress(IJeiUserInput input) {
                        if(!RouteClient.available())return false;
                        if(input.isSimulate())return true;
                        importRecipe(layout);
                        return true;
                    }
                };
            }
        });
    }
    private static <T> void importRecipe(IRecipeLayoutDrawable<T> layout) {
        var mc=Minecraft.getInstance();
        try {
            var draft = readDraft(layout);
            if (mc.screen instanceof RouteScreen screen) screen.expandDraft(draft);
            else if(currentRuntime!=null && currentRuntime.getRecipesGui().getParentScreen().orElse(null) instanceof RouteScreen screen) {
                mc.setScreen(screen); screen.expandDraft(draft);
            }
            else RouteClient.open(currentRuntime.getRecipesGui().getParentScreen().orElse(mc.screen),draft.outputs().get(0).decode(mc.level.registryAccess()),draft);
        } catch(Exception e) {
            mc.player.displayClientMessage(Component.literal("[AE2 路线] "+e.getMessage()),false);
        }
    }
    static <T> RouteView.Draft readDraft(IRecipeLayoutDrawable<T> layout) {
        return readDraft(layout,null,null);
    }
    static <T> RouteView.Draft readDraft(IRecipeLayoutDrawable<T> layout,RouteView.Draft previous,Object changedSlot) {
        var mc=Minecraft.getInstance();
            java.util.function.Function<Object,RouteView.Stack> decode=ingredient -> {
                var stack=RouteJeiConverters.from((ITypedIngredient<?>)ingredient);
                return RouteView.Stack.of(stack.what(),stack.amount(),mc.level.registryAccess());
            };
            var slots=new ArrayList<RecipeDraftImport.Slot>();
            int inputIndex=0,outputIndex=0;
            for(var slot:layout.getRecipeSlotsView().getSlotViews()) {
                if(slot.getRole()!=RecipeIngredientRole.INPUT && slot.getRole()!=RecipeIngredientRole.OUTPUT)continue;
                var role=slot.getRole()==RecipeIngredientRole.INPUT?RecipeDraftImport.Role.INPUT:RecipeDraftImport.Role.OUTPUT;
                var candidates=slot.getAllIngredientsList();
                Object displayed=slot.getDisplayedIngredient().map(i->(Object)i).orElse(null);
                if(!candidates.isEmpty()) {
                    var old=previous==null?List.<RouteView.Stack>of():role==RecipeDraftImport.Role.INPUT?previous.inputs():previous.outputs();
                    int ordinal=role==RecipeDraftImport.Role.INPUT?inputIndex++:outputIndex++;
                    if(slot!=changedSlot && ordinal<old.size())displayed=RecipeDraftImport.preferred(
                            new RecipeDraftImport.Slot(role,candidates,displayed),old.get(ordinal),decode);
                }
                slots.add(new RecipeDraftImport.Slot(role,candidates,displayed));
            }
            var imported=RecipeDraftImport.read(slots,decode);
            var inputs=imported.inputs();var outputs=imported.outputs();
            var category=layout.getRecipeCategory();
            var recipeId=category.getRegistryName(layout.getRecipe());
            String id=recipeId==null?(previous!=null?previous.recipeId():category.getRecipeType().getUid()+"/"+Integer.toHexString(Objects.hash(inputs,outputs))):recipeId.toString();
            return new RouteView.Draft(id,category.getTitle().getString(),List.copyOf(inputs),List.copyOf(outputs),
                    "读取当前显示的替代材料；概率、催化剂、机器和能耗需核对。草稿不是可执行样板。");
    }
}
