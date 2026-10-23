package com.ae2vm.addon.route;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** Opt-in local QA fixture only; refuses to operate in any ordinary world. */
@EventBusSubscriber(modid="ae2vm")
public final class RouteQaFixtures {
    private static final Map<UUID,BlockPos> anchors=new HashMap<>();
    private static ItemStack item(String id,int count) {return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)),count);}
    private static void block(ServerPlayer p,BlockPos pos,String id) {
        p.serverLevel().setBlockAndUpdate(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState());
    }
    @SubscribeEvent public static void register(RegisterCommandsEvent e) {
        if(!Boolean.getBoolean("ae2vm.qa"))return;
        e.getDispatcher().register(Commands.literal("ae2vmqa").requires(s->s.hasPermission(2)).then(Commands.literal("setup").executes(c->{
            var p=c.getSource().getPlayerOrException();check(p);var a=p.blockPosition().offset(3,0,0);anchors.put(p.getUUID(),a);
            for(int x=-2;x<9;x++)for(int z=-2;z<4;z++)block(p,a.offset(x,-1,z),"minecraft:smooth_stone");
            for(int x=0;x<7;x++)for(int y=0;y<3;y++)block(p,a.offset(x,y,0),"minecraft:air");
            block(p,a,"ae2:creative_energy_cell");block(p,a.offset(1,0,0),"ae2:chest");
            block(p,a.offset(2,0,0),"ae2:pattern_provider");block(p,a.offset(3,0,0),"ae2:1k_crafting_storage");
            block(p,a.offset(2,1,0),"ae2:molecular_assembler");
            ((MEChestBlockEntity)p.serverLevel().getBlockEntity(a.offset(1,0,0))).setCell(item("ae2:item_storage_cell_64k",1));
            p.serverLevel().setDayTime(6000);p.displayClientMessage(Component.literal("QA network placed; /ae2vmqa fill, then /ae2vmqa open"),false);return 1;
        })).then(Commands.literal("fill").executes(c->{
            var p=c.getSource().getPlayerOrException();check(p);var a=anchor(p);
            var chest=(MEChestBlockEntity)p.serverLevel().getBlockEntity(a.offset(1,0,0));
            var inv=chest.getInventory();
            for(var id:List.of("ae2:blank_pattern","minecraft:iron_ingot","minecraft:gold_ingot","minecraft:copper_ingot"))
                inv.insert(AEItemKey.of(item(id,1)),128,Actionable.MODULATE,IActionSource.empty());
            var provider=(PatternProviderBlockEntity)p.serverLevel().getBlockEntity(a.offset(2,0,0));
            var encoded=PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(item("minecraft:iron_ingot",1)),9)),List.of(new GenericStack(AEItemKey.of(item("minecraft:iron_block",1)),1)));
            provider.getLogic().getPatternInv().setItemDirect(0,encoded);
            p.displayClientMessage(Component.literal("QA network filled: blanks + ingots + one processing pattern"),false);return 1;
        })).then(Commands.literal("open").executes(c->{
            var p=c.getSource().getPlayerOrException();check(p);var a=anchor(p);
            ((MEChestBlockEntity)p.serverLevel().getBlockEntity(a.offset(1,0,0))).openCellInventoryMenu(p);return 1;
        })));
    }
    private static BlockPos anchor(ServerPlayer p) {var a=anchors.get(p.getUUID());if(a==null)throw new IllegalStateException("Run setup first");return a;}
    private static void check(ServerPlayer p) {
        if(!p.server.getWorldData().getLevelName().startsWith("AE2VM-QA"))throw new IllegalStateException("QA fixtures are restricted to AE2VM-QA worlds");
    }
}
