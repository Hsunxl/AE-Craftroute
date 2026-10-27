package com.aecraftroute.addon.route.client;
import java.util.*;

/** Compact persistent status; full details are revealed only over the bottom status row. */
public record RouteNodeStatus(int x,int y,int width,int height) {
    public static RouteNodeStatus of(int x,int y,int width) {return new RouteNodeStatus(x+4,y+68,Math.max(1,width-8),14);}
    public boolean contains(double mx,double my) {return mx>=x && mx<x+width && my>=y && my<y+height;}
    public static String label(boolean root,boolean cycle,boolean recipe,boolean expanded,boolean emc) {
        if(cycle)return "回料引用 ▾";
        if(root)return "最终产物 ▾";
        if(!recipe)return "选择获取方式 ▾";
        return (expanded?"已展开":"已折叠")+(emc?" · EMC":"")+(expanded?" ▾":" ▸");
    }
    public record Details(String name,long demand,boolean cycle,boolean recipe,boolean expanded,int visibleChildren,
                          String proof,String startup,boolean localEdit,String cost,String error) {}
    public static List<String> details(Details data) {
        var lines=new ArrayList<String>();lines.add(data.name());lines.add("需求："+data.demand());
        lines.add(data.cycle()?"回料引用；数量只读":!data.recipe()?"尚未选择获取方式":
                data.expanded()?"已展开 · 可见下级 "+data.visibleChildren()+" 项":"下级已折叠；配方与数量保留");
        lines.add(data.proof()==null?"计划：尚未取得服务端证明":data.proof());
        if(data.startup()!=null)lines.add("起手资本："+data.startup()+"（不是库存快照）");
        if(data.localEdit())lines.add("本地编辑不等于已应用或已证明");
        if(data.cost()!=null)lines.add(data.cost()+"（数值成本，不是机器）");
        if(data.error()!=null)lines.add(data.error());
        lines.add("点击进入分支显示子菜单；Esc 返回上一级");return List.copyOf(lines);
    }
}
