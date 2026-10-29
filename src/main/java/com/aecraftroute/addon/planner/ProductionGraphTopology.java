package com.aecraftroute.addon.planner;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Request-local SCC condensation. Edges point from demanded output to inputs.
 * Iterative Kosaraju keeps deep recipe chains off the Java call stack. */
final class ProductionGraphTopology {
    record Components(List<int[]> demandOrder, int[] componentOf, boolean cyclic) {}
    private record Frame(int key, int next) {}

    static Components analyze(int[][] edges, boolean[] relevant, BooleanSupplier expired) {
        int n=edges.length;
        var reverse=new ArrayList<List<Integer>>(n);
        for(int k=0;k<n;k++) {if(expired.getAsBoolean())return null;reverse.add(new ArrayList<>());}
        for(int k=0;k<n;k++) if(relevant[k]) for(int child:edges[k]) {
            if(expired.getAsBoolean())return null;
            if(relevant[child])reverse.get(child).add(k);
        }
        var visited=new boolean[n]; var finished=new ArrayList<Integer>();
        var stack=new ArrayDeque<Frame>();
        for(int root=0;root<n;root++) {
            if(expired.getAsBoolean())return null;
            if(!relevant[root] || visited[root])continue;
            visited[root]=true;stack.push(new Frame(root,0));
            while(!stack.isEmpty()) {
                if(expired.getAsBoolean())return null;
                var frame=stack.pop();int k=frame.key;
                if(frame.next==edges[k].length) {finished.add(k);continue;}
                stack.push(new Frame(k,frame.next+1));int child=edges[k][frame.next];
                if(relevant[child] && !visited[child]) {visited[child]=true;stack.push(new Frame(child,0));}
            }
        }
        var componentOf=new int[n];Arrays.fill(componentOf,-1);
        var components=new ArrayList<int[]>();var pending=new ArrayDeque<Integer>();
        boolean cyclic=false;
        for(int i=finished.size()-1;i>=0;i--) {
            if(expired.getAsBoolean())return null;
            int root=finished.get(i);if(componentOf[root]>=0)continue;
            int id=components.size();var members=new ArrayList<Integer>();
            componentOf[root]=id;pending.push(root);
            while(!pending.isEmpty()) {
                if(expired.getAsBoolean())return null;
                int k=pending.pop();members.add(k);
                for(int parent:reverse.get(k)) {
                    if(expired.getAsBoolean())return null;
                    if(componentOf[parent]<0) {componentOf[parent]=id;pending.push(parent);}
                }
            }
            int[] keys=members.stream().mapToInt(Integer::intValue).sorted().toArray();
            components.add(keys);
            if(keys.length>1)cyclic=true;
            else for(int child:edges[root])if(child==root)cyclic=true;
        }
        return new Components(List.copyOf(components),componentOf,cyclic);
    }
    private ProductionGraphTopology() {}
}
