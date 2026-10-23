package com.ae2vm.addon.route;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Checks the actual installed jars, not just the older build dependency. Not an in-game UI test. */
class InstalledRouteIntegrationContractTest {
    @Test void installedProjectEProvidesOptionalEmcAndKnowledgeApis() throws Exception {
        assertTrue(read("ProjectE-1.21.1-PE1.1.0", "moze_intel.projecte.api.proxy.IEMCProxy").methods.contains(
                "getValue(Lnet/minecraft/world/item/ItemStack;)J"));
        assertTrue(read("ProjectE-1.21.1-PE1.1.0", "moze_intel.projecte.api.proxy.ITransmutationProxy").methods.contains(
                "getKnowledgeProviderFor(Ljava/util/UUID;)Lmoze_intel/projecte/api/capabilities/IKnowledgeProvider;"));
        assertTrue(read("ProjectE-1.21.1-PE1.1.0", "moze_intel.projecte.api.capabilities.IKnowledgeProvider").methods.containsAll(
                Set.of("hasKnowledge(Lnet/minecraft/world/item/ItemStack;)Z","getEmc()Ljava/math/BigInteger;")));
    }
    @Test void installedSuperMatrixHasTerminalVisibilityAndInventoryApi() throws Exception {
        var matrix=read("extendedae_plus-1.6.3","com.extendedae_plus.content.matrix.supermatrix.SuperAssemblerMatrixBlockEntity");
        assertTrue(matrix.methods.contains("isVisibleInTerminal()Z"));
        assertTrue(matrix.methods.contains("getTerminalPatternInventory()Lappeng/api/inventories/InternalInventory;"));
    }
    @Test void installedTreeJobIncludesSynchronizedFinalOutput() throws Exception {
        assertTrue(read("ae2ct-1.21.1-1.1.1","com.neuvillette.ae2ct.api.RecipeHelper").fields.contains("output:Lappeng/api/stacks/GenericStack;"));
    }
    record Contract(Set<String> methods,Set<String> fields){}
    Contract read(String filename, String clazz) throws Exception {
        Path mods=Path.of("../../../mods");
        assumeTrue(Files.isDirectory(mods),"Installed-pack contract test only");
        Path file;
        try(var files=Files.list(mods)){file=files.filter(p->p.getFileName().toString().contains(filename)).findFirst().orElse(null);}
        assumeTrue(file!=null,"Optional mod not installed");
        try(var zip=new ZipFile(file.toFile())){
            var entry=zip.getEntry(clazz.replace('.','/')+".class");assertNotNull(entry,clazz);
            var methods=new HashSet<String>();var fields=new HashSet<String>();
            new ClassReader(zip.getInputStream(entry)).accept(new ClassVisitor(Opcodes.ASM9){
                public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){methods.add(n+d);return null;}
                public FieldVisitor visitField(int a,String n,String d,String s,Object v){fields.add(n+":"+d);return null;}
            },ClassReader.SKIP_CODE);
            return new Contract(methods,fields);
        }
    }
    @Test void installedAe2HasMenuAndReplanHooks() throws Exception {
        assertTrue(read("appliedenergistics2-19.2.18", "appeng.menu.AEBaseMenu").methods.contains("getActionHost()Lappeng/api/networking/security/IActionHost;"));
        var confirm=read("appliedenergistics2-19.2.18", "appeng.menu.me.crafting.CraftConfirmMenu");
        assertTrue(confirm.methods.containsAll(Set.of("replan()V","setAutoStart(Z)V")));
        assertTrue(confirm.fields.containsAll(Set.of("whatToCraft:Lappeng/api/stacks/AEKey;","amount:I")));
        assertTrue(read("appliedenergistics2-19.2.18", "appeng.client.gui.AEBaseScreen").methods.contains(
                "addToLeftToolbar(Lnet/minecraft/client/gui/components/Button;)Lnet/minecraft/client/gui/components/Button;"));
    }
    @Test void installedTreeHasExactOptionalMixinTargets() throws Exception {
        var tree=read("ae2ct-1.21.1-1.1.1", "com.neuvillette.ae2ct.gui.CraftingTreeWidget");
        assertTrue(tree.fields.contains("_nodeManager:Lcom/neuvillette/ae2ct/api/CraftingTreeHelper$NodeManager;"));
        assertTrue(tree.methods.containsAll(Set.of("getMousePoint(DD)Ljava/awt/Point;","isMouseOutScreen(DD)Z","mouseClicked(DDI)Z","draw(Lnet/minecraft/client/gui/GuiGraphics;IIII)V")));
    }
    @Test void installedJeiSupportsPerRecipeImportButton() throws Exception {
        assertTrue(read("jei-1.21.1-neoforge-19.57", "mezz.jei.api.registration.IAdvancedRegistration").methods.contains(
                "addRecipeButtonFactory(Lmezz/jei/api/recipe/advanced/IRecipeButtonControllerFactory;)V"));
        assertTrue(read("jei-1.21.1-neoforge-19.57", "mezz.jei.api.gui.inputs.IJeiUserInput").methods.contains("isSimulate()Z"));
    }
    @Test void installedJeiHasNativeScreenAndLayoutInterfaces() throws Exception {
        var handlers=read("jei-1.21.1-neoforge-19.57", "mezz.jei.api.registration.IGuiHandlerRegistration");
        assertTrue(handlers.methods.contains("addGuiScreenHandler(Ljava/lang/Class;Lmezz/jei/api/gui/handlers/IScreenHandler;)V"));
        var layout=read("jei-1.21.1-neoforge-19.57", "mezz.jei.api.gui.IRecipeLayoutDrawable");
        assertTrue(layout.methods.contains("drawRecipe(Lnet/minecraft/client/gui/GuiGraphics;II)V"));
        assertTrue(layout.methods.contains("drawOverlays(Lnet/minecraft/client/gui/GuiGraphics;II)V"));
    }
    @Test void installedExtendedAeHasReusableProviderMenu() throws Exception {
        var terminal=read("ExtendedAE-1.21-2.2.41", "com.glodblock.github.extendedae.container.ContainerExPatternTerminal");
        assertTrue(terminal.fields.contains("TYPE:Lnet/minecraft/world/inventory/MenuType;"));
        assertTrue(terminal.methods.contains("quickMovePattern(Lnet/minecraft/server/level/ServerPlayer;ILjava/util/List;)V"));
    }
    @Test void installedTreeSnapshotHasRecipeInputsAndOutputs() throws Exception {
        assertTrue(read("ae2ct-1.21.1-1.1.1","com.neuvillette.ae2ct.api.ICraftingPlanSummary").methods.contains(
                "getJob()Lcom/neuvillette/ae2ct/api/RecipeHelper;"));
        assertTrue(read("ae2ct-1.21.1-1.1.1","com.neuvillette.ae2ct.api.RecipeHelper").fields.contains("recipes:Ljava/util/List;"));
        assertTrue(read("ae2ct-1.21.1-1.1.1","com.neuvillette.ae2ct.api.RecipeHelper$Recipe").methods.containsAll(
                Set.of("inputs()Ljava/util/List;","outputs()Ljava/util/List;")));
    }
    @Test void nativeJeiRecipeWindowCanHandleInputsAndTooltips() throws Exception {
        var handler=read("jei-1.21.1-neoforge-19.57","mezz.jei.api.gui.inputs.IJeiInputHandler");
        assertTrue(handler.methods.contains("handleInput(DDLmezz/jei/api/gui/inputs/IJeiUserInput;)Z"));
        assertTrue(handler.methods.contains("handleMouseScrolled(DDDD)Z"));
        assertTrue(read("jei-1.21.1-neoforge-19.57","mezz.jei.api.gui.ingredient.IRecipeSlotDrawable").methods.contains(
                "drawTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V"));
    }
    @Test void nativeProviderInventorySupportsSimulatedAndActualInsert() throws Exception {
        assertTrue(read("appliedenergistics2-19.2.18","appeng.helpers.patternprovider.PatternContainer").methods.contains(
                "getTerminalPatternInventory()Lappeng/api/inventories/InternalInventory;"));
        assertTrue(read("appliedenergistics2-19.2.18","appeng.api.inventories.InternalInventory").methods.contains(
                "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"));
    }
    @Test void canonicalNbtIgnoresCompoundInsertionOrder() {
        var a=new net.minecraft.nbt.CompoundTag();a.putString("x","a");a.putInt("y",2);
        var b=new net.minecraft.nbt.CompoundTag();b.putInt("y",2);b.putString("x","a");
        assertEquals(RouteIdentity.canonical(a),RouteIdentity.canonical(b));
        b.putInt("y",3);assertNotEquals(RouteIdentity.canonical(a),RouteIdentity.canonical(b));
    }
    @Test void clientAndNetworkClassesLinkWithoutStartingMinecraft() throws Exception {
        for (String name : List.of("client.RouteScreen", "client.RouteScreen$Material", "client.RouteScreen$MNode",
                "client.RouteScreen$RNode", "client.RouteScreen$ChoiceRow", "client.RouteScreen$ChoiceHit",
                "client.RouteScreen$ActionHit", "client.RouteClient", "client.RouteJeiPlugin",
                "client.RouteJeiRecipes", "client.RouteEmcAccess", "client.RouteEmcAccess$State", "RouteEmcRules", "client.RouteTreeSnapshot", "client.RouteRecipeBrowser", "RoutePatternEncoder", "RoutePatternRules", "RouteWorkbench", "RouteWorkbench$State", "client.RouteWorkbenchPanel",
                "RouteNetwork$Request", "RouteNetwork$Response")) {
            var type=Class.forName("com.ae2vm.addon.route."+name,false,getClass().getClassLoader());
            assertNotNull(type.getDeclaredMethods());
            assertNotNull(type.getDeclaredConstructors());
        }
    }
}
