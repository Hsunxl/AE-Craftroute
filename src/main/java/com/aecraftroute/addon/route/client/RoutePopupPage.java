package com.aecraftroute.addon.route.client;

/** Pure page geometry shared by both recipe pickers. Page is a page number, never a row offset. */
record RoutePopupPage(int page, int capacity, int start, int end, int pages) {
    static RoutePopupPage of(int count, int capacity, int requestedPage) {
        count = Math.max(0, count);
        capacity = Math.max(1, capacity);
        int pages = count == 0 ? 1 : 1 + (count - 1) / capacity;
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * capacity;
        return new RoutePopupPage(page, capacity, start, start + Math.min(capacity, count - start), pages);
    }
    RoutePopupPage move(int count, int delta) {
        long next = (long) page + delta;
        return of(count, capacity, (int) Math.max(0, Math.min(Integer.MAX_VALUE, next)));
    }
    int rows() { return end - start; }
    String range(int count) { return count <= 0 ? "无配方" : (start + 1) + "–" + end + " / " + count; }
    static int capacity(int availableHeight, int reservedHeight, int maximum) {
        return Math.max(1, Math.min(maximum, Math.max(0, availableHeight - reservedHeight) / 18));
    }
}
