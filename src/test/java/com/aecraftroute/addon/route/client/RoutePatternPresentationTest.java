package com.aecraftroute.addon.route.client;
import com.aecraftroute.addon.route.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoutePatternPresentationTest {
    final RouteView.PatternSource source=new RouteView.PatternSource("供应器",new RouteView.Stack("provider","provider",1),"overworld 1, 2, 3",4);
    RouteView.Choice choice(String id,List<RouteView.PatternSource> sources) {return new RouteView.Choice(id,List.of(),List.of(),false,sources);}
    RouteView.Reply reply(RouteView.Choice value) {return new RouteView.Reply("ok",List.of(value),null,List.of(),false,0,false);}
    @Test void lateMetadataEnrichesAnAlreadySelectedChoice() {
        assertEquals(List.of(source),RoutePatternPresentation.sources(choice("a",List.of()),reply(choice("a",List.of(source)))));
    }
    @Test void sameOutputNeverAssociatesAnotherPatternIdentity() {
        assertTrue(RoutePatternPresentation.sources(choice("a",List.of()),reply(choice("b",List.of(source)))).isEmpty());
    }
    @Test void refreshedEmptySourcesRemoveStaleOrigin() {
        assertTrue(RoutePatternPresentation.sources(choice("a",List.of(source)),reply(choice("a",List.of()))).isEmpty());
    }
    @Test void completedReloadWithRemovedPatternCannotKeepStaleProvider() {
        assertTrue(RoutePatternPresentation.sources(choice("removed",List.of(source)),reply(choice("other",List.of()))).isEmpty());
    }
    @Test void truncatedReloadDoesNotClaimPatternWasRemoved() {
        var partial=new RouteView.Reply("ok",List.of(),null,List.of(),false,0,true);
        assertEquals(List.of(source),RoutePatternPresentation.sources(choice("a",List.of(source)),partial));
    }
    @Test void olderPayloadsAndConstructorsHaveAnEmptySourceList() {
        var old=new com.google.gson.Gson().fromJson("{\"id\":\"a\",\"inputs\":[],\"outputs\":[],\"alternatives\":false}",RouteView.Choice.class);
        assertNotNull(old.sources());assertTrue(old.sources().isEmpty());
        assertTrue(new RouteView.Choice("a",List.of(),List.of(),false).sources().isEmpty());
    }
    @Test void sourcesSurviveWireRoundTripWithTheirExactSlot() {
        var gson=new com.google.gson.Gson();var expected=choice("a",List.of(source));
        assertEquals(expected,gson.fromJson(gson.toJson(expected),RouteView.Choice.class));
    }
}
