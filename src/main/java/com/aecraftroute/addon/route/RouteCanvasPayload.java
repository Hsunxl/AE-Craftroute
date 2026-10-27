package com.aecraftroute.addon.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** Wire compaction may omit diagnostic labels, never executable constraints. */
public final class RouteCanvasPayload {
    public static final int MAX_NODES=128, MAX_CHARACTERS=24000;
    private RouteCanvasPayload() {}
    public static String encode(long amount,List<RouteApply.Node> nodes) {
        if(nodes.size()>MAX_NODES)throw new IllegalArgumentException("强制画布有 "+nodes.size()+" 个分支，最多 "+MAX_NODES+"；未截断或应用任何分支");
        String full=json(amount,nodes,true);
        if(full.length()<=MAX_CHARACTERS)return full;
        String compact=json(amount,nodes,false);
        if(compact.length()>MAX_CHARACTERS)
            throw new IllegalArgumentException("画布约束数据仍有 "+compact.length()+" 字符，最多 "+MAX_CHARACTERS+"；请减少展开分支，未应用任何分支");
        return compact;
    }
    private static String json(long amount,List<RouteApply.Node> nodes,boolean labels) {
        var entries=new JsonArray();
        for(var node:nodes) {
            var entry=new JsonObject();entry.addProperty("k",node.keyTag());entry.addProperty("c",node.choiceId());
            if(labels && node.label()!=null)entry.addProperty("n",node.label());
            entry.addProperty("p",node.path());entries.add(entry);
        }
        var json=new JsonObject();json.addProperty("amount",Math.max(1,amount));json.add("nodes",entries);
        return json.toString();
    }
}
