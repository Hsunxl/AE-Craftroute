package com.ae2vm.addon.route.client;

import appeng.api.stacks.*;
import appeng.client.gui.AEBaseScreen;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.ae2vm.addon.mixin.CraftConfirmRouteAccessor;
import com.ae2vm.addon.route.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = "ae2vm", value = Dist.CLIENT)
public final class RouteClient {
    public static java.util.function.Supplier<RouteRecipeBrowser> recipeBrowser;
    private static final java.util.Map<Screen, Button> toolbarButtons = new java.util.WeakHashMap<>();
    public static AEKey selectedTreeKey;
    public static long selectedTreeAmount;
    public static String selectedTreePath = "";
    public static java.util.function.Consumer<AEKey> showJei;

    public static boolean available() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.player.containerMenu instanceof AEBaseMenu;
    }

    public static void open(Screen parent, AEKey key, RouteView.Draft draft) {
        long amount = 0;
        String source = draft == null ? "手动" : "JEI · " + draft.category();
        String path = "";
        var mc = Minecraft.getInstance();
        if (draft != null && !draft.outputs().isEmpty()) {
            amount = draft.outputs().get(0).amount();
        }
        if (mc.player != null && mc.player.containerMenu instanceof CraftConfirmMenu confirm) {
            AEKey planned = ((CraftConfirmRouteAccessor) confirm).ae2vm$routeWhatToCraft();
            int plannedAmount = ((CraftConfirmRouteAccessor) confirm).ae2vm$routeAmount();
            if (key == null) key = planned;
            if (draft == null && planned != null && planned.equals(key)) {
                amount = plannedAmount;
                source = "合成确认";
            }
        }
        open(parent, key, draft, amount, source, path);
    }

    public static void open(Screen parent, AEKey key, RouteView.Draft draft,
                            long amount, String source, String path) {
        if (!available()) return;
        var mc=Minecraft.getInstance();
        if(draft==null && mc.player.containerMenu instanceof CraftConfirmMenu confirm) {
            if(confirm.getPlan()==null) {
                mc.player.displayClientMessage(Component.literal("合成计划尚未计算完成，请稍候再打开画布"),true);return;
            }
            // Client whatToCraft/amount are not the synchronized plan. AE2CT carries its final output.
            var output=RouteTreeSnapshot.output(confirm);
            if(output!=null) {key=output.what();amount=output.amount();}
            else if(key==null) {
                key=((CraftConfirmRouteAccessor)confirm).ae2vm$routeWhatToCraft();
                amount=((CraftConfirmRouteAccessor)confirm).ae2vm$routeAmount();
            }
            source="合成确认";
        }
        var canvas=new RouteScreen(parent,key,draft,amount,source,path);
        var reference=new java.lang.ref.WeakReference<>(canvas);
        RouteNetwork.clientHandler = response -> {
            var screen=reference.get();
            if(screen!=null)screen.accept(response);
        };
        Minecraft.getInstance().setScreen(canvas);
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Pre event) {
        if (event.getScreen() instanceof RouteScreen || !(event.getScreen() instanceof AEBaseScreen<?> screen)) return;
        if (!(screen.getMenu() instanceof appeng.menu.me.common.MEStorageMenu)
                && !(screen.getMenu() instanceof CraftConfirmMenu)) return;
        if (!(screen instanceof com.ae2vm.addon.mixin.RouteToolbarAccessor accessor)) return;
        if (toolbarButtons.containsKey(screen)) return;
        var button = new appeng.client.gui.widgets.IconButton(b -> {
            var current = Minecraft.getInstance().screen;
            open(current, null, null);
        }) {
            protected appeng.client.gui.Icon getIcon() { return appeng.client.gui.Icon.TAB_CRAFTING; }
            @Override public java.util.List<Component> getTooltipMessage() {
                return java.util.List.of(Component.literal("合成树画布（F8）"), Component.literal("从终端新建，或从合成确认页修改"));
            }
        };
        accessor.ae2vm$addRouteButton(button);
        toolbarButtons.put(screen, button);
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void separateTerminalButtons(ScreenEvent.Init.Post event) {
        if (!event.getScreen().getClass().getName().equals("com.lhy.wcwt.client.WirelessComprehensiveWorkTerminalScreen")) return;
        for (var child : event.getScreen().children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractButton button) {
                String text = button.getMessage().getString();
                if (text.contains("深色") || text.contains("暗色") || text.equalsIgnoreCase("Dark Mode")) {
                    button.setY(button.getY() - 22);
                }
            }
        }
    }

    @SubscribeEvent
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof RouteScreen || event.getKeyCode() != GLFW.GLFW_KEY_F8 || !(event.getScreen() instanceof AEBaseScreen<?> screen)) return;
        AEKey key = null;
        long amount = 0;
        String source = "手动";
        String path = "";
        if (screen.getClass().getName().equals("com.neuvillette.ae2ct.gui.CraftingTreeScreen")) {
            key = selectedTreeKey;
            amount = selectedTreeAmount;
            source = "合成树";
            path = selectedTreePath;
        }
        if (key == null && screen.getSlotUnderMouse() != null) {
            key = AEItemKey.of(screen.getSlotUnderMouse().getItem());
            amount = 0;
            source = "终端槽位";
        }
        open(screen, key, null, amount, source, path);
        event.setCanceled(true);
    }
}
