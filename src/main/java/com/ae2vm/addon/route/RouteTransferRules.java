package com.ae2vm.addon.route;

import java.util.function.BooleanSupplier;

/** One-item move contract; run on the server thread, never remove cache before native insertion succeeds. */
public final class RouteTransferRules {
    private RouteTransferRules() {}
    public static void transferOne(int count,boolean empty,boolean accepts,BooleanSupplier simulate,
                                   BooleanSupplier insert,Runnable removeCached) {
        if(count!=1)throw new IllegalArgumentException("缓存每槽只能保存一张样板");
        if(!empty)throw new IllegalArgumentException("目标槽位已占用，不覆盖原样板");
        if(!accepts || !simulate.getAsBoolean())throw new IllegalArgumentException("供应器拒绝此样板，未移动缓存");
        if(!insert.getAsBoolean())throw new IllegalArgumentException("供应器插入失败，样板仍在缓存");
        removeCached.run();
    }
    public static void validateToken(int slot,int capacity,String expected,String actual) {
        if(slot<0 || slot>=capacity || expected==null || expected.isBlank() || !expected.equals(actual))
            throw new IllegalArgumentException("缓存样板已变化，请重新拖动");
    }
}
