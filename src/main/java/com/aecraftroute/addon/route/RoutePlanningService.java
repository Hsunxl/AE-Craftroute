package com.aecraftroute.addon.route;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.planner.*;
import com.aecraftroute.addon.CraftRouteAddon;
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
    private static final long SLOW_REQUEST_NANOS=500_000_000L;
    private enum Phase { CAPTURE("capture"), WORKER_QUEUE("worker queue"), WORKER_COMPUTE("worker computation"), VALIDATION("validation"), COMPLETE("complete");
        final String label; Phase(String label){this.label=label;} }
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"aecraftroute-route-plan");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final List<Job> JOBS=new ArrayList<>();
    interface CaptureWork {
        boolean advance();
        RouteSnapshot.Result result();
        void validate(long deadline,long validationEnd,CertifiedCraftingPlan plan);
        default boolean refresh(AEKey target){return false;}
    }
    private static final class LiveCapture implements CaptureWork {
        RouteSnapshot.Capture source;
        LiveCapture(RouteSnapshot.Capture source){this.source=source;}
        public boolean advance(){return source.advance();}
        public RouteSnapshot.Result result(){return source.result();}
        public boolean refresh(AEKey target){source=source.refreshed(target);return true;}
        public void validate(long deadline,long validationEnd,CertifiedCraftingPlan plan) {
            var live=source.liveInventory();RouteCanvasPlanner.check(deadline);
            var entries=source.validation();
            while(entries.hasNext()) {
                if(!source.unchanged(entries.next()))throw new ProductionPlanningException(INVALID,"网络样板已变化；旧计划已丢弃，请重试");
                RouteCanvasPlanner.check(validationEnd);
            }
            // The proof needs executable inputs, not a frozen network. Stock changes in
            // unrelated parts of a busy ATM10 grid must not invalidate an otherwise valid
            // plan. Native tryExtractInitialItems still checks these amounts atomically
            // before consuming anything, so a later race remains a safe missing-item refusal.
            for(var entry:plan.usedItems()) {
                long needed=entry.getLongValue();
                long available=Math.max(0,live.get(entry.getKey()));
                if(needed>0 && available<needed)
                    throw ProductionPlanningException.startupStockChanged(entry.getKey(),needed,available);
                RouteCanvasPlanner.check(validationEnd);
            }
        }
    }
    /** Conservative cross-request fast path inspired by upstream AE2-VM tryFastPath: a repeated
     * request for the same grid/policy/target/amount reuses the last certified plan when
     * every pattern it executes is still published and its startup capital is still in
     * stock as pure leaves. Capture and search are skipped entirely; the atomic validation boundary is
     * NOT skipped, it runs inline on the server thread. */
    private static final Map<IGrid,Map<ReuseKey,CertifiedCraftingPlan>> REUSE=new WeakHashMap<>();
    private record ReuseKey(RoutePolicy<AEKey> policy,AEKey key,long amount) {}
    static synchronized void remember(IGrid grid,RoutePolicy<AEKey> policy,AEKey key,long amount,CertifiedCraftingPlan plan) {
        var per=REUSE.computeIfAbsent(grid,g->new LinkedHashMap<>(16,0.75f,true));
        per.put(new ReuseKey(policy,key,amount),plan);
        if(per.size()>64) {var oldest=per.keySet().iterator();oldest.next();oldest.remove();}
    }
    static synchronized CertifiedCraftingPlan reuse(IGrid grid,RoutePolicy<AEKey> policy,AEKey key,long amount) {
        var per=REUSE.get(grid); return per==null?null:per.get(new ReuseKey(policy,key,amount));
    }
    static synchronized void forget(IGrid grid,RoutePolicy<AEKey> policy,AEKey key,long amount) {
        var per=REUSE.get(grid); if(per!=null) per.remove(new ReuseKey(policy,key,amount));
    }
    static synchronized void clearReuse() {REUSE.clear();}
    /** Conservative fast-path guards: startup capital must still be stocked pure
     * leaves, and every executed pattern must still be the published instance.
     * A failed guard or expired tick slice falls back to normal capture/search. */
    static boolean stillExecutable(IGrid grid,CertifiedCraftingPlan plan,long validationEnd) {
        if(System.nanoTime()>=validationEnd)return false;
        var live=grid.getStorageService().getInventory().getAvailableStacks();
        for(var entry:plan.usedItems()) {
            if(System.nanoTime()>=validationEnd)return false;
            if(entry.getLongValue()>0 && Math.max(0,live.get(entry.getKey()))<entry.getLongValue()) return false;
            if(entry.getLongValue()>0 && !grid.getCraftingService().getCraftingFor(entry.getKey()).isEmpty())return false;
        }
        for(var pattern:plan.patternTimes().keySet()) {
            if(System.nanoTime()>=validationEnd)return false;
            boolean published=false;
            for(var out:pattern.getOutputs()) {
                if(System.nanoTime()>=validationEnd)return false;
                if(grid.getCraftingService().getCraftingFor(out.what()).stream().anyMatch(p->p==pattern)) {published=true;break;}
            }
            if(!published) return false;
            com.aecraftroute.addon.compat.ScaledPatternCompat.validate(pattern);
        }
        return System.nanoTime()<validationEnd;
    }
    static final class Job {
        final CaptureWork capture; final ExecutorService executor; final RoutePolicy<AEKey> policy; final AEKey key;
        final IGrid gridRef;
        final long amount,deadline; final int states; final BooleanSupplier valid;
        final long createdAt=System.nanoTime();
        volatile Phase phase=Phase.CAPTURE,errorPhase;
        volatile long captureNanos,workerSubmittedAt,workerStartedAt,workerFinishedAt,validationNanos;
        private boolean diagnosticLogged;
        private static final int MAX_STOCK_REPLANS=2;
        int stockReplans;
        private final Set<AEKey> unstableStock=new LinkedHashSet<>();
        private long priorQueueNanos,priorComputeNanos;
        final CompletableFuture<CertifiedCraftingPlan> result=new CompletableFuture<>();
        volatile Future<?> worker; volatile CertifiedCraftingPlan plan; volatile Throwable error; volatile boolean finished;
        Job(IGrid grid,RoutePolicy<AEKey> policy,HolderLookup.Provider registries,AEKey key,long amount,ProductionPlanner.Limits limits,BooleanSupplier valid) {
            this(grid,policy,key,amount,limits,valid,WORKERS,null);
            captureSource=new LiveCapture(new RouteSnapshot.Capture(grid,policy,registries,key,deadline));
        }
        private CaptureWork captureSource;
        /** Same production state machine with injectable capture and executor for lifecycle tests. */
        Job(RoutePolicy<AEKey> policy,AEKey key,long amount,ProductionPlanner.Limits limits,BooleanSupplier valid,
            ExecutorService executor,CaptureWork capture) {
            this(null,policy,key,amount,limits,valid,executor,capture);
        }
        private Job(IGrid grid,RoutePolicy<AEKey> policy,AEKey key,long amount,ProductionPlanner.Limits limits,BooleanSupplier valid,
            ExecutorService executor,CaptureWork capture) {
            this.policy=policy;this.key=key;this.amount=amount;this.states=limits.states();this.valid=valid;this.executor=executor;
            this.gridRef=grid;
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
                    if(gridRef!=null && stockReplans==0) {
                        var cached=reuse(gridRef,policy,key,amount);
                        if(cached!=null) {
                            phase=Phase.VALIDATION;long validationStart=System.nanoTime();
                            boolean executable;
                            try {executable=stillExecutable(gridRef,cached,Math.min(deadline,sliceEnd));} finally {validationNanos+=System.nanoTime()-validationStart;}
                            if(executable) {
                                if(!valid.getAsBoolean()) {result.cancel(true);return;}
                                RouteCanvasPlanner.check(deadline);phase=Phase.COMPLETE;result.complete(cached);return;
                            }
                            forget(gridRef,policy,key,amount);
                        }
                    }
                    phase=Phase.CAPTURE;
                    while(true) {
                        long captureStart=System.nanoTime();boolean done;
                        try {done=capture().advance();} finally {captureNanos+=System.nanoTime()-captureStart;}
                        if(done)break;
                        if(System.nanoTime()>=sliceEnd)return;
                    }
                    long captureStart=System.nanoTime();RouteSnapshot.Result frozen;
                    try {frozen=capture().result();} finally {captureNanos+=System.nanoTime()-captureStart;}
                    var avoidStock=Set.copyOf(unstableStock);
                    workerSubmittedAt=System.nanoTime();phase=Phase.WORKER_QUEUE;
                    try {
                        worker=executor.submit(()->{
                            workerStartedAt=System.nanoTime();
                            try {
                                RouteCanvasPlanner.check(deadline);
                                phase=Phase.WORKER_COMPUTE;
                                plan=planFrozenAvoidingUnstable(frozen,policy,key,amount,states,deadline,avoidStock);
                            } catch(Throwable failure) {error=failure;errorPhase=phase;} finally {workerFinishedAt=System.nanoTime();finished=true;}
                        });
                    } catch(RejectedExecutionException full) {throw new ProductionPlanningException(LIMIT,"规划工作队列已满；不是缺料");}
                    if(result.isCancelled())worker.cancel(true);
                } else if(finished) {
                    if(error!=null) {completeFailure(error,errorPhase==null?Phase.WORKER_COMPUTE:errorPhase);return;}
                    // One atomic server-thread validation/commit boundary. Do not incrementally
                    // validate across ticks then claim the early keys are still current.
                    long validationEnd=Math.min(deadline,System.nanoTime()+SLICE_NANOS);
                    phase=Phase.VALIDATION;long validationStart=System.nanoTime();
                    try {capture().validate(deadline,validationEnd,plan);}
                    catch(ProductionPlanningException changed) {
                        if(changed.stockDeficit()==null || stockReplans>=MAX_STOCK_REPLANS)throw changed;
                        RouteCanvasPlanner.check(deadline);
                        if(!valid.getAsBoolean()){result.cancel(true);return;}
                        if(!capture().refresh(key))throw changed;
                        var deficit=changed.stockDeficit();unstableStock.add(deficit.key());stockReplans++;
                        CraftRouteAddon.LOGGER.info("[AE-Craftroute] startup stock changed; recapturing target={} amount={} key={} needed={} available={} retry={}/{}",
                                key,amount,deficit.key(),deficit.needed(),deficit.available(),stockReplans,MAX_STOCK_REPLANS);
                        if(gridRef!=null)forget(gridRef,policy,key,amount);
                        priorQueueNanos+=Math.max(0,workerStartedAt-workerSubmittedAt);
                        priorComputeNanos+=Math.max(0,workerFinishedAt-workerStartedAt);
                        worker=null;plan=null;error=null;errorPhase=null;finished=false;
                        workerSubmittedAt=workerStartedAt=workerFinishedAt=0;phase=Phase.CAPTURE;
                        return;
                    } finally {validationNanos+=System.nanoTime()-validationStart;}
                    if(!valid.getAsBoolean()) {result.cancel(true);return;}
                    RouteCanvasPlanner.check(deadline);phase=Phase.COMPLETE;
                    if(gridRef!=null) remember(gridRef,policy,key,amount,plan);
                    result.complete(plan);
                }
            } catch(Throwable failure) {
                if(worker!=null)worker.cancel(true);
                completeFailure(failure,phase);
            } finally {logDiagnosticIfNeeded();}
        }
        private void completeFailure(Throwable failure,Phase failedPhase) {
            phase=failedPhase;
            if(failure instanceof ProductionPlanningException planning && planning.reason()==LIMIT) {
                var contextual=new ProductionPlanningException(planning.reason(),planning.getMessage()+" [阶段="+failedPhase.label+"]");
                contextual.initCause(planning);failure=contextual;
            }
            result.completeExceptionally(failure);
        }
        private void logDiagnosticIfNeeded() {
            if(diagnosticLogged || !result.isDone() || result.isCancelled() || (!result.isCompletedExceptionally() && System.nanoTime()-createdAt<SLOW_REQUEST_NANOS))return;
            diagnosticLogged=true;
            long now=System.nanoTime(),submitted=workerSubmittedAt,started=workerStartedAt,finishedAt=workerFinishedAt;
            double captureActiveMs=captureNanos/1_000_000.0;
            double captureWallMs=((submitted==0?now:submitted)-createdAt)/1_000_000.0;
            double queueMs=priorQueueNanos+(submitted==0?0:(started==0?now:started)-submitted);
            double computeMs=priorComputeNanos+(started==0?0:(finishedAt==0?now:finishedAt)-started);
            double validationMs=validationNanos/1_000_000.0,totalMs=(now-createdAt)/1_000_000.0;
            String outcome=result.isCompletedExceptionally()?"failed":"slow";
            Throwable cause=error;
            if(cause==null && result.isCompletedExceptionally())try {result.join();}catch(CompletionException ex){cause=ex.getCause();}
            CraftRouteAddon.LOGGER.warn("[AE-Craftroute] route planning {}: target={} amount={} phase={} captureActiveMs={} captureWallMs={} workerQueueMs={} workerComputeMs={} validationMs={} totalMs={} cause={}",
                    outcome,key,amount,phase.label,captureActiveMs,captureWallMs,queueMs/1_000_000.0,computeMs/1_000_000.0,validationMs,totalMs,
                    cause==null?"none":cause.getClass().getSimpleName()+": "+cause.getMessage());
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
    /** A recaptured plan may prefer producing known-volatile intermediates instead
     * of borrowing their moving stock. The conservative candidate is independently
     * certified. Failure MUST fall back to the actual snapshot: masking stock is
     * not evidence of real missing material, absent seeds or an impossible route. */
    static CertifiedCraftingPlan planFrozenAvoidingUnstable(RouteSnapshot.Result snapshot,RoutePolicy<AEKey> policy,
            AEKey key,long amount,int states,long deadline,Set<AEKey> unstable) {
        var conservative=new LinkedHashMap<>(snapshot.stock());boolean changed=false;
        for(var k:unstable)if(conservative.getOrDefault(k,0L)>0 && !snapshot.patterns().getOrDefault(k,List.of()).isEmpty()) {
            conservative.put(k,0L);changed=true;
        }
        if(changed) {
            long allowance=Math.min(200_000_000L,RouteCanvasPlanner.remaining(deadline)/3);
            if(allowance>0)try {
                return planFrozen(new RouteSnapshot.Result(snapshot.patterns(),conservative,snapshot.identities()),policy,key,amount,
                        new ProductionPlanner.Limits(states,allowance));
            } catch(ProductionPlanningException rejected) {
                if(rejected.reason()!=UNREACHABLE && rejected.reason()!=UNSUPPORTED && rejected.reason()!=LIMIT)throw rejected;
            }
        }
        return planFrozen(snapshot,policy,key,amount,new ProductionPlanner.Limits(states,RouteCanvasPlanner.remaining(deadline)));
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
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {cancelJobs(JOBS);clearReuse();NetworkProductionPlanner.clearPlanningCaches();WORKERS.purge();}
    private RoutePlanningService() {}
}
