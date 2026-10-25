package com.aecraftroute.addon.route.client;
import java.util.*;

/** Two-level action model. Destructive edits never appear on the first-level menu. */
public final class RouteNodeMenu {
    private RouteNodeMenu() {}
    public enum Page {ROOT,BRANCH,POLICY,QUANTITY,REMOVE,HELP}
    public enum Action {BRANCH,POLICY,QUANTITY,REMOVE,HELP,BACK,TOGGLE,RECIPES,STOCK,AUTO,EDIT,RESET,CLEAR,DELETE,NONE}
    public record Entry(Action action,String label,boolean enabled) {
        Entry(Action action,String label) {this(action,label,true);}
    }
    public record Context(boolean expanded,boolean hasRecipe,boolean root,boolean cycle,boolean overridden) {}
    public static String title(Page page) {return switch(page) {
        case ROOT->"节点操作";case BRANCH->"节点 › 分支显示";case POLICY->"节点 › 路线策略";
        case QUANTITY->"节点 › 数量编辑";case REMOVE->"节点 › 移除";case HELP->"节点 › 操作帮助";
    };}
    public static List<Entry> entries(Page page,Context ctx) {
        var back=new Entry(Action.BACK,"‹ 返回节点操作");
        return switch(page) {
            case ROOT->List.of(new Entry(Action.BRANCH,"分支显示 ›"),new Entry(Action.POLICY,"路线策略 ›"),
                    new Entry(Action.QUANTITY,ctx.root()?"数量编辑 ›":"数量由配方推导（只读）",RouteQuantityField.editable(ctx.root(),ctx.cycle())),new Entry(Action.REMOVE,"移除 ›"),new Entry(Action.HELP,"操作帮助 ›"));
            case BRANCH->List.of(back,new Entry(Action.TOGGLE,ctx.expanded()?"折叠下级（保留配方与数量）":"展开下级",ctx.hasRecipe()&&!ctx.cycle()),
                    new Entry(Action.RECIPES,"选择获取方式 / 配方 ›",!ctx.cycle()));
            case POLICY->List.of(back,new Entry(Action.STOCK,"仅用现有库存",!ctx.cycle()),new Entry(Action.AUTO,"恢复自动路由",!ctx.cycle()));
            case QUANTITY->List.of(back,new Entry(Action.EDIT,ctx.root()?"输入数量 / 滚轮调整":"由最终产物需求自动推导",RouteQuantityField.editable(ctx.root(),ctx.cycle())));
            case REMOVE->List.of(back,new Entry(Action.CLEAR,"清除下级（删除本地分支编辑）"),
                    new Entry(Action.DELETE,ctx.root()?"移除最终产物（清空画布）":"移除此节点及下级"));
            case HELP->List.of(back,new Entry(Action.NONE,"拖动节点：移动整棵子树",false),
                    new Entry(Action.NONE,"仅最终产物可修改数量",false),new Entry(Action.NONE,"机器图标：详情 / 悬停配方",false),
                    new Entry(Action.NONE,"Ctrl+Z 撤销；Home 整理",false));
        };
    }
    public static Page parent(Page page) {return Page.ROOT;}
}
