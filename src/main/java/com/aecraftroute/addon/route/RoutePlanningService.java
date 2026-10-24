package com.aecraftroute.addon.route;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.planner.*;
import net.minecraft.core.HolderLookup;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.aecraftroute.addon.planner.ProductionPlanningException.Reason.*;

/** Captures mutable network state exclusively on server ticks. The bounded worker pool
 * only sees immutable snapshot maps. Queue wait, capture, variants and proof share one
 * deadline. Cancellation propagates to the actual worker Future, not just its wrapper. */
@EventBusSubscriber(modid="aecraftroute")
public final class RoutePlanningService {
    private static final long SLICE_NANOS=2_000_000L, TICK_NANOS=6_000_000L;
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"aecraftroute-route-plan");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final List<Job> JOBS=new ArrayList<>();
    interface CaptureWork {
        boolean advance();
        RouteSnapshot.Result result();
        void validate(long deadline,long validationEnd);
    }
    private static final class LiveCapture implements CaptureWork {
        final RouteSnapshot.Capture source;
        LiveCapture(RouteSnapshot.Capture source){this.source=source;}
        public boolean advance(){return source.advance();}
        public RouteSnapshot.Result result(){return source.result();}
        public void validate(long deadline,long validationEnd) {
            var live=source.liveInventory();RouteCanvasPlanner.check(deadline);
            var entries=source.validation();
            while(entries.hasNext()) {
                if(!source.unchanged(entries.next(),live))throw new ProductionPlanningException(INVALID,"网络样板或库存已变化；旧计划已丢弃，请重试");
                RouteCanvasPlanner.check(validationEnd);
            }
        }
    }
    static final class Job {
        final CaptureWork capture; final ExecutorService executor; final RoutePolicy<AEKey> policy; final AEKey key;
        final long amount,deadline; final int states; final BooleanSupplier valid;
        final CompletableFuture<CertifiedCraftingPlan> result=new CompletableFuture<>();
        volatile Future<?> worker; volatile CertifiedCraftingPlan plan; volatile Throwable error; volatile boolean finished;
        Job(IGrid grid,RoutePolicy<AEKey> policy,HolderLookup.Provider registries,AEKey key,long amount,ProductionPlanner.Limits limits,BooleanSupplier valid) {
            this(policy,key,amount,limits,valid,WORKERS,null);
            captureSource=new LiveCapture(new RouteSnapshot.Capture(grid,policy,registries,key,deadline));
        }
        private CaptureWork captureSource;
        /** Same production state machine with injectable capture and executor for lifecycle tests. */
        Job(RoutePolicy<AEKey> policy,AEKey key,long amount,ProductionPlanner.Limits limits,BooleanSupplier valid,
            ExecutorService executor,CaptureWork capture) {
            this.policy=policy;this.key=key;this.amount=amount;this.states=limits.states();this.valid=valid;this.executor=executor;
            deadline=System.nanoTime()+limits.nanos();this.capture=capture;
            result.whenComplete((p,e)->{if(result.isCancelled() && worker!=null)worker.cancel(true);});
        }
        private CaptureWork capture() {return capture==null?captureSource:capture;}
        void tick(long sliceEnd) {
            if(result.isDone())return;
            try {
                if(!valid.getAsBoolean()) {result.cancel(true);return;}
                RouteCanvasPlanner.check(deadline);
                if(worker==null) {
                    while(!capture().advance()) {if(System.nanoTime()>=sliceEnd)return;}
                    var frozen=capture().result();
                    try {
                        worker=executor.submit(()->{
                            try { plan=planFrozen(frozen,policy,key,amount,new ProductionPlanner.Limits(states,RouteCanvasPlanner.remaining(deadline))); }
                            catch(Throwable failure) {error=failure;} finally {finished=true;}
                        });
                    } catch(RejectedExecutionException full) {throw new ProductionPlanningException(LIMIT,"规划工作队列已满；不是缺料");}
                    if(result.isCancelled())worker.cancel(true);
                } else if(finished) {
                    if(error!=null) {result.completeExceptionally(error);return;}
                    // One atomic server-thread validation/commit boundary. Do not incrementally
                    // validate across ticks then claim the early keys are still current.
                    long validationEnd=Math.min(deadline,System.nanoTime()+SLICE_NANOS);
                    capture().validate(deadline,validationEnd);
                    if(!valid.getAsBoolean()) {result.cancel(true);return;}
                    RouteCanvasPlanner.check(deadline);result.complete(plan);
                }
            } catch(Throwable failure) {if(worker!=null)worker.cancel(true);result.completeExceptionally(failure);}
        }
    }
    public static CompletableFuture<CertifiedCraftingPlan> planAsync(IGrid grid,RoutePolicy<AEKey> policy,
            HolderLookup.Provider registries,AEKey key,long amount,ProductionPlanner.Limits limits,BooleanSupplier valid) {
        var server=ServerLifecycleHooks.getCurrentServer();
        if(server==null)return CompletableFuture.failedFuture(new ProductionPlanningException(INVALID,"服务器已停止"));
        var outer=new CompletableFuture<CertifiedCraftingPlan>();
        Runnable enqueue=()->{
            if(outer.isCancelled())return;
            if(JOBS.size()>=32){outer.completeExceptionally(new ProductionPlanningException(LIMIT,"规划队列已满；不是缺料"));return;}
            try {
                var job=new Job(grid,policy,registries,key,amount,limits,valid);JOBS.add(job);
                outer.whenComplete((p,e)->{if(outer.isCancelled())job.result.cancel(true);});
                job.result.whenComplete((p,e)->{if(e!=null)outer.completeExceptionally(e);else outer.complete(p);});
            } catch(Throwable failure) {outer.completeExceptionally(failure);}
        };
        if(server.isSameThread())enqueue.run();else server.execute(enqueue);
        return outer;
    }
    public static CertifiedCraftingPlan planFrozen(RouteSnapshot.Result snapshot,RoutePolicy<AEKey> policy,
            AEKey key,long amount,ProductionPlanner.Limits limits) {
        if(policy.canvas()!=null)return RouteCanvasPlanner.plan(snapshot,policy.canvas(),key,amount,limits);
        return NetworkProductionPlanner.plan(key,amount,k->snapshot.patterns().getOrDefault(k,List.of()),k->snapshot.stock().getOrDefault(k,0L),limits);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        tickJobs(JOBS,System.nanoTime()+TICK_NANOS);WORKERS.purge();
    }
    /** Production tick/stop logic, shared without replacing the actual scheduling implementation. */
    static void tickJobs(List<Job> jobs,long end) {
        int count=jobs.size();
        for(int i=0;i<count && !jobs.isEmpty() && System.nanoTime()<end;i++) {
            var job=jobs.remove(0);job.tick(Math.min(end,System.nanoTime()+SLICE_NANOS));
            if(!job.result.isDone())jobs.add(job);
        }
    }
    static void cancelJobs(List<Job> jobs) {
        var old=new ArrayList<>(jobs);jobs.clear();for(var j:old)j.result.cancel(true);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {cancelJobs(JOBS);WORKERS.purge();}
    private RoutePlanningService() {}
}