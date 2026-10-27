package com.aecraftroute.addon.route.client;

import com.aecraftroute.addon.route.RouteView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RouteDraftsTest {
    @TempDir Path temp;

    @Test
    void concurrentCanvasSavesKeepEveryDraftAndLeaveNoSharedTemporaryFile() throws Exception {
        var file=temp.resolve("drafts.json");
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(8)) {
            var saves=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<16;i++) {
                int id=i;
                saves.add(workers.submit(()->{
                    start.await();
                    RouteDrafts.save(file,new RouteView.Draft("recipe-"+id,"category",List.of(),List.of(),"note"));
                    return null;
                }));
            }
            start.countDown();
            for(var save:saves)save.get(10,TimeUnit.SECONDS);
        }
        var drafts=RouteDrafts.load(file);
        assertEquals(16,drafts.size());
        assertEquals(16,drafts.stream().map(RouteView.Draft::recipeId).distinct().count());
        try(var files=Files.list(temp)) {
            assertFalse(files.anyMatch(path->path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
