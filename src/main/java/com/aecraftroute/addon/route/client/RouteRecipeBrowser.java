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
    default boolean draggingIngredient() {return false;}
    default void renderMachine(String recipeId,GuiGraphics graphics,int x,int y,int mx,int my) {}
    default void showMachine(String recipeId) {}
    default void focusRecipe(String id) {}
    default boolean scroll(double x,double y,double sx,double sy) {return false;}
    default boolean drag(double x,double y,int button,double dx,double dy) {return false;}
    default boolean key(int key,int scan) {return false;}
    default void tick() {}
    default void demand(long amount) {}
    default boolean selectEmc() {return false;}
}
