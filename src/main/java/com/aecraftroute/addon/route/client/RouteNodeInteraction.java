package com.aecraftroute.addon.route.client;

/** Menu routing without Minecraft dependencies. A drag latches even if the pointer returns to its origin. */
final class RouteNodeInteraction {
    enum Action { NONE, DRAG, RECIPES, REMOVE, QUANTITY, MACHINE }
    static Action press(int button, boolean recipePort) {
        if (button == 2) return Action.QUANTITY;
        if (button == 1 || button == 0 && recipePort) return Action.RECIPES;
        return button == 0 ? Action.DRAG : Action.NONE;
    }
    static Action release(boolean dragged, boolean machineHit) {
        return dragged ? Action.NONE : machineHit ? Action.MACHINE : Action.REMOVE;
    }
    static boolean moved(boolean alreadyMoved, double dx, double dy) {
        return alreadyMoved || Math.hypot(dx, dy) >= 4;
    }
    private RouteNodeInteraction() {}
}
