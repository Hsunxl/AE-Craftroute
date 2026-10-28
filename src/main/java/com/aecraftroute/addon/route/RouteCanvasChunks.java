package com.aecraftroute.addon.route;

import java.util.*;

/** Ordered, per-session streaming. No partial document is ever applied. */
public final class RouteCanvasChunks<K> {
    public static final int CHUNK_CHARS=4000;
    private static final long TIMEOUT_NANOS=60_000_000_000L;
    private static final class State<K> {
        final int menu,sequence,count;final K key;final StringBuilder data=new StringBuilder();
        int next;long touched;
        State(int menu,int sequence,K key,int count,long now){this.menu=menu;this.sequence=sequence;this.key=key;this.count=count;touched=now;}
    }
    private final Map<UUID,State<K>> active=new HashMap<>();
    public static List<String> split(String text) {
        Objects.requireNonNull(text);var parts=new ArrayList<String>();
        for(int i=0;i<text.length();) {
            int end=Math.min(text.length(),i+CHUNK_CHARS);
            if(end<text.length() && Character.isHighSurrogate(text.charAt(end-1)) && Character.isLowSurrogate(text.charAt(end)))end--;
            parts.add(text.substring(i,end));i=end;
        }
        if(parts.isEmpty())parts.add("");return List.copyOf(parts);
    }
    public String accept(UUID player,int menu,int sequence,K key,int index,int count,String chunk,long now) {
        expire(now);
        if(index<0 || count<=0 || index>=count || chunk==null || chunk.length()>CHUNK_CHARS)
            throw new IllegalArgumentException("画布分片无效");
        if(index==0)active.put(player,new State<>(menu,sequence,key,count,now));
        var state=active.get(player);
        if(state==null || state.menu!=menu || state.sequence!=sequence || !Objects.equals(state.key,key)
                || state.count!=count || state.next!=index) {
            active.remove(player);throw new IllegalArgumentException("画布分片缺失、乱序或会话已变化；未应用");
        }
        state.data.append(chunk);state.next++;state.touched=now;
        if(state.next<count)return null;
        active.remove(player);return state.data.toString();
    }
    public void expire(long now){active.entrySet().removeIf(e->now-e.getValue().touched>TIMEOUT_NANOS);}
    public void cancel(UUID player){active.remove(player);}
    public void clear(){active.clear();}
}
