package com.ae2vm.addon.route.client;

import com.ae2vm.addon.route.*;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local JEI notes only, never loaded into a server's executable pattern registry. */
public final class RouteDrafts {
    private static Path path() { return FMLPaths.CONFIGDIR.get().resolve("ae2vm-route-drafts.json"); }
    public static List<RouteView.Draft> load() throws java.io.IOException {
        if (!Files.exists(path())) return List.of();
        if (Files.size(path()) > 2_000_000) throw new java.io.IOException("草稿文件超过 2 MB");
        try {
            var drafts = RouteNetwork.GSON.fromJson(Files.readString(path()), RouteView.Draft[].class);
            return drafts == null ? List.of() : List.of(drafts);
        } catch (RuntimeException e) { throw new java.io.IOException("草稿文件损坏，未覆盖原文件",e); }
    }
    public static void save(RouteView.Draft draft) throws java.io.IOException {
        var list = new ArrayList<>(load());
        list.removeIf(d -> Objects.equals(d.recipeId(),draft.recipeId()) && Objects.equals(d.category(),draft.category()));
        list.add(0,draft);
        if (list.size() > 128) list.subList(128,list.size()).clear();
        String json = RouteNetwork.GSON.toJson(list);
        if (json.getBytes(StandardCharsets.UTF_8).length > 2_000_000) throw new java.io.IOException("草稿超过 2 MB，未保存");
        Path tmp = path().resolveSibling("ae2vm-route-drafts.json.tmp");
        Files.writeString(tmp,json);
        try { Files.move(tmp,path(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp,path(),StandardCopyOption.REPLACE_EXISTING); }
    }
}
