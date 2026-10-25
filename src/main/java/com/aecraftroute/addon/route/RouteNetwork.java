package com.aecraftroute.addon.route;

import appeng.api.stacks.AEKey;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.aecraftroute.addon.mixin.RouteMenuAccessor;
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
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("aecraftroute", "route_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b) { return new Request(b.readVarInt(), b.readVarInt(), b.readUtf(16), AEKey.readOptionalKey(b), b.readUtf(24000)); }
            public void encode(RegistryFriendlyByteBuf b, Request p) { b.writeVarInt(p.menu); b.writeVarInt(p.sequence); b.writeUtf(p.action,16); AEKey.writeOptionalKey(b,p.key); b.writeUtf(p.argument,24000); }
        };
        public Type<Request> type() { return TYPE; }
    }
    public record Response(int menu, int sequence, String json) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("aecraftroute", "route_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = new StreamCodec<>() {
            public Response decode(RegistryFriendlyByteBuf b) { return new Response(b.readVarInt(), b.readVarInt(), b.readUtf(200000)); }
            public void encode(RegistryFriendlyByteBuf b, Response p) { b.writeVarInt(p.menu); b.writeVarInt(p.sequence); b.writeUtf(p.json,200000); }
        };
        public Type<Response> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("route-3");
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
                var host = ((RouteMenuAccessor)menu).aecraftroute$routeHost();
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
                if("preview".equals(request.action()) || "apply".equals(request.action())) {
                    planAsync(context,request,player,menu,grid,policy);return;
                }
                if(RouteRequestRules.mutation(request.action())) cancelPlanning(player.getUUID());
                String message = "这些设置只对你在当前网络生效；退出登录后清空";
                var patterns = key == null ? List.<appeng.api.crafting.IPatternDetails>of()
                        : new ArrayList<>(grid.getCraftingService().getCraftingFor(key));
                boolean changed = false;
                switch (request.action()) {
                    case "query", "preview" -> {}
                    case "begin" -> {PLANNING.begin(player.getUUID());RouteSessions.begin(grid,player.getUUID(),menu.containerId);}
                    case "restore" -> { policy=RouteSessions.restore(grid,player.getUUID(),menu.containerId);changed=true;message="已还原进入画布时的合成路线"; }
                    case "clear" -> { policy = new RoutePolicy<>(Map.of()); changed = true; message = "已撤销你在当前网络的全部路线改动"; }
                    case "replan" -> {
                        if (menu instanceof CraftConfirmMenu confirm) { confirm.setAutoStart(false); confirm.replan(); message = "已重新计算；回到合成确认页查看结果"; }
                        else message = "已保存改动；回到终端重新点合成即可查看";
                    }
                    case "lock", "ban", "auto", "stock" -> {
                        if (key == null) throw new IllegalArgumentException("请先选择一个材料");
                        if (!com.aecraftroute.addon.config.CraftRouteConfig.isProxyEnabled()) throw new IllegalArgumentException("AE-Craftroute 的自动合成没有开启");
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
                var wanted=choices.stream().map(RouteView.Choice::id).collect(java.util.stream.Collectors.toSet());
                var sources=RoutePatternSources.find(grid,player.serverLevel(),wanted);
                for(int i=0;i<choices.size();i++) {
                    var choice=choices.get(i);
                    var enriched=new RouteView.Choice(choice.id(),choice.inputs(),choice.outputs(),choice.alternatives(),
                            sources.getOrDefault(choice.id(),List.of()));
                    int delta=GSON.toJson(enriched).length()-GSON.toJson(choice).length();
                    if(size+delta<=170000) {choices.set(i,enriched);size+=delta;}else truncated=true;
                }
                RoutePlanView planView = null;
                reply(context, request, new RouteView.Reply(message, choices, rule.locked(),
                        new ArrayList<>(rule.banned()), rule.stockOnly(), policy.rules().size(), truncated,
                        planView));
            } catch (Exception e) {
                reply(context, request, new RouteView.Reply("操作失败：" + e.getMessage(), List.of(), null, List.of(), false, 0, false));
            }
        });
    }
    private static final RoutePlanningRequests<UUID> PLANNING=new RoutePlanningRequests<>();
    static void cancelPlanning(UUID player) {PLANNING.cancel(player);}
    static void clearPlanning() {PLANNING.clear();}
    private static void planAsync(IPayloadContext context,Request request,ServerPlayer player,AEBaseMenu menu,
                                  appeng.api.networking.IGrid grid,RoutePolicy<AEKey> base) {
        var ticket=PLANNING.replace(player.getUUID(),request.sequence());
        if(ticket==null)return;
        long amount=1;var nodes=new ArrayList<RouteApply.Node>();
        try {
            var json=GSON.fromJson(request.argument(),com.google.gson.JsonObject.class);
            if(json!=null && json.has("amount"))amount=json.get("amount").getAsLong();
            if(amount<=0)throw new IllegalArgumentException("目标数量必须大于零");
            if(json!=null && json.has("nodes"))for(var value:json.getAsJsonArray("nodes")) {
                var e=value.getAsJsonObject();
                nodes.add(new RouteApply.Node(e.has("k")?e.get("k").getAsString():null,e.has("c")?e.get("c").getAsString():null,
                        e.has("n")?e.get("n").getAsString():null,e.has("p")?e.get("p").getAsString():null));
            }
            if(request.key()==null)throw new IllegalArgumentException("先选择目标材料");
            boolean apply="apply".equals(request.action());
            RouteRequestRules.requirePlanningEnabled(request.action(),com.aecraftroute.addon.config.CraftRouteConfig.isProxyEnabled());
            if(apply && menu instanceof CraftConfirmMenu) {
                var confirm=(com.aecraftroute.addon.mixin.CraftConfirmRouteAccessor)menu;
                if(!Objects.equals(request.key(),confirm.aecraftroute$routeWhatToCraft()) || amount!=confirm.aecraftroute$routeAmount())
                    throw new IllegalArgumentException("画布目标或数量与当前合成确认页不同；请从对应的确认页重新打开");
            }
            var prepared=apply?RouteApply.prepare(grid,base,player.registryAccess(),request.key(),amount,nodes)
                    :new RouteApply.Prepared(base,List.of(),List.of());
            if(!prepared.problems().isEmpty())throw new IllegalArgumentException(String.join("；",prepared.problems()));
            long want=amount;
            java.util.function.BooleanSupplier valid=()->{
                if(!PLANNING.current(player.getUUID(),ticket) || player.containerMenu!=menu || !menu.isValidMenu() || !menu.stillValid(player)
                        || apply && !com.aecraftroute.addon.config.CraftRouteConfig.isProxyEnabled()
                        || !RouteSessions.get(grid,player.getUUID()).equals(base)
                        || player.getServer()==null || player.getServer().getPlayerList().getPlayer(player.getUUID())!=player)return false;
                var host=((RouteMenuAccessor)menu).aecraftroute$routeHost();var node=host==null?null:host.getActionableNode();
                return node!=null && node.isActive() && node.getGrid()==grid;
            };
            var future=RoutePlanningService.planAsync(grid,prepared.policy(),player.registryAccess(),request.key(),want,
                    new com.aecraftroute.addon.planner.ProductionPlanner.Limits(apply?12000:6000,apply?3_000_000_000L:2_000_000_000L),valid);
            PLANNING.bind(player.getUUID(),ticket,future);
            future.whenComplete((plan,error)->{
                if(!valid.getAsBoolean()) {if(PLANNING.current(player.getUUID(),ticket))PLANNING.forget(player.getUUID(),ticket);return;}
                try {
                    RoutePlanView view;
                    if(error==null)view=RoutePlanView.of(plan,want,(k,a)->RouteView.Stack.of(k,a,player.registryAccess()));
                    else {
                        Throwable cause=error;while(cause instanceof java.util.concurrent.CompletionException && cause.getCause()!=null)cause=cause.getCause();
                        if(cause instanceof java.util.concurrent.CancellationException)return;
                        view=cause instanceof com.aecraftroute.addon.planner.ProductionPlanningException failure?RoutePlanView.of(failure,want)
                                :RoutePlanView.error("规划失败："+cause.getMessage(),want);
                    }
                    String message=view.message();var effective=base;
                    if(apply && view.proven()) {
                        // Atomic: no policy writes before proof and final snapshot validation.
                        RouteSessions.put(grid,player.getUUID(),prepared.policy());effective=prepared.policy();
                        if(menu instanceof CraftConfirmMenu confirm){confirm.setAutoStart(false);confirm.replan();}
                        message="已应用强制画布程序："+prepared.locked().size()+" 个路径分支（各分支至少执行画布批次，可继续循环增产） · "+message;
                    } else if(apply)message="未应用任何路线改动 · "+message;
                    var rule=effective.rule(request.key());
                    reply(context,request,new RouteView.Reply(message,List.of(),rule.locked(),new ArrayList<>(rule.banned()),rule.stockOnly(),effective.rules().size(),false,view));
                } finally {if(PLANNING.current(player.getUUID(),ticket))PLANNING.forget(player.getUUID(),ticket);}
            });
        } catch(Exception failure) {
            PLANNING.forget(player.getUUID(),ticket);
            reply(context,request,new RouteView.Reply("操作失败："+failure.getMessage(),List.of(),null,List.of(),false,base.rules().size(),false));
        }
    }
    private static void reply(IPayloadContext context, Request req, RouteView.Reply reply) {
        if(context.player() instanceof ServerPlayer player && player.containerMenu instanceof AEBaseMenu menu
                && menu.containerId==req.menu()) {
            var host=((RouteMenuAccessor)menu).aecraftroute$routeHost();var node=host==null?null:host.getActionableNode();
            if(node!=null && node.isActive()) reply=reply.withCanvas(RouteView.CanvasChoices.of(
                    RouteSessions.get(node.getGrid(),player.getUUID()).canvas(),player.registryAccess()));
            if("begin".equals(req.action()) && menu instanceof CraftConfirmMenu && menu.isValidMenu() && menu.stillValid(player)) {
                var confirm=(com.aecraftroute.addon.mixin.CraftConfirmRouteAccessor)menu;
                var key=confirm.aecraftroute$routeWhatToCraft();long amount=confirm.aecraftroute$routeAmount();
                if(key!=null && amount>0) reply=reply.withRequestTarget(new RouteView.RequestTarget(key.toTagGeneric(player.registryAccess()).toString(),amount));
            }
        }
        context.reply(new Response(req.menu(),req.sequence(),GSON.toJson(reply)));
    }
}
