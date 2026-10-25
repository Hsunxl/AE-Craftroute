package com.aecraftroute.addon.planner;

/** A typed planning failure must never trigger an older, uncertified planner. */
public final class ProductionPlanningException extends RuntimeException {
    public enum Reason { UNREACHABLE, LIMIT, UNSUPPORTED, INVALID }
    private final Reason reason;
    public record StockDeficit(appeng.api.stacks.AEKey key,long needed,long available) {}
    private final StockDeficit stockDeficit;
    public ProductionPlanningException(Reason reason,String message) {this(reason,message,null);}
    private ProductionPlanningException(Reason reason,String message,StockDeficit stockDeficit) {
        super(message);this.reason=reason;this.stockDeficit=stockDeficit;
    }
    public static ProductionPlanningException startupStockChanged(appeng.api.stacks.AEKey key,long needed,long available) {
        return new ProductionPlanningException(Reason.INVALID,"起手材料在提交前已不足："+key+"，需要 "+needed+"，当前 "+available+"；请重新规划",
                new StockDeficit(key,needed,available));
    }
    public Reason reason() { return reason; }
    public StockDeficit stockDeficit(){return stockDeficit;}
}
