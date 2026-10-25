package com.aecraftroute.addon.route.client;

import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.route.RouteView;
import net.minecraft.client.gui.GuiGraphics;
import java.util.function.Consumer;

/** Optional integration boundary: the canvas can load without JEI installed. */
public interface RouteRecipeBrowser {
    void select(AEKey key);
    void render(GuiGraphics graphics, int x, int y, int width, int height, int mx, int my);
    boolean click(double x, double y, int button, Consumer<RouteView.Draft> choose);
    void page(int delta);
    record Option(int index,String label) {}
    default java.util.List<Option> options() {return java.util.List.of();}
    default void selectIndex(int index) {}
    default RouteView.Draft currentDraft() {return null;}
    default void renderOption(GuiGraphics graphics,int index,int x,int y,int width,int mx,int my) {}
    default boolean optionMachineContains(int index,int x,int y,int width,double mx,double my) {return mx>=x && mx<x+18 && my>=y && my<y+18;}
    default void showOptionMachine(int index) {}
    interface IngredientDrop {void accept(RouteView.Draft draft,AEKey ingredient,double x,double y);}
    default boolean release(double x,double y,int button,IngredientDrop drop) {return false;}
    default void renderDrag(GuiGraphics graphics) {}
    default void clearMachineOverlay() {}
    default void renderMachineOverlay(GuiGraphics graphics,int mx,int my) {}
    default boolean clickMachineOverlay(double mx,double my,int button) {return false;}
    default boolean scrollMachineOverlay(double mx,double my,double delta) {return false;}
    default boolean draggingIngredient() {return false;}
    default boolean pickingRecipe() {return false;}
    default void renderMachine(String recipeId,GuiGraphics graphics,int x,int y,int mx,int my) {}
    default java.util.List<net.minecraft.network.chat.Component> machineTooltip(String recipeId) {return java.util.List.of();}
    default void showMachine(String recipeId) {}
    default void focusRecipe(String id) {}
    /** Read-only exact recipe preview; never falls back to another recipe for the same output. */
    default boolean renderPreview(String id,GuiGraphics graphics,int x,int y,int width,int height,int mx,int my) {return false;}
    default String previewRecipeId(RouteView.Choice choice,String knownId) {return knownId;}
    default void renderRecipeBadge(String id,GuiGraphics graphics,int x,int y,int mx,int my) {renderMachine(id,graphics,x,y,-1000000,-1000000);}
    default boolean scroll(double x,double y,double sx,double sy) {return false;}
    default boolean drag(double x,double y,int button,double dx,double dy) {return false;}
    default boolean key(int key,int scan) {return false;}
    default void tick() {}
    default void demand(long amount) {}
    default boolean selectEmc() {return false;}
}
