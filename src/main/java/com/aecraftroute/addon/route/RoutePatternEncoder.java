package com.aecraftroute.addon.route;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.stacks.*;
import appeng.menu.AEBaseMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/** Server-only encoding. A processing pattern is a machine instruction, never an item grant. */
public final class RoutePatternEncoder {
    private RoutePatternEncoder() {}

    public static String encode(ServerPlayer player, AEBaseMenu menu, IGrid grid, AEKey target, String json) {
        var draft=RouteNetwork.GSON.fromJson(json, RouteView.Draft.class);
        RoutePatternRules.validate(draft);
        var inputs=decode(draft.inputs(),player);
        var outputs=decode(draft.outputs(),player);
        if(target==null || outputs.stream().noneMatch(s->s.what().equals(target)))
            throw new IllegalArgumentException("配方不产出选中的材料");
        ItemStack encoded;
        boolean crafting=false;
        var id=ResourceLocation.tryParse(draft.recipeId());
        var holder=id==null?null:player.serverLevel().getRecipeManager().byKey(id).orElse(null);
        if(holder!=null && holder.value() instanceof CraftingRecipe recipe) {
            if(recipe.isSpecial()) throw new IllegalArgumentException("特殊合成需使用原生样板编码终端");
            ItemStack[] slots=new ItemStack[9]; Arrays.fill(slots,ItemStack.EMPTY);
            var ingredients=recipe.getIngredients();
            int width=recipe instanceof ShapedRecipe shaped?shaped.getWidth():3;
            if(width>3 || ingredients.size()>9) throw new IllegalArgumentException("合成网格超过 3×3");
            var indices=new ArrayList<Integer>();
            for(int i=0;i<ingredients.size();i++)if(!ingredients.get(i).isEmpty())indices.add(i);
            if(indices.size()!=inputs.size())throw new IllegalArgumentException("JEI 显示槽位数量与服务端配方不同");
            int[] assignment=RoutePatternRules.assign(indices.size(),(i,j)->inputs.get(j).what() instanceof AEItemKey item
                    && ingredients.get(indices.get(i)).test(item.toStack()));
            for(int i=0;i<assignment.length;i++) {
                var chosen=inputs.get(assignment[i]); int slot=indices.get(i);
                if(chosen.amount()!=1)throw new IllegalArgumentException("合成槽位数量不是 1，请使用原生样板编码终端");
                slots[(slot/width)*3+slot%width]=((AEItemKey)chosen.what()).toStack();
            }
            var input=CraftingInput.of(3,3,Arrays.asList(slots));
            if(!recipe.matches(input,player.serverLevel()))throw new IllegalArgumentException("合成网格校验失败");
            var output=recipe.assemble(input,player.registryAccess());
            if(outputs.size()!=1 || !(outputs.getFirst().what() instanceof AEItemKey expected)
                    || !ItemStack.isSameItemSameComponents(output,expected.toStack()) || output.getCount()!=outputs.getFirst().amount())
                throw new IllegalArgumentException("JEI 显示产物与服务端配方不同");
            encoded=PatternDetailsHelper.encodeCraftingPattern(new RecipeHolder<>(holder.id(),recipe),slots,output,false,false);
            crafting=true;
        } else {
            // AE2 intentionally permits user-defined processing patterns. No recipe execution or output is fabricated here.
            encoded=PatternDetailsHelper.encodeProcessingPattern(inputs,outputs);
        }
        if(encoded.isEmpty() || PatternDetailsHelper.decodePattern(encoded,player.serverLevel())==null)
            throw new IllegalArgumentException("AE2 无法解码该样板，未消耗空白样板");
        RouteWorkbench.storeEncoded(player,menu,grid,encoded);
        return "已消耗网络内 1 张空白样板，"+(crafting?"合成":"加工")+"样板保留在缓存中";
    }
    /** Reconstruct and validate a vanilla crafting recipe on the server without consuming
     * a blank pattern or inspecting the ME network. Its encoded definition is used only
     * later, against the fresh execution snapshot. Processing/JEI-only recipes are not
     * trusted because their machine and probability semantics are not established here. */
    public static RecipePatternMapping.Recipe authoritativeCrafting(ServerPlayer player,String path,AEKey target,RouteView.Draft draft) {
        RoutePatternRules.validate(draft);
        var inputs=decode(draft.inputs(),player);var outputs=decode(draft.outputs(),player);
        var id=ResourceLocation.tryParse(draft.recipeId());
        var holder=id==null?null:player.serverLevel().getRecipeManager().byKey(id).orElse(null);
        if(holder==null || !(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial())
            throw new IllegalArgumentException("仅支持可由服务端验证的普通工作台配方");
        var ingredients=recipe.getIngredients();int width=recipe instanceof ShapedRecipe shaped?shaped.getWidth():3;
        if(width>3 || ingredients.size()>9)throw new IllegalArgumentException("合成网格超过 3×3");
        var indices=new ArrayList<Integer>();for(int i=0;i<ingredients.size();i++)if(!ingredients.get(i).isEmpty())indices.add(i);
        if(indices.size()!=inputs.size())throw new IllegalArgumentException("JEI 显示槽位数量与服务端配方不同");
        int[] assignment=RoutePatternRules.assign(indices.size(),(i,j)->inputs.get(j).what() instanceof AEItemKey item
                && ingredients.get(indices.get(i)).test(item.toStack()));
        ItemStack[] slots=new ItemStack[9];Arrays.fill(slots,ItemStack.EMPTY);
        for(int i=0;i<assignment.length;i++) {
            var chosen=inputs.get(assignment[i]);int slot=indices.get(i);
            if(chosen.amount()!=1)throw new IllegalArgumentException("合成槽位数量不是 1");
            int position=!(recipe instanceof ShapedRecipe) && indices.size()==1 ? 4 : (slot/width)*3+slot%width;
            slots[position]=((AEItemKey)chosen.what()).toStack();
        }
        var input=CraftingInput.of(3,3,Arrays.asList(slots));
        if(!recipe.matches(input,player.serverLevel()))throw new IllegalArgumentException("合成网格校验失败");
        var output=recipe.assemble(input,player.registryAccess());
        if(outputs.size()!=1 || !(outputs.getFirst().what() instanceof AEItemKey expected)
                || !expected.equals(target) || !ItemStack.isSameItemSameComponents(output,expected.toStack())
                || output.getCount()!=outputs.getFirst().amount())
            throw new IllegalArgumentException("JEI 显示产物与服务端配方不同");
        var encoded=PatternDetailsHelper.encodeCraftingPattern(new RecipeHolder<>(holder.id(),recipe),slots,output,false,false);
        var pattern=PatternDetailsHelper.decodePattern(encoded,player.serverLevel());
        if(pattern==null || pattern.getDefinition()==null)throw new IllegalArgumentException("无法构造可验证的合成样板定义");
        var variants=com.aecraftroute.addon.planner.PatternVariants.enumerate(pattern,k->0L);
        if(!variants.complete() || variants.recipes().size()!=1)throw new IllegalArgumentException("配方的输入或返还语义不能精确验证");
        var physical=variants.recipes().getFirst();var ordered=new LinkedHashMap<AEKey,Long>();
        for(var stack:inputs)ordered.merge(stack.what(),stack.amount(),Math::addExact);
        if(!ordered.equals(physical.inputs()))throw new IllegalArgumentException("验证样板与配方输入不一致");
        return new RecipePatternMapping.Recipe(path,target,ordered,physical.outputs(),pattern.getDefinition());
    }

    private static List<GenericStack> decode(List<RouteView.Stack> stacks,ServerPlayer player) {
        var result=new ArrayList<GenericStack>();
        for(var stack:stacks) {
            if(stack==null || stack.key()==null || stack.key().length()>4096 || stack.amount()<=0 || stack.amount()>Integer.MAX_VALUE)
                throw new IllegalArgumentException("样板材料或数量无效");
            AEKey key=stack.decode(player.registryAccess());
            if(key==null)throw new IllegalArgumentException("材料没有注册到 AE2，不能编码");
            result.add(new GenericStack(key,stack.amount()));
        }
        return List.copyOf(result);
    }

}
