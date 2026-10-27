package com.aecraftroute.addon.route.client;

import com.aecraftroute.addon.route.*;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.*;

/** Local JEI notes only, never loaded into a server's executable pattern registry. */
public final class RouteDrafts {
    private static Path path() { return FMLPaths.CONFIGDIR.get().resolve("aecraftroute-route-drafts.json"); }
    public static List<RouteView.Draft> load() throws java.io.IOException {
        return load(path());
    }
    static List<RouteView.Draft> load(Path file) throws java.io.IOException {
        if (!Files.exists(file)) return List.of();
        if (Files.size(file) > 2_000_000) throw new java.io.IOException("草稿文件超过 2 MB");
        try {
            var drafts = RouteNetwork.GSON.fromJson(Files.readString(file), RouteView.Draft[].class);
            return drafts == null ? List.of() : List.of(drafts);
        } catch (RuntimeException e) { throw new java.io.IOException("草稿文件损坏，未覆盖原文件",e); }
    }
    public static void save(RouteView.Draft draft) throws java.io.IOException {
        save(path(),draft);
    }
    static synchronized void save(Path file,RouteView.Draft draft) throws java.io.IOException {
        // The JVM monitor handles two open canvases; the OS lock also serializes two clients
        // sharing a config directory. Hold it across read, merge and atomic replacement.
        Path lock=file.resolveSibling(file.getFileName()+".lock");
        try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var ignored=channel.lock()) {
            var list = new ArrayList<>(load(file));
            list.removeIf(d -> Objects.equals(d.recipeId(),draft.recipeId()) && Objects.equals(d.category(),draft.category()));
            list.add(0,draft);
            if (list.size() > 128) list.subList(128,list.size()).clear();
            String json = RouteNetwork.GSON.toJson(list);
            if (json.getBytes(StandardCharsets.UTF_8).length > 2_000_000) throw new java.io.IOException("草稿超过 2 MB，未保存");
            Path tmp=Files.createTempFile(file.getParent(),"aecraftroute-route-drafts-",".tmp");
            try {
                Files.writeString(tmp,json,StandardCharsets.UTF_8);
                try { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(tmp); }
        }
    }
}
