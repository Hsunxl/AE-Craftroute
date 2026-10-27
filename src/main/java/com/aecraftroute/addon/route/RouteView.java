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
    public record PatternSource(String name,Stack icon,String location,int slot) {}
    public record Choice(String id, List<Stack> inputs, List<Stack> outputs, boolean alternatives,List<PatternSource> sources) {
        public Choice {
            inputs=inputs==null?List.of():List.copyOf(inputs);
            outputs=outputs==null?List.of():List.copyOf(outputs);
            sources=sources==null?List.of():List.copyOf(sources);
        }
        // Gson may construct records without running the compact constructor when
        // reading older/foreign replies with omitted fields.
        @Override public List<Stack> inputs() {return inputs==null?List.of():inputs;}
        @Override public List<Stack> outputs() {return outputs==null?List.of():outputs;}
        @Override public List<PatternSource> sources() {return sources==null?List.of():sources;}
        public Choice(String id,List<Stack> inputs,List<Stack> outputs,boolean alternatives) {this(id,inputs,outputs,alternatives,List.of());}
    }
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
        public Reply {
            choices=choices==null?List.of():List.copyOf(choices);
            banned=banned==null?List.of():List.copyOf(banned);
        }
        // Gson can read older/foreign records without invoking the compact constructor.
        @Override public List<Choice> choices() {return choices==null?List.of():choices;}
        @Override public List<String> banned() {return banned==null?List.of():banned;}
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
