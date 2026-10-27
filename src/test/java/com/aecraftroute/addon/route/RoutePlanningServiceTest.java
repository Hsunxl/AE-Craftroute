package com.aecraftroute.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.*;
import com.aecraftroute.addon.planner.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.*;

/** Drives the actual production Job.tick/tickJobs/cancelJobs and actual worker futures.
 * Only grid capture is injected; no parallel fake scheduler replaces the implementation. */
class RoutePlanningServiceTest {
    final Thread serverThread=Thread.currentThread();
    final ExecutorService workers=Executors.newFixedThreadPool(2);
    final BenchAEKey target=BenchAEKey.of("async_target"),ore=BenchAEKey.of("async_ore");
    final IPatternDetails plain=new BenchPatternDetails(target,1,List.of(of(ore,1)));
    final RoutePolicy<AEKey> policy=new RoutePolicy<>(Map.of());
    final ProductionPlanner.Limits limits=new ProductionPlanner.Limits(6000,5_000_000_000L);
    final AtomicInteger captures=new AtomicInteger(),validations=new AtomicInteger(),commits=new AtomicInteger();
    @AfterEach void close() throws Exception {RoutePlanningService.stopped(null);workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));}
    RoutePlanningService.CaptureWork capture(IPatternDetails pattern,boolean changed) {
        return new RoutePlanningService.CaptureWork() {
            int calls;
            public boolean advance() {assertSame(serverThread,Thread.currentThread());captures.incrementAndGet();return ++calls>=2;}
            public RouteSnapshot.Result result() {assertSame(serverThread,Thread.currentThread());return new RouteSnapshot.Result(Map.of(target,List.of(pattern)),Map.of(ore,1L));}
            public void validate(long deadline,long validationEnd,com.aecraftroute.addon.planner.CertifiedCraftingPlan plan) {
                assertSame(serverThread,Thread.currentThread());validations.incrementAndGet();
                if(changed)throw new ProductionPlanningException(ProductionPlanningException.Reason.INVALID,"network snapshot changed");
            }
        };
    }
    RoutePlanningService.Job job(IPatternDetails pattern,java.util.function.BooleanSupplier valid,boolean changed) {
        var job=new RoutePlanningService.Job(policy,target,1,limits,valid,workers,capture(pattern,changed));
        job.result.whenComplete((p,e)->{if(e==null)commits.incrementAndGet();});return job;
    }
    void tick(RoutePlanningService.Job job) {job.tick(System.nanoTime()+100_000_000L);}
    void finishWorker(RoutePlanningService.Job job) throws Exception {assertNotNull(job.worker);job.worker.get(3,TimeUnit.SECONDS);}
    IPatternDetails blocked(CountDownLatch entered,CountDownLatch release,CountDownLatch interrupted,boolean ignoreInterrupt) {
        var calls=new AtomicInteger();
        return (IPatternDetails)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IPatternDetails.class},(proxy,method,args)->{
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            if(method.getName().equals("getInputs") && calls.incrementAndGet()==1) {
                assertNotSame(serverThread,Thread.currentThread());entered.countDown();
                while(true)try {release.await();break;}catch(InterruptedException cancel) {
                    interrupted.countDown();if(!ignoreInterrupt)throw new CancellationException("worker interrupted");
                }
            }
            return method.invoke(plain,args);
        });
    }
    @Test void captureAndValidationStayOnServerThreadAndWorkerCannotCommitWithoutValidation() throws Exception {
        var job=job(plain,()->true,false);tick(job);finishWorker(job);
        assertFalse(job.result.isDone());assertEquals(0,commits.get());assertEquals(0,validations.get());
        tick(job);assertNotNull(job.result.get(1,TimeUnit.SECONDS));assertEquals(1,validations.get());assertEquals(1,commits.get());assertTrue(captures.get()>=2);
    }
    @Test void menuClosureCancelsActualInFlightWorkerAndDoesNotCommit() throws Exception {
        var menu=new AtomicReference<>(new Object());var expected=menu.get();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        var job=job(blocked(entered,release,interrupted,false),()->menu.get()==expected,false);tick(job);assertTrue(entered.await(3,TimeUnit.SECONDS));
        menu.set(new Object());tick(job);
        assertTrue(job.result.isCancelled());assertTrue(job.worker.isCancelled());assertTrue(interrupted.await(3,TimeUnit.SECONDS));assertEquals(0,commits.get());assertEquals(0,validations.get());
    }
    @Test void networkIdentityOrPolicyChangesDiscardCompletedButUncommittedWork() throws Exception {
        for(boolean networkChange:new boolean[]{true,false}) {
            var grid=new AtomicReference<>(new Object());var expected=grid.get();var currentPolicy=new AtomicReference<>(policy);
            var job=job(plain,()->grid.get()==expected && currentPolicy.get().equals(policy),false);
            tick(job);finishWorker(job);
            if(networkChange)grid.set(new Object());else currentPolicy.set(policy.change(ore,"stock",""));
            tick(job);assertTrue(job.result.isCancelled());
        }
        assertEquals(0,validations.get());assertEquals(0,commits.get());
    }
    @Test void changedAuthoritativeSnapshotIsNeverSubmittedEvenIfSearchWasFeasible() throws Exception {
        var job=job(plain,()->true,true);tick(job);finishWorker(job);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        assertInstanceOf(ProductionPlanningException.class,error.getCause());assertEquals(0,commits.get());assertEquals(1,validations.get());
    }
    @Test void serverStopCancelsRunningAndUncapturedJobsAndEmptiesRealTickQueue() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        var running=job(blocked(entered,release,interrupted,false),()->true,false);tick(running);assertTrue(entered.await(3,TimeUnit.SECONDS));
        var waiting=job(plain,()->true,false);var jobs=new ArrayList<>(List.of(running,waiting));
        RoutePlanningService.cancelJobs(jobs);
        assertTrue(jobs.isEmpty());assertTrue(running.result.isCancelled());assertTrue(waiting.result.isCancelled());assertTrue(interrupted.await(3,TimeUnit.SECONDS));
        RoutePlanningService.tickJobs(jobs,System.nanoTime()+100_000_000L);assertEquals(0,commits.get());assertEquals(0,validations.get());
    }
    @Test void serverStopShutsDownProductionPoolAndNextWorldGetsAFreshPool() {
        var first=liveJob(Map.of(ore,1L));
        var oldPool=assertInstanceOf(ThreadPoolExecutor.class,first.executor);
        assertFalse(oldPool.isShutdown());
        RoutePlanningService.stopped(null);
        assertTrue(oldPool.isShutdown());
        var second=liveJob(Map.of(ore,1L));
        assertNotSame(oldPool,second.executor);
        assertFalse(second.executor.isShutdown());
    }
    @Test void twoPreviewsOnlyLastLeaseCommitsEvenWhenOldWorkerIgnoresCancellation() throws Exception {
        var leases=new RoutePlanningRequests<String>();var first=leases.replace("p",1);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        try {
            var old=job(blocked(entered,release,interrupted,true),()->leases.current("p",first),false);leases.bind("p",first,old.result);
            tick(old);assertTrue(entered.await(3,TimeUnit.SECONDS));
            var second=leases.replace("p",2);var latest=job(plain,()->leases.current("p",second),false);leases.bind("p",second,latest.result);
            tick(latest);finishWorker(latest);tick(latest);assertEquals(1,commits.get());assertTrue(old.result.isCancelled());
            release.countDown();tick(old);assertEquals(1,commits.get());assertTrue(leases.current("p",second));
        } finally {release.countDown();}
    }
    @Test void disablingProxyWhileApplyIsInFlightInvalidatesItBeforeCommit() throws Exception {
        var enabled=new AtomicBoolean(true);var job=job(plain,enabled::get,false);
        tick(job);finishWorker(job);enabled.set(false);tick(job);
        assertTrue(job.result.isCancelled());assertEquals(0,commits.get());assertEquals(0,validations.get());
    }
    @Test void invalidSessionBeforeFirstTickDoesNotReadAnyGridOrStartAnyWorker() {
        var job=job(plain,()->false,false);tick(job);assertTrue(job.result.isCancelled());assertNull(job.worker);assertEquals(0,captures.get());
    }
    @Test void boundedWorkerRejectionIsLimitAndCannotSubmitAPartialPlan() {
        workers.shutdown();var job=job(plain,()->true,false);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        assertInstanceOf(ProductionPlanningException.class,error.getCause());
        assertEquals(ProductionPlanningException.Reason.LIMIT,((ProductionPlanningException)error.getCause()).reason());
        assertEquals(0,commits.get());assertEquals(0,validations.get());
    }
    @Test void actualProductionPoolRejectsFullQueueAndRecoversWithoutLosingAcceptedJobs() throws Exception {
        var pool=assertInstanceOf(ThreadPoolExecutor.class,liveJob(Map.of(ore,1L)).executor);
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);
        Runnable blocker=()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        pool.submit(blocker);pool.submit(blocker);
        assertTrue(started.await(3,TimeUnit.SECONDS));
        var queued=new ArrayList<RoutePlanningService.Job>();
        try {
            assertEquals(2,pool.getMaximumPoolSize());
            assertEquals(8,pool.getQueue().remainingCapacity());
            for(int i=0;i<8;i++) {
                var accepted=new RoutePlanningService.Job(policy,target,1,limits,()->true,pool,capture(plain,false));
                queued.add(accepted);tick(accepted);
                assertNotNull(accepted.worker);assertFalse(accepted.result.isDone());
            }
            assertEquals(8,pool.getQueue().size());
            var rejected=new RoutePlanningService.Job(policy,target,1,limits,()->true,pool,capture(plain,false));
            tick(rejected);
            var error=assertThrows(ExecutionException.class,()->rejected.result.get(1,TimeUnit.SECONDS));
            var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
            assertEquals(ProductionPlanningException.Reason.LIMIT,failure.reason());
            assertTrue(failure.getMessage().contains("不是缺料"));assertNull(rejected.worker);
            assertEquals(0,validations.get());assertFalse(pool.isShutdown());
            release.countDown();
            for(var accepted:queued) {finishWorker(accepted);tick(accepted);assertNotNull(accepted.result.get(1,TimeUnit.SECONDS));}
            assertEquals(8,validations.get());
            var recovered=new RoutePlanningService.Job(policy,target,1,limits,()->true,pool,capture(plain,false));
            tick(recovered);finishWorker(recovered);tick(recovered);
            assertNotNull(recovered.result.get(1,TimeUnit.SECONDS));assertEquals(9,validations.get());
        } finally {release.countDown();}
    }
    @Test void expiredEndToEndBudgetDoesNotStartCaptureOrBecomeMissingMaterials() {
        var job=new RoutePlanningService.Job(policy,target,1,new ProductionPlanner.Limits(6000,1),()->true,workers,capture(plain,false));
        tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        assertEquals(ProductionPlanningException.Reason.LIMIT,((ProductionPlanningException)error.getCause()).reason());
        assertNull(job.worker);assertEquals(0,captures.get());assertEquals(0,commits.get());
    }
    @Test void limitFailureDuringCaptureKeepsReasonAndAddsCapturePhase() {
        RoutePlanningService.CaptureWork failingCapture=new RoutePlanningService.CaptureWork() {
            public boolean advance(){throw new ProductionPlanningException(ProductionPlanningException.Reason.LIMIT,"capture budget exhausted");}
            public RouteSnapshot.Result result(){throw new AssertionError("capture did not complete");}
            public void validate(long deadline,long validationEnd,com.aecraftroute.addon.planner.CertifiedCraftingPlan plan){throw new AssertionError("must not validate");}
        };
        var job=new RoutePlanningService.Job(policy,target,1,limits,()->true,workers,failingCapture);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
        assertEquals(ProductionPlanningException.Reason.LIMIT,failure.reason());
        assertTrue(failure.getMessage().startsWith("capture budget exhausted"));assertTrue(failure.getMessage().contains("阶段=capture"));
    }
    @Test void limitFailureDuringValidationKeepsReasonAndAddsValidationPhase() throws Exception {
        RoutePlanningService.CaptureWork failingValidation=new RoutePlanningService.CaptureWork() {
            public boolean advance(){return true;}
            public RouteSnapshot.Result result(){return new RouteSnapshot.Result(Map.of(target,List.of(plain)),Map.of(ore,1L));}
            public void validate(long deadline,long validationEnd,com.aecraftroute.addon.planner.CertifiedCraftingPlan plan){throw new ProductionPlanningException(ProductionPlanningException.Reason.LIMIT,"validation budget exhausted");}
        };
        var job=new RoutePlanningService.Job(policy,target,1,limits,()->true,workers,failingValidation);
        tick(job);finishWorker(job);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
        assertEquals(ProductionPlanningException.Reason.LIMIT,failure.reason());
        assertTrue(failure.getMessage().startsWith("validation budget exhausted"));assertTrue(failure.getMessage().contains("阶段=validation"));
        assertInstanceOf(ProductionPlanningException.class,failure.getCause());
    }
    @Test void workerQueueBudgetFailureIsAttributedToQueueAndKeepsOriginalCause() throws Exception {
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);
        Runnable blocker=()->{started.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}};
        workers.submit(blocker);workers.submit(blocker);assertTrue(started.await(3,TimeUnit.SECONDS));
        try {
            var job=new RoutePlanningService.Job(policy,target,1,new ProductionPlanner.Limits(6000,100_000_000L),()->true,workers,capture(plain,false));
            tick(job);assertNotNull(job.worker);assertFalse(job.worker.isDone());
            Thread.sleep(120);
            release.countDown();
            job.worker.get(3,TimeUnit.SECONDS);tick(job);
            var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
            var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
            assertEquals(ProductionPlanningException.Reason.LIMIT,failure.reason());
            assertTrue(failure.getMessage().contains("阶段=worker queue"));
            assertInstanceOf(ProductionPlanningException.class,failure.getCause());
        } finally {release.countDown();}
    }
    RoutePlanningService.CaptureWork sliceLimitedCapture(AtomicInteger checks,boolean alwaysExpire,AtomicBoolean changed) {
        return new RoutePlanningService.CaptureWork() {
            public boolean advance(){return true;}
            public RouteSnapshot.Result result(){return new RouteSnapshot.Result(Map.of(target,List.of(plain)),Map.of(ore,1L));}
            public void validate(long deadline,long validationEnd,CertifiedCraftingPlan plan) {
                assertSame(serverThread,Thread.currentThread());
                int attempt=checks.incrementAndGet();
                if(changed.get())throw new ProductionPlanningException(ProductionPlanningException.Reason.INVALID,"changed before retry");
                RoutePlanningService.checkValidationBudget(deadline,alwaysExpire || attempt==1 ? System.nanoTime()-1 : validationEnd);
            }
        };
    }
    @Test void validationSliceRetryRestartsWholeCheckWithoutExtendingDeadlineOrReplanning() throws Exception {
        var checks=new AtomicInteger();
        var job=new RoutePlanningService.Job(policy,target,1,limits,()->true,workers,sliceLimitedCapture(checks,false,new AtomicBoolean()));
        long deadline=job.deadline;tick(job);finishWorker(job);var worker=job.worker;tick(job);
        assertFalse(job.result.isDone());assertEquals(1,checks.get());
        tick(job);assertNotNull(job.result.get(1,TimeUnit.SECONDS));assertEquals(2,checks.get());
        assertEquals(deadline,job.deadline);assertSame(worker,job.worker);
    }
    @Test void validationRetryRejectsChangedStateInsteadOfCommittingPreviouslyCheckedPrefix() throws Exception {
        var checks=new AtomicInteger();var changed=new AtomicBoolean();
        var job=new RoutePlanningService.Job(policy,target,1,limits,()->true,workers,sliceLimitedCapture(checks,false,changed));
        tick(job);finishWorker(job);tick(job);assertFalse(job.result.isDone());
        changed.set(true);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        assertEquals(ProductionPlanningException.Reason.INVALID,assertInstanceOf(ProductionPlanningException.class,error.getCause()).reason());
        assertEquals(2,checks.get());
    }
    @Test void repeatedValidationSliceExpiryIsBoundedAndDoesNotBecomeMissingMaterials() throws Exception {
        var checks=new AtomicInteger();
        var job=new RoutePlanningService.Job(policy,target,1,limits,()->true,workers,sliceLimitedCapture(checks,true,new AtomicBoolean()));
        tick(job);finishWorker(job);
        tick(job);tick(job);assertFalse(job.result.isDone());tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
        assertEquals(ProductionPlanningException.Reason.LIMIT,failure.reason());
        assertTrue(failure.getMessage().contains("阶段=validation"));assertEquals(3,checks.get());
    }
    @Test void workerComputationLimitFailureIsAttributedAndRetainsOriginalFailure() throws Exception {
        var original=new ProductionPlanningException(ProductionPlanningException.Reason.LIMIT,"worker budget exhausted");
        IPatternDetails failingPattern=(IPatternDetails)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IPatternDetails.class},(proxy,method,args)->{
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            if(method.getName().equals("getInputs"))throw original;
            return method.invoke(plain,args);
        });
        var job=job(failingPattern,()->true,false);tick(job);
        job.worker.get(3,TimeUnit.SECONDS);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
        assertEquals(ProductionPlanningException.Reason.LIMIT,failure.reason());
        assertTrue(failure.getMessage().contains("阶段=worker computation"));assertSame(original,failure.getCause());
    }
    @Test void nonLimitFailureIsPropagatedAsTheSameObject() {
        var original=new IllegalStateException("capture failed");
        RoutePlanningService.CaptureWork failingCapture=new RoutePlanningService.CaptureWork() {
            public boolean advance(){throw original;}
            public RouteSnapshot.Result result(){throw new AssertionError("capture did not complete");}
            public void validate(long deadline,long validationEnd,com.aecraftroute.addon.planner.CertifiedCraftingPlan plan){throw new AssertionError("must not validate");}
        };
        var job=new RoutePlanningService.Job(policy,target,1,limits,()->true,workers,failingCapture);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        assertSame(original,error.getCause());
    }

    private RoutePlanningService.Job liveJob(Map<BenchAEKey,Long> stock) {
        return liveJob(stock,new HashMap<>(Map.of(target,List.of(plain))));
    }
    private RoutePlanningService.Job liveJob(Map<BenchAEKey,Long> stock,Map<AEKey,List<IPatternDetails>> patterns) {
        ICraftingService crafting=(ICraftingService)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ICraftingService.class},(proxy,method,args)->{
                    if(method.getName().equals("getCraftingFor"))return patterns.getOrDefault(args[0],List.of());
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==args[0];
                    if(method.getName().equals("toString"))return "live-test-crafting";
                    Class<?> type=method.getReturnType();
                    if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;
                    return null;
                });
        var base=new FakeBenchGrid(stock);
        IGrid grid=(IGrid)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IGrid.class},(proxy,method,args)->{
            if(method.getName().equals("getCraftingService"))return crafting;
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            return method.invoke(base,args);
        });
        return new RoutePlanningService.Job(grid,policy,null,target,1,limits,()->true);
    }
    @Test void stockMovementOutsideRequiredStartupCapitalDoesNotInvalidateAProvenPlan() throws Exception {
        var stock=new HashMap<BenchAEKey,Long>();stock.put(ore,1L);
        var job=liveJob(stock);tick(job);finishWorker(job);
        stock.put(ore,2L);stock.put(target,99L); // More stock and unrelated output stock are both harmless.
        tick(job);assertNotNull(job.result.get(1,TimeUnit.SECONDS));
    }
    @Test void missingRequiredStartupCapitalStillRejectsThePlanBeforeSubmit() throws Exception {
        var stock=new HashMap<BenchAEKey,Long>();stock.put(ore,1L);
        var job=liveJob(stock);tick(job);finishWorker(job);stock.put(ore,0L);tick(job);
        assertEquals(1,job.stockReplans);assertFalse(job.result.isDone());assertNull(job.plan);
        tick(job);finishWorker(job);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,failure.reason());
        assertNull(RoutePlanningService.reuse(job.gridRef,policy,target,1));
    }
    @Test void movingCraftableStockIsRecapturedAndProducedFromUpstreamBeforeCommit() throws Exception {
        var mid=BenchAEKey.of("moving_mid");
        var make=new BenchPatternDetails(mid,1,List.of(of(ore,1)));
        var finish=new BenchPatternDetails(target,1,List.of(of(mid,2)));
        var stock=new HashMap<>(Map.of(ore,100L,mid,2L));
        var job=liveJob(stock,new HashMap<>(Map.of(target,List.of(finish),mid,List.of(make))));
        tick(job);finishWorker(job);assertEquals(2,job.plan.usedItems().get(mid));
        long originalDeadline=job.deadline;var stale=job.plan;
        stock.put(mid,1L);tick(job);
        assertFalse(job.result.isDone());assertEquals(1,job.stockReplans);assertNull(job.plan);
        tick(job);finishWorker(job);
        assertEquals(0,job.plan.usedItems().get(mid));assertEquals(2,job.plan.usedItems().get(ore));
        stock.put(mid,0L);tick(job);var fresh=job.result.get(1,TimeUnit.SECONDS);
        assertNotSame(stale,fresh);assertEquals(originalDeadline,job.deadline);
        assertEquals(100,stock.get(ore)); // planning/retry never reserves or extracts items
        assertSame(fresh,RoutePlanningService.reuse(job.gridRef,policy,target,1));
    }
    @Test void twoDifferentMovingIntermediatesAreAvoidedWithoutMaskingStableRawStock() throws Exception {
        var mid=BenchAEKey.of("moving_middle");var lower=BenchAEKey.of("moving_lower");
        var stock=new HashMap<>(Map.of(ore,100L,lower,2L,mid,1L));
        var patterns=new HashMap<AEKey,List<IPatternDetails>>(Map.of(
                target,List.of(new BenchPatternDetails(target,1,List.of(of(mid,1)))),
                mid,List.of(new BenchPatternDetails(mid,1,List.of(of(lower,2)))),
                lower,List.of(new BenchPatternDetails(lower,1,List.of(of(ore,1))))));
        var job=liveJob(stock,patterns);tick(job);finishWorker(job);stock.put(mid,0L);tick(job);
        tick(job);finishWorker(job);assertEquals(2,job.plan.usedItems().get(lower));
        stock.put(lower,1L);tick(job);assertEquals(2,job.stockReplans);
        tick(job);finishWorker(job);stock.put(lower,0L);tick(job);
        var plan=job.result.get(1,TimeUnit.SECONDS);assertEquals(2,plan.usedItems().get(ore));
        assertEquals(0,plan.usedItems().get(mid));assertEquals(0,plan.usedItems().get(lower));
    }
    @Test void conservativeMaskFailureFallsBackToRealStockInsteadOfClaimingMissingMaterials() {
        var mid=BenchAEKey.of("only_available_mid");
        var snapshot=new RouteSnapshot.Result(Map.of(target,List.of(new BenchPatternDetails(target,1,List.of(of(mid,2)))),
                mid,List.of(new BenchPatternDetails(mid,1,List.of(of(ore,1))))),Map.of(mid,2L));
        var plan=RoutePlanningService.planFrozenAvoidingUnstable(snapshot,policy,target,1,6000,System.nanoTime()+5_000_000_000L,Set.of(mid));
        assertEquals(2,plan.usedItems().get(mid));assertEquals(0,plan.usedItems().get(ore));
    }
    @Test void aReturnedSeedIsNotInventedOrLostWhenConservativeMaskHasNoBootstrap() {
        var feed=BenchAEKey.of("seed_feed");
        var grow=new BenchPatternDetails(target,2,List.of(of(target,1),of(feed,1)));
        var snapshot=new RouteSnapshot.Result(Map.of(target,List.of(grow)),Map.of(target,1L,feed,1L));
        var plan=RoutePlanningService.planFrozenAvoidingUnstable(snapshot,policy,target,1,6000,System.nanoTime()+5_000_000_000L,Set.of(target));
        assertEquals(1,plan.usedItems().get(target));assertEquals(1,plan.usedItems().get(feed));
    }
    @Test void continuouslyChangingStockHasABoundedRetryCountAndDetailedFinalDeficit() throws Exception {
        var mid=BenchAEKey.of("unstable_without_raw");var stock=new HashMap<>(Map.of(mid,2L));
        var patterns=new HashMap<AEKey,List<IPatternDetails>>(Map.of(
                target,List.of(new BenchPatternDetails(target,1,List.of(of(mid,2)))),
                mid,List.of(new BenchPatternDetails(mid,1,List.of(of(ore,1))))));
        var job=liveJob(stock,patterns);long deadline=job.deadline;
        for(int attempt=0;attempt<3;attempt++) {
            stock.put(mid,2L);tick(job);finishWorker(job);stock.put(mid,1L);tick(job);
            assertEquals(deadline,job.deadline);
            if(attempt<2)assertFalse(job.result.isDone());
        }
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
        assertEquals(ProductionPlanningException.Reason.INVALID,failure.reason());assertEquals(2,job.stockReplans);
        assertEquals(mid,failure.stockDeficit().key());assertEquals(2,failure.stockDeficit().needed());assertEquals(1,failure.stockDeficit().available());
        assertTrue(failure.getMessage().contains("需要 2"));assertTrue(failure.getMessage().contains("当前 1"));
        assertNull(RoutePlanningService.reuse(job.gridRef,policy,target,1));
    }
    @Test void closureOrStopBetweenRecaptureAndRetryCannotCommitAnOldPlan() throws Exception {
        for(boolean stop:new boolean[]{false,true}) {
            var valid=new AtomicBoolean(true);var refreshes=new AtomicInteger();
            RoutePlanningService.CaptureWork source=new RoutePlanningService.CaptureWork() {
                public boolean advance(){return true;}
                public RouteSnapshot.Result result(){return new RouteSnapshot.Result(Map.of(target,List.of(plain)),Map.of(ore,1L));}
                public void validate(long d,long v,CertifiedCraftingPlan p){throw ProductionPlanningException.startupStockChanged(ore,1,0);}
                public boolean refresh(AEKey key){refreshes.incrementAndGet();return true;}
            };
            var job=new RoutePlanningService.Job(policy,target,1,limits,valid::get,workers,source);
            tick(job);finishWorker(job);tick(job);assertEquals(1,refreshes.get());assertNull(job.worker);
            if(stop)RoutePlanningService.cancelJobs(new ArrayList<>(List.of(job)));else{valid.set(false);tick(job);}
            assertTrue(job.result.isCancelled());assertNull(job.worker);
        }
    }
    @Test void recaptureDoesNotRestartTheOriginalEndToEndDeadline() throws Exception {
        var refreshes=new AtomicInteger();
        RoutePlanningService.CaptureWork source=new RoutePlanningService.CaptureWork() {
            public boolean advance(){return true;}
            public RouteSnapshot.Result result(){return new RouteSnapshot.Result(Map.of(target,List.of(plain)),Map.of(ore,1L));}
            public void validate(long d,long v,CertifiedCraftingPlan p){throw ProductionPlanningException.startupStockChanged(ore,1,0);}
            public boolean refresh(AEKey key){refreshes.incrementAndGet();return true;}
        };
        var job=new RoutePlanningService.Job(policy,target,1,new ProductionPlanner.Limits(6000,500_000_000L),()->true,workers,source);
        long deadline=job.deadline;tick(job);finishWorker(job);tick(job);assertEquals(1,refreshes.get());
        Thread.sleep(510);tick(job);
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        assertEquals(ProductionPlanningException.Reason.LIMIT,((ProductionPlanningException)error.getCause()).reason());
        assertEquals(deadline,job.deadline);assertEquals(1,refreshes.get());assertNull(job.worker);
    }
    private CertifiedCraftingPlan complete(RoutePlanningService.Job job) throws Exception {
        tick(job);finishWorker(job);
        for(int i=0;i<3 && !job.result.isDone();i++)tick(job);
        return job.result.get(1,TimeUnit.SECONDS);
    }
    private RoutePlanningService.Job repeat(RoutePlanningService.Job first) {
        return new RoutePlanningService.Job(first.gridRef,policy,null,target,1,limits,()->true);
    }
    @Test void repeatedRequestReusesCertifiedProgramWithoutStartingWorker() throws Exception {
        var first=liveJob(new HashMap<>(Map.of(ore,2L)));var plan=complete(first);
        var again=repeat(first);tick(again);
        assertSame(plan,again.result.get(1,TimeUnit.SECONDS));assertNull(again.worker);
    }
    @Test void insufficientLeafStockFallsBackToCaptureInsteadOfReusingPlan() throws Exception {
        var stock=new HashMap<>(Map.of(ore,1L));var first=liveJob(stock);complete(first);
        stock.put(ore,0L);var again=repeat(first);tick(again);
        assertNotNull(again.worker);assertFalse(again.result.isDone());
        assertNull(RoutePlanningService.reuse(first.gridRef,policy,target,1));
    }
    @Test void newlyCraftableStartupCapitalRejectsFastPath() throws Exception {
        var patterns=new HashMap<AEKey,List<IPatternDetails>>(Map.of(target,List.of(plain)));
        var first=liveJob(new HashMap<>(Map.of(ore,1L)),patterns);var plan=complete(first);
        patterns.put(ore,List.of(new BenchPatternDetails(ore,1,List.of(of(target,1)))));
        assertFalse(RoutePlanningService.stillExecutable(first.gridRef,plan,Long.MAX_VALUE));
        var again=repeat(first);tick(again);assertNotNull(again.worker);
    }
    @Test void removedOrReplacedPatternRejectsCachedProgram() throws Exception {
        var patterns=new HashMap<AEKey,List<IPatternDetails>>(Map.of(target,List.of(plain)));
        var first=liveJob(new HashMap<>(Map.of(ore,1L)),patterns);var plan=complete(first);
        patterns.remove(target);
        assertFalse(RoutePlanningService.stillExecutable(first.gridRef,plan,Long.MAX_VALUE));
        patterns.put(target,List.of(new BenchPatternDetails(target,1,List.of(of(ore,1)))));
        assertFalse(RoutePlanningService.stillExecutable(first.gridRef,plan,Long.MAX_VALUE));
    }
    @Test void expiredFastPathSliceFallsBackWithoutAcceptingCachedPlan() throws Exception {
        var first=liveJob(new HashMap<>(Map.of(ore,1L)));var plan=complete(first);
        assertFalse(RoutePlanningService.stillExecutable(first.gridRef,plan,System.nanoTime()-1));
        var again=repeat(first);again.tick(System.nanoTime()-1);assertFalse(again.result.isDone());
        tick(again);finishWorker(again);tick(again);assertNotNull(again.result.get(1,TimeUnit.SECONDS));
    }
    @Test void reuseCacheIsBoundedAndEvictsLeastRecentlyUsedRequest() throws Exception {
        var first=liveJob(new HashMap<>(Map.of(ore,1L)));var plan=complete(first);
        for(long amount=2;amount<=64;amount++)RoutePlanningService.remember(first.gridRef,policy,target,amount,plan);
        assertSame(plan,RoutePlanningService.reuse(first.gridRef,policy,target,1));
        RoutePlanningService.remember(first.gridRef,policy,target,65,plan);
        assertSame(plan,RoutePlanningService.reuse(first.gridRef,policy,target,1));
        assertNull(RoutePlanningService.reuse(first.gridRef,policy,target,2));
        assertSame(plan,RoutePlanningService.reuse(first.gridRef,policy,target,65));
    }
    @Test void cacheIsIsolatedByGridPolicyAmountAndClearedOnStop() throws Exception {
        var first=liveJob(new HashMap<>(Map.of(ore,1L)));complete(first);
        var other=liveJob(new HashMap<>(Map.of(ore,1L)));
        assertNull(RoutePlanningService.reuse(other.gridRef,policy,target,1));
        assertNull(RoutePlanningService.reuse(first.gridRef,policy.change(ore,"stock",""),target,1));
        assertNull(RoutePlanningService.reuse(first.gridRef,policy,target,2));
        RoutePlanningService.stopped(null);
        assertNull(RoutePlanningService.reuse(first.gridRef,policy,target,1));
    }
}
