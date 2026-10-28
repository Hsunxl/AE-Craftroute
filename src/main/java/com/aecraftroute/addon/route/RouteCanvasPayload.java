package com.aecraftroute.addon.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Wire compaction may omit diagnostic labels, never executable constraints. */
public final class RouteCanvasPayload {
    public static final int MAX_CHARACTERS=24000;
    private RouteCanvasPayload() {}
    public static String encode(long amount,List<RouteApply.Node> nodes) {
        String full=json(amount,nodes,true);
        if(full.length()<=MAX_CHARACTERS)return full;
        String compact=json(amount,nodes,false);
        return compact; // Large payloads are transmitted as ordered packet-sized chunks.
    }
    private static String json(long amount,List<RouteApply.Node> nodes,boolean labels) {
        var entries=new JsonArray();
        for(var node:nodes) {
            var entry=new JsonObject();entry.addProperty("k",node.keyTag());entry.addProperty("c",node.choiceId());
            if(labels && node.label()!=null)entry.addProperty("n",node.label());
            entry.addProperty("p",node.path());
            if(node.draft()!=null)entry.add("d",RouteNetwork.GSON.toJsonTree(node.draft()));
            entries.add(entry);
        }
        var json=new JsonObject();json.addProperty("amount",Math.max(1,amount));json.add("nodes",entries);
        return json.toString();
    }
    /** Exactly the two values {@link #encode} wrote, already type-checked. */
    public record Decoded(long amount,List<RouteApply.Node> nodes) {
        public Decoded {nodes=List.copyOf(nodes);}
    }
    /**
     * Parse the wire form the server actually received. Every field is type-checked here so
     * that a malformed or edited request is refused with a readable reason instead of a raw
     * Gson exception surfacing as the generic "操作失败：..." reply. This never widens a
     * constraint: unknown keys are ignored and every missing field stays null, which
     * {@link RouteApply#prepare} then rejects on its own.
     */
    public static Decoded decode(String argument) {
        if(argument==null || argument.isBlank()) throw new IllegalArgumentException("画布数据为空");
        JsonElement root;
        try {root=JsonParser.parseString(argument);}
        catch(RuntimeException malformed) {throw new IllegalArgumentException("画布数据不是有效 JSON");}
        if(root==null || !root.isJsonObject()) throw new IllegalArgumentException("画布数据不是 JSON 对象");
        var json=root.getAsJsonObject();
        long amount=1;
        if(json.has("amount")) amount=number(json.get("amount"),"数量");
        var nodes=new ArrayList<RouteApply.Node>();
        if(json.has("nodes")) {
            var array=json.get("nodes");
            if(!array.isJsonArray()) throw new IllegalArgumentException("画布分支不是 JSON 列表");
            int index=0;
            for(var value:array.getAsJsonArray()) {
                index++;
                if(value==null || !value.isJsonObject()) throw new IllegalArgumentException("画布分支第 "+index+" 项不是 JSON 对象");
                var entry=value.getAsJsonObject();
                RouteView.Draft draft=null;
                if(entry.has("d") && !entry.get("d").isJsonNull()) {
                    if(!entry.get("d").isJsonObject())throw new IllegalArgumentException("画布分支第 "+index+" 项配方不是对象");
                    try {draft=RouteNetwork.GSON.fromJson(entry.get("d"),RouteView.Draft.class);}
                    catch(RuntimeException malformed){throw new IllegalArgumentException("画布分支第 "+index+" 项配方无效");}
                }
                nodes.add(new RouteApply.Node(text(entry,"k",index),text(entry,"c",index),
                        text(entry,"n",index),text(entry,"p",index),draft));
            }
        }
        return new Decoded(amount,nodes);
    }
    private static long number(JsonElement value,String field) {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("画布"+field+"不是数字");
        return value.getAsLong();
    }
    private static String text(JsonObject entry,String key,int index) {
        if(!entry.has(key)) return null;
        var value=entry.get(key);
        if(value==null || value.isJsonNull()) return null;
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("画布分支第 "+index+" 项的 "+key+" 不是文本");
        return value.getAsString();
    }
}
