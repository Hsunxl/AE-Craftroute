package com.aecraftroute.addon.route;

/** Converts planner and packet failures to non-empty, actionable UI text. */
public final class RouteFailureText {
    public static String message(Throwable failure) {
        if(failure==null)return "未知错误";
        Throwable cause=failure;
        for(int depth=0;depth<8 && cause.getCause()!=null && cause.getCause()!=cause;depth++)
            cause=cause.getCause();
        if(cause instanceof ArithmeticException)return "数量超出整数范围";
        String detail=cause.getMessage();
        return detail==null || detail.isBlank()?cause.getClass().getSimpleName():detail;
    }
    private RouteFailureText() {}
}
