package com.aecraftroute.addon.route.client;

import com.aecraftroute.addon.route.RouteView;
import java.util.List;

/** Late query replies enrich imported/local choices by exact identity, not by output name. */
public final class RoutePatternPresentation {
    private RoutePatternPresentation() {}
    public static List<RouteView.PatternSource> sources(RouteView.Choice choice,RouteView.Reply reply) {
        if(reply!=null)for(var current:reply.choices())if(current.id().equals(choice.id()))return current.sources();
        return choice.sources();
    }
}
