package com.aecraftroute.addon.route;

import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.TagParser;
import java.util.List;

/** Data-only DTOs shared by the UI, JEI drafts and network. Drafts are previews until explicitly encoded by the server. */
public final class RouteView {
    public record Stack(String key, String name, long amount) {
        public static Stack of(AEKey key, long amount, HolderLookup.Provider registries) {
            return new Stack(key.toTagGeneric(registries).toString(), key.getDisplayName().getString(), amount);
        }
        public AEKey decode(HolderLookup.Provider registries) {
            return decodeKey(key, registries);
        }
    }

    /** SNBT generic-key text back to a key; the one decoder used by both directions. */
    public static AEKey decodeKey(String tag, HolderLookup.Provider registries) {
        try {
            return AEKey.fromTagGeneric(registries, TagParser.parseTag(tag));
        } catch (Exception e) {
            throw new IllegalArgumentException("材料数据无法读取", e);
        }
    }
    public record Choice(String id, List<Stack> inputs, List<Stack> outputs, boolean alternatives) {}
    public record PathChoice(String path,String choiceId) {}
    /** Authoritative confirm-menu request; unlike private client menu fields, this is synchronized. */
    public record RequestTarget(String key,long amount) {}
    public record CanvasChoices(String target,long amount,List<PathChoice> paths) {
        public static CanvasChoices of(RoutePolicy.Canvas<AEKey> canvas,HolderLookup.Provider registries) {
            if(canvas==null)return null;
            return new CanvasChoices(canvas.target().toTagGeneric(registries).toString(),canvas.amount(),
                    canvas.paths().stream().map(p->new PathChoice(p.path(),p.choiceId())).toList());
        }
    }
    public record Reply(String message, List<Choice> choices, String locked, List<String> banned,
                        boolean stockOnly, int ruleCount, boolean truncated, RoutePlanView plan,CanvasChoices canvas,RequestTarget requestTarget) {
        public Reply(String message,List<Choice> choices,String locked,List<String> banned,
                     boolean stockOnly,int ruleCount,boolean truncated,RoutePlanView plan,CanvasChoices canvas) {
            this(message,choices,locked,banned,stockOnly,ruleCount,truncated,plan,canvas,null);
        }
        public Reply(String message,List<Choice> choices,String locked,List<String> banned,
                     boolean stockOnly,int ruleCount,boolean truncated,RoutePlanView plan) {
            this(message,choices,locked,banned,stockOnly,ruleCount,truncated,plan,null);
        }
        public Reply withCanvas(CanvasChoices canvas) {return new Reply(message,choices,locked,banned,stockOnly,ruleCount,truncated,plan,canvas,requestTarget);}
        public Reply withRequestTarget(RequestTarget target) {return new Reply(message,choices,locked,banned,stockOnly,ruleCount,truncated,plan,canvas,target);}
        /** Back-compat: replies that carry no server-computed verdict. */
        public Reply(String message, List<Choice> choices, String locked, List<String> banned,
                     boolean stockOnly, int ruleCount, boolean truncated) {
            this(message, choices, locked, banned, stockOnly, ruleCount, truncated, null);
        }
    }
    public record Draft(String recipeId, String category, List<Stack> inputs, List<Stack> outputs, String note) {}
}
