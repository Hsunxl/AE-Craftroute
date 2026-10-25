package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteBranchEditsTest {
    @Test void draggingFirstMaterialOnlyRevealsThatMaterial() {
        var e=new RouteBranchEdits();e.reveal("root","iron",false);
        assertTrue(e.shown("root","root/a","iron"));
        assertFalse(e.shown("root","root/b","gold"));
    }
    @Test void draggingMoreMaterialsAddsRatherThanReplaces() {
        var e=new RouteBranchEdits();e.reveal("root","iron",false);e.reveal("root","gold",true);
        assertTrue(e.shown("root","root/a","iron"));assertTrue(e.shown("root","root/b","gold"));
    }
    @Test void changingRecipeDoesNotKeepPreviousMaterialSelection() {
        var e=new RouteBranchEdits();e.reveal("root","iron",false);e.reveal("root","gold",false);
        assertFalse(e.shown("root","root/a","iron"));assertTrue(e.shown("root","root/b","gold"));
    }
    @Test void deletingOneNodeDoesNotHideItsSibling() {
        var e=new RouteBranchEdits();e.hide("root/a");
        assertFalse(e.shown("root","root/a","iron"));assertTrue(e.shown("root","root/b","gold"));
    }
    @Test void deletingSubtreeKeepsMaterialAndOtherBranchIndependent() {
        var e=new RouteBranchEdits();e.collapse("root/a");
        assertFalse(e.expanded("root/a"));assertTrue(e.expanded("root/b"));
        assertTrue(e.shown("root","root/a","iron"));
    }
    @Test void explicitRecipeSelectionRestoresAllItsInputs() {
        var e=new RouteBranchEdits();e.hide("root/a");e.reveal("root","gold",false);e.collapse("root");e.expandAll("root");
        assertTrue(e.expanded("root"));assertTrue(e.shown("root","root/a","iron"));
    }
    @Test void undoRestoresIndependentDeepSnapshot() {
        var e=new RouteBranchEdits();e.reveal("root","iron",false);e.hide("root/a");e.collapse("root/b");
        var snapshot=e.snapshot();e.reveal("root","gold",true);e.clear();e.restore(snapshot);
        assertFalse(e.expanded("root/b"));assertFalse(e.shown("root","root/a","iron"));
        assertFalse(e.shown("root","root/c","gold"));
        e.show("root/a");assertTrue(e.shown("root","root/a","iron"));
    }
    @Test void draggingOnFullyExpandedRecipeDoesNotHideExistingMaterials() {
        var e=new RouteBranchEdits();e.reveal("root","iron",true);
        assertTrue(e.shown("root","root/b","gold"));
    }
    @Test void addingMaterialDoesNotResurrectAnotherDeletedNode() {
        var e=new RouteBranchEdits();e.reveal("root","iron",false);e.hide("root/a");e.reveal("root","gold",true);
        assertFalse(e.shown("root","root/a","iron"));
    }
    @Test void removingNodeHidesItsMaterialRegardlessOfRebuiltPath() {
        var e=new RouteBranchEdits();
        e.hideMaterial("iron");
        assertTrue(e.materialHidden("iron"));
        var snapshot=e.snapshot();
        e.clear();
        assertFalse(e.materialHidden("iron"));
        e.restore(snapshot);
        assertTrue(e.materialHidden("iron"));
        e.showMaterial("iron");
        assertFalse(e.materialHidden("iron"));
    }
    @Test void foldingRoundTripPreservesFiltersAndUndo() {
        var e=new RouteBranchEdits();e.reveal("root","iron",false);e.hide("root/a");
        var before=e.snapshot();e.toggleExpanded("root");var folded=e.snapshot();
        assertFalse(e.expanded("root"));e.toggleExpanded("root");assertEquals(before,e.snapshot());
        e.restore(folded);assertFalse(e.expanded("root"));
        assertFalse(e.shown("root","root/a","iron"));assertFalse(e.shown("root","root/b","gold"));
        e.restore(before);assertTrue(e.expanded("root"));
    }
}
