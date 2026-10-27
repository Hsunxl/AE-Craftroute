package com.aecraftroute.addon.route.client;

import java.util.Objects;
import java.util.function.IntFunction;

/** Incremental exact-match scan; a first match is not trustworthy until all candidates were checked. */
final class RoutePreviewSearch {
    record Result(boolean pending,String id) {}
    private Object choice;
    private int next;
    private String match;
    private boolean ambiguous;

    void clear() {choice=null;next=0;match=null;ambiguous=false;}

    Result scan(Object requested,int count,int budget,IntFunction<String> matchingId) {
        if(!Objects.equals(choice,requested)) {clear();choice=requested;}
        if(count<0 || budget<=0)throw new IllegalArgumentException("Invalid preview search bounds");
        for(int work=0;next<count && work<budget;work++) {
            String id=matchingId.apply(next++);
            if(id==null)continue;
            if(match!=null) {ambiguous=true;next=count;break;}
            match=id;
        }
        return new Result(next<count,ambiguous || next<count?null:match);
    }
}
