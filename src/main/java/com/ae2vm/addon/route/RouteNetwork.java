package com.ae2vm.addon.route;

import appeng.api.stacks.AEKey;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.ae2vm.addon.mixin.RouteMenuAccessor;
import com.google.gson.Gson;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;
import java.util.function.Consumer;

public final class RouteNetwork {
    public static final Gson GSON = new Gson();
    // Installed only by the client event subscriber, avoiding server class-loading of GUI/JEI.
    public static Consumer<Response> clientHandler = response -> {};
    public record Request(int menu, int sequence, String action, AEKey key, String argument) implements CustomPacketPayload {
        public Request {
            if(action==null || argument==null || argument.length()>(action.equals("encode")||action.equals("apply")?24000:Set.of("workbench","takecache","upload").contains(action)?1024:128))
                throw new IllegalArgumentException("路线操作参数超过限制");
        }
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("ae2vm", "route_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b) { return new Request(b.readVarInt(), b.readVarInt(), b.readUtf(16), AEKey.readOptionalKey(b), b.readUtf(24000)); }
            public void encode(RegistryFriendlyByteBuf b, Request p) { b.writeVarInt(p.menu); b.writeVarInt(p.sequence); b.writeUtf(p.action,16); AEKey.writeOptionalKey(b,p.key); b.writeUtf(p.argument,24000); }
        };
        public Type<Request> type() { return TYPE; }
    }
    public record Response(int menu, int sequence, String json) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("ae2vm", "route_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = new StreamCodec<>() {
            public Response decode(RegistryFriendlyByteBuf b) { return new Response(b.readVarInt(), b.readVarInt(), b.readUtf(200000)); }
            public void encode(RegistryFriendlyByteBuf b, Response p) { b.writeVarInt(p.menu); b.writeVarInt(p.sequence); b.writeUtf(p.json,200000); }
        };
        public Type<Response> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("route-2");
        r.playToServer(Request.TYPE, Request.CODEC, RouteNetwork::handle);
        r.playToClient(Response.TYPE, Response.CODEC, (p,c) -> c.enqueueWork(() -> clientHandler.accept(p)));
    }
    private static void handle(Request request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.containerMenu instanceof AEBaseMenu menu) || menu.containerId != request.menu()
                    || !menu.stillValid(player) || !menu.isValidMenu()) {
                reply(context,request,new RouteView.Reply("操作失败：终端会话已失效，请重新打开终端",List.of(),null,List.of(),false,0,false));
                return;
            }
            try {
                var host = ((RouteMenuAccessor)menu).ae2vm$routeHost();
                var node = host == null ? null : host.getActionableNode();
                if (node == null || !node.isActive()) throw new IllegalArgumentException("终端没有连接可用的 ME 网络");
                var grid = node.getGrid();
                if(Set.of("workbench","encode","takecache","upload").contains(request.action())) {
                    var view=RouteWorkbench.ViewRequest.first();String message="";
                    switch(request.action()) {
                        case "workbench" -> view=request.argument().isBlank()?view:GSON.fromJson(request.argument(),RouteWorkbench.ViewRequest.class);
                        case "encode" -> message=RoutePatternEncoder.encode(player,menu,grid,request.key(),request.argument());
                        case "takecache" -> {
                            view=request.argument().isBlank()?view:GSON.fromJson(request.argument(),RouteWorkbench.ViewRequest.class);
                            message=RouteWorkbench.takeToBackpack(player,menu);
                        }
                        case "upload" -> {
                            var upload=GSON.fromJson(request.argument(),RouteWorkbench.Upload.class);
                            message=RouteWorkbench.upload(player,menu,grid,upload);view=upload.view();
                        }
                    }
                    context.reply(new Response(request.menu(),request.sequence(),GSON.toJson(RouteWorkbench.view(player,menu,grid,view,message))));
                    return;
                }
                var policy = RouteSessions.get(grid, player.getUUID());
                var key = request.key();
                String message = "这些设置只对你在当前网络生效；退出登录后清空";
                var patterns = key == null ? List.<appeng.api.crafting.IPatternDetails>of()
                        : new ArrayList<>(grid.getCraftingService().getCraftingFor(key));
                if ("apply".equals(request.action())) {
                    // Whole-canvas apply: the picture becomes constraints the server can prove.
                    // Nothing is applied silently - conflicts and stale picks come back per node.
                    long want = 1;
                    var nodes = new ArrayList<RouteApply.Node>();
                    try {
                        var json = GSON.fromJson(request.argument(), com.google.gson.JsonObject.class);
                        if (json != null && json.has("amount")) want = json.get("amount").getAsLong();
                        if (json != null && json.has("nodes")) {
                            for (var element : json.getAsJsonArray("nodes")) {
                                var entry = element.getAsJsonObject();
                                nodes.add(new RouteApply.Node(
                                        entry.has("k") ? entry.get("k").getAsString() : null,
                                        entry.has("c") ? entry.get("c").getAsString() : null,
                                        entry.has("n") ? entry.get("n").getAsString() : null));
                            }
                        }
                    } catch (Exception broken) {
                        reply(context, request, new RouteView.Reply(
                                "操作失败：画布数据无法读取（" + broken.getMessage() + "）",
                                List.of(), null, List.of(), false, 0, false));
                        return;
                    }
                    var outcome = RouteApply.apply(grid, policy, player.registryAccess(), key,
                            Math.max(1, want), nodes,
                            new com.ae2vm.addon.planner.ProductionPlanner.Limits(12000, 500_000_000L));
                    RouteSessions.put(grid, player.getUUID(), outcome.policy());
                    String applied = "已按画布应用 " + outcome.locked().size() + " 个材料"
                            + (outcome.problems().isEmpty() ? "" : "；" + String.join("；", outcome.problems()));
                    var appliedRule = outcome.policy().rule(key);
                    reply(context, request, new RouteView.Reply(applied + " · " + outcome.plan().message(),
                            List.of(), appliedRule.locked(), new ArrayList<>(appliedRule.banned()),
                            appliedRule.stockOnly(), outcome.policy().rules().size(), false, outcome.plan()));
                    return;
                }
                boolean changed = false;
                switch (request.action()) {
                    case "query", "preview" -> {}
                    case "begin" -> RouteSessions.begin(grid,player.getUUID(),menu.containerId);
                    case "restore" -> { policy=RouteSessions.restore(grid,player.getUUID(),menu.containerId);changed=true;message="已还原进入画布时的合成路线"; }
                    case "clear" -> { policy = new RoutePolicy<>(Map.of()); changed = true; message = "已撤销你在当前网络的全部路线改动"; }
                    case "replan" -> {
                        if (menu instanceof CraftConfirmMenu confirm) { confirm.setAutoStart(false); confirm.replan(); message = "已重新计算；回到合成确认页查看结果"; }
                        else message = "已保存改动；回到终端重新点合成即可查看";
                    }
                    case "lock", "ban", "auto", "stock" -> {
                        if (key == null) throw new IllegalArgumentException("请先选择一个材料");
                        if (!com.ae2vm.addon.config.AE2VMConfig.isProxyEnabled()) throw new IllegalArgumentException("AE2-VM 的自动合成没有开启");
                        if ((request.action().equals("lock") || request.action().equals("ban")) && patterns.stream().noneMatch(p ->
                                RouteIdentity.pattern(p, player.registryAccess()).equals(request.argument())))
                            throw new IllegalArgumentException("这张样板已经变了，请重新打开页面再选");
                        policy = policy.change(key, request.action(), request.argument()); changed = true;
                        message = "已改用你选的配方；系统不会再换别的路线";
                    }
                    default -> throw new IllegalArgumentException("未知路线操作");
                }
                RouteSessions.put(grid, player.getUUID(), policy);
                // Cancel/replace old confirmation immediately, not just when the UI's refresh button is pressed.
                if (changed && menu instanceof CraftConfirmMenu confirm) { confirm.setAutoStart(false); confirm.replan(); }
                var rule = policy.rule(key);
                var ordered = new ArrayList<>(patterns);
                if (rule.locked() != null) {
                    for (int i = 0; i < ordered.size(); i++) {
                        if (RouteIdentity.pattern(ordered.get(i), player.registryAccess()).equals(rule.locked())) {
                            ordered.add(0, ordered.remove(i));
                            break;
                        }
                    }
                }
                var choices = new ArrayList<RouteView.Choice>();
                int size = 0;
                boolean truncated = false;
                for (var p : ordered) {
                    if (choices.size() >= 64) { truncated = true; break; }
                    var inputs = new ArrayList<RouteView.Stack>();
                    boolean alternatives = false;
                    for (var in : p.getInputs()) {
                        var possible = in.getPossibleInputs();
                        if (possible.length == 0) continue;
                        alternatives |= possible.length > 1;
                        inputs.add(RouteView.Stack.of(possible[0].what(), Math.multiplyExact(possible[0].amount(), in.getMultiplier()), player.registryAccess()));
                    }
                    var outputs = p.getOutputs().stream().map(s -> RouteView.Stack.of(s.what(),s.amount(),player.registryAccess())).toList();
                    var choice = new RouteView.Choice(RouteIdentity.pattern(p, player.registryAccess()), inputs, outputs, alternatives);
                    size += GSON.toJson(choice).length();
                    if (size > 170000) { truncated = true; break; }
                    choices.add(choice);
                }
                RoutePlanView planView = null;
                if ("preview".equals(request.action())) {
                    // The canvas asks the server what the request really costs. A short
                    // budget keeps the tick bounded; overrun reports LIMIT, never "missing".
                    long want = 1;
                    try {
                        var json = GSON.fromJson(request.argument(), com.google.gson.JsonObject.class);
                        if (json != null && json.has("amount")) want = json.get("amount").getAsLong();
                    } catch (Exception ignored) {
                        // Malformed argument means "one"; the verdict still comes from the server.
                    }
                    if (want <= 0) want = 1;
                    if (key == null) {
                        planView = RoutePlanView.error("先选择目标材料", want);
                        message = planView.message();
                    } else {
                        try {
                            var plan = com.ae2vm.addon.api.AE2VMCrafting.planNow(grid, policy,
                                    player.registryAccess(), key, want,
                                    new com.ae2vm.addon.planner.ProductionPlanner.Limits(6000, 250_000_000L));
                            planView = RoutePlanView.of(plan, want,
                                    (k, a) -> RouteView.Stack.of(k, a, player.registryAccess()));
                        } catch (com.ae2vm.addon.planner.ProductionPlanningException planning) {
                            planView = RoutePlanView.of(planning, want);
                        } catch (Exception other) {
                            planView = RoutePlanView.error("预览失败：" + other.getMessage(), want);
                        }
                        message = planView.message();
                    }
                }
                reply(context, request, new RouteView.Reply(message, choices, rule.locked(),
                        new ArrayList<>(rule.banned()), rule.stockOnly(), policy.rules().size(), truncated,
                        planView));
            } catch (Exception e) {
                reply(context, request, new RouteView.Reply("操作失败：" + e.getMessage(), List.of(), null, List.of(), false, 0, false));
            }
        });
    }
    private static void reply(IPayloadContext context, Request req, RouteView.Reply reply) {
        context.reply(new Response(req.menu(),req.sequence(),GSON.toJson(reply)));
    }
}
