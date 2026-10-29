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
    /** IdentityHashMap keys must remain object-identity based; its values need String equality. */
    static boolean hasPatternIdentity(Map<appeng.api.crafting.IPatternDetails,String> identities,String requested) {
        return requested!=null && identities.values().stream().anyMatch(requested::equals);
    }
    public record Request(int menu, int sequence, String action, AEKey key, String argument) implements CustomPacketPayload {
        public Request {
            if(action==null || argument==null || argument.length()>(action.equals("encode")||action.equals("apply")||action.equals("apply_chunk")?RouteCanvasPayload.MAX_CHARACTERS:Set.of("workbench","takecache","upload","discardcache").contains(action)?1024:128))
                throw new IllegalArgumentException("路线操作参数超过限制");
        }
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("aecraftroute", "route_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b) { return new Request(b.readVarInt(), b.readVarInt(), b.readUtf(16), AEKey.readOptionalKey(b), b.readUtf(RouteCanvasPayload.MAX_CHARACTERS)); }
            public void encode(RegistryFriendlyByteBuf b, Request p) { b.writeVarInt(p.menu); b.writeVarInt(p.sequence); b.writeUtf(p.action,16); AEKey.writeOptionalKey(b,p.key); b.writeUtf(p.argument,RouteCanvasPayload.MAX_CHARACTERS); }
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
                if(Set.of("workbench","encode","takecache","upload","discardcache").contains(request.action())) {
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
                        case "discardcache" -> {
                            var discard=GSON.fromJson(request.argument(),RouteWorkbench.Discard.class);
                            message=RouteWorkbench.discardCorrupt(player,discard);view=discard.view();
                        }
                    }
                    context.reply(new Response(request.menu(),request.sequence(),GSON.toJson(RouteWorkbench.view(player,menu,grid,view,message))));
                    return;
                }
                if("apply_chunk".equals(request.action())) {
                    var data=request.argument();int a=data.indexOf('|'),b=a<0?-1:data.indexOf('|',a+1);
                    if(a<1 || b<a+2)throw new IllegalArgumentException("画布分片头无效");
                    int index=Integer.parseInt(data.substring(0,a)),count=Integer.parseInt(data.substring(a+1,b));
                    if(index==0)PLANNING.cancel(player.getUUID());
                    var complete=CHUNKS.accept(player.getUUID(),menu.containerId,request.sequence(),request.key(),index,count,data.substring(b+1),System.nanoTime());
                    if(complete!=null) {
                        var apply=new Request(request.menu(),request.sequence(),"apply",request.key(),"");
                        planAsync(context,apply,player,menu,grid,RouteSessions.get(grid,player.getUUID()),complete);
                    }
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
                var identities=new IdentityHashMap<appeng.api.crafting.IPatternDetails,String>();
                int invalidIdentities=0;
                for(var pattern:patterns) try {identities.put(pattern,RouteIdentity.pattern(pattern,player.registryAccess()));}
                    catch(RuntimeException invalid) { invalidIdentities++; /* Do not hide healthy patterns. */ }
                boolean changed = false;
                switch (request.action()) {
                    case "query", "preview" -> {}
                    case "begin" -> {PLANNING.begin(player.getUUID());RouteSessions.begin(grid,player.getUUID(),menu.containerId);}
                    case "restore" -> { policy=RouteSessions.restore(grid,player.getUUID(),menu.containerId);changed=true;message="已还原进入画布时的合成路线"; }
                    case "clear" -> { policy = new RoutePolicy<>(Map.of()); changed = true; message = "已撤销你在当前网络的全部路线改动"; }
                    case "clearcanvas" -> {
                        changed=policy.canvas()!=null;
                        policy=policy.withoutCanvas();
                        message=changed?"已撤销强制画布，保留逐材料路线设置":"当前没有已应用的强制画布；逐材料路线设置未变";
                    }
                    case "replan" -> {
                        if (menu instanceof CraftConfirmMenu confirm) { confirm.setAutoStart(false); confirm.replan(); message = "已重新计算；回到合成确认页查看结果"; }
                        else message = "已保存改动；回到终端重新点合成即可查看";
                    }
                    case "lock", "ban", "auto", "stock" -> {
                        if (key == null) throw new IllegalArgumentException("请先选择一个材料");
                        if (!com.aecraftroute.addon.config.CraftRouteConfig.isProxyEnabled()) throw new IllegalArgumentException("AE-Craftroute 的自动合成没有开启");
                        if ((request.action().equals("lock") || request.action().equals("ban"))
                                && !hasPatternIdentity(identities,request.argument()))
                            throw new IllegalArgumentException("这张样板已经变了，请重新打开页面再选");
                        policy = policy.change(key, request.action(), request.argument()); changed = true;
                        message = "已改用你选的配方；系统不会再换别的路线";
                    }
                    default -> throw new IllegalArgumentException("未知路线操作");
                }
                if(changed)RouteSessions.put(grid, player.getUUID(), policy);
                // Cancel/replace old confirmation immediately, not just when the UI's refresh button is pressed.
                if (changed && menu instanceof CraftConfirmMenu confirm) { confirm.setAutoStart(false); confirm.replan(); }
                var rule = policy.rule(key);
                var ordered = new ArrayList<>(patterns.stream().filter(identities::containsKey).toList());
                if (rule.locked() != null) {
                    for (int i = 0; i < ordered.size(); i++) {
                        if (identities.get(ordered.get(i)).equals(rule.locked())) {
                            ordered.add(0, ordered.remove(i));
                            break;
                        }
                    }
                }
                var choices = new ArrayList<RouteView.Choice>();
                int size = 0;
                boolean truncated = false;
                int invalidChoices = invalidIdentities;
                for (var p : ordered) {
                    if (choices.size() >= 64) { truncated = true; break; }
                    RouteView.Choice choice;
                    try {
                        choice = RoutePatternChoices.read(p, identities.get(p),
                                stack -> RouteView.Stack.of(stack.what(), stack.amount(), player.registryAccess()));
                    } catch (RuntimeException invalid) {
                        invalidChoices++;
                        continue;
                    }
                    size += GSON.toJson(choice).length();
                    if (size > 170000) { truncated = true; break; }
                    choices.add(choice);
                }
                if (invalidChoices > 0) message += "；另有 " + invalidChoices + " 张异常样板未显示";
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
                reply(context, request, new RouteView.Reply("操作失败：" + RouteFailureText.message(e), List.of(), null, List.of(), false, 0, false));
            }
        });
    }
    private static final RoutePlanningRequests<UUID> PLANNING=new RoutePlanningRequests<>();
    private static final RouteCanvasChunks<AEKey> CHUNKS=new RouteCanvasChunks<>();
    static void cancelPlanning(UUID player) {PLANNING.cancel(player);CHUNKS.cancel(player);}
    static void clearPlanning() {PLANNING.clear();CHUNKS.clear();}
    static void expireChunks(long now) {CHUNKS.expire(now);}
    private static void planAsync(IPayloadContext context,Request request,ServerPlayer player,AEBaseMenu menu,
                                  appeng.api.networking.IGrid grid,RoutePolicy<AEKey> base) {
        planAsync(context,request,player,menu,grid,base,request.argument());
    }
    private static void planAsync(IPayloadContext context,Request request,ServerPlayer player,AEBaseMenu menu,
                                  appeng.api.networking.IGrid grid,RoutePolicy<AEKey> base,String argument) {
        var ticket=PLANNING.replace(player.getUUID(),request.sequence());
        if(ticket==null)return;
        long amount=1;java.util.List<RouteApply.Node> nodes=java.util.List.of();
        try {
            // The argument is untrusted client text: parse it with explicit type checks so a
            // malformed or edited request is refused by its own message instead of surfacing
            // as a raw Gson failure behind the generic "操作失败" reply.
            var decoded=RouteCanvasPayload.decode(argument);
            amount=decoded.amount();nodes=decoded.nodes();
            RouteRequestRules.requirePlanAmount(amount);
            if(request.key()==null)throw new IllegalArgumentException("先选择目标材料");
            boolean apply="apply".equals(request.action());
            RouteRequestRules.requirePlanningEnabled(request.action(),com.aecraftroute.addon.config.CraftRouteConfig.isProxyEnabled());
            if(apply && menu instanceof CraftConfirmMenu) {
                var confirm=(com.aecraftroute.addon.mixin.CraftConfirmRouteAccessor)menu;
                if(!Objects.equals(request.key(),confirm.aecraftroute$routeWhatToCraft()) || amount!=confirm.aecraftroute$routeAmount())
                    throw new IllegalArgumentException("画布目标或数量与当前合成确认页不同；请从对应的确认页重新打开");
            }
            var prepared=apply?RouteApply.prepare(grid,base,player.registryAccess(),request.key(),amount,nodes,player)
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
                    new com.aecraftroute.addon.planner.ProductionPlanner.Limits(apply?150_000:30_000,apply?30_000_000_000L:8_000_000_000L),valid,progress->{
                        if(valid.getAsBoolean())context.reply(new Response(request.menu(),request.sequence(),
                                GSON.toJson(java.util.Map.of("planningProgress",progress))));
                    });
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
            reply(context,request,new RouteView.Reply("操作失败："+RouteFailureText.message(failure),List.of(),null,List.of(),false,base.rules().size(),false));
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
        String encoded=GSON.toJson(reply);
        if(encoded.length()>190_000 && reply.canvas()!=null) {
            // Never let an oversized echo turn a successfully committed plan into a
            // transport exception. The server policy is authoritative; omit only
            // this optional echo, not any constraint or the plan itself.
            reply=new RouteView.Reply((reply.message()==null?"":reply.message())+"；画布已保存，路径回显过大已省略",
                    reply.choices(),reply.locked(),reply.banned(),reply.stockOnly(),reply.ruleCount(),true,
                    reply.plan(),null,reply.requestTarget());
            encoded=GSON.toJson(reply);
        }
        context.reply(new Response(req.menu(),req.sequence(),encoded));
    }
}
