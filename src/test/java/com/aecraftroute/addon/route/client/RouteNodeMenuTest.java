package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.route.client.RouteNodeMenu.*;
class RouteNodeMenuTest {
    final Context ordinary=new Context(true,true,false,false,true);
    Entry entry(Page page,Action action,Context context) {return entries(page,context).stream().filter(e->e.action()==action).findFirst().orElseThrow();}
    @Test void firstLevelHasCategoriesNotDestructiveOperations() {
        assertEquals(5,entries(Page.ROOT,ordinary).size());
        assertFalse(entries(Page.ROOT,ordinary).stream().anyMatch(e->e.action()==Action.DELETE||e.action()==Action.CLEAR));
        assertTrue(entries(Page.ROOT,ordinary).stream().allMatch(Entry::enabled));
    }
    @Test void branchSubmenuRetainsNonDestructiveFoldAndRecipeSelection() {
        assertTrue(entry(Page.BRANCH,Action.TOGGLE,ordinary).label().contains("保留配方与数量"));
        assertTrue(entry(Page.BRANCH,Action.TOGGLE,new Context(false,true,false,false,false)).label().contains("展开"));
        assertTrue(entry(Page.BRANCH,Action.RECIPES,ordinary).enabled());
    }
    @Test void foldingAnUnconfiguredOrCycleNodeIsDisabled() {
        assertFalse(entry(Page.BRANCH,Action.TOGGLE,new Context(true,false,false,false,false)).enabled());
        assertFalse(entry(Page.BRANCH,Action.TOGGLE,new Context(true,true,false,true,false)).enabled());
    }
    @Test void resettingNumbersNeedsAnActualNonRootOverride() {
        assertTrue(entry(Page.QUANTITY,Action.RESET,ordinary).enabled());
        assertFalse(entry(Page.QUANTITY,Action.RESET,new Context(true,true,true,false,true)).enabled());
        assertFalse(entry(Page.QUANTITY,Action.RESET,new Context(true,true,false,false,false)).enabled());
        assertFalse(entry(Page.QUANTITY,Action.EDIT,new Context(true,true,false,true,true)).enabled());
    }
    @Test void removalIsExplicitAboutClearingTheRoot() {
        assertTrue(entry(Page.REMOVE,Action.DELETE,new Context(true,true,true,false,false)).label().contains("清空画布"));
        assertTrue(entry(Page.REMOVE,Action.CLEAR,ordinary).label().contains("删除本地分支编辑"));
    }
    @Test void allSubmenusHaveBackAndFitTheMinimumViewport() {
        for(var page:Page.values()) {
            var entries=entries(page,ordinary);assertTrue(24+entries.size()*18<=128);
            if(page!=Page.ROOT) {assertEquals(Action.BACK,entries.getFirst().action());assertEquals(Page.ROOT,parent(page));}
        }
        assertTrue(entries(Page.HELP,ordinary).stream().filter(e->e.action()==Action.NONE).noneMatch(Entry::enabled));
    }
}
