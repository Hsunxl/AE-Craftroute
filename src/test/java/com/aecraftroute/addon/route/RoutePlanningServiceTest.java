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
    @AfterEach void close() throws Exception {workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));}
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
        ICraftingService crafting=(ICraftingService)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ICraftingService.class},(proxy,method,args)->{
                    if(method.getName().equals("getCraftingFor"))return args[0]==target?List.of(plain):List.of();
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
        var error=assertThrows(ExecutionException.class,()->job.result.get(1,TimeUnit.SECONDS));
        var failure=assertInstanceOf(ProductionPlanningException.class,error.getCause());
        assertEquals(ProductionPlanningException.Reason.INVALID,failure.reason());
        assertTrue(failure.getMessage().contains("起手材料"));
    }
}
