package com.aecraftroute.addon.planner;

import com.aecraftroute.addon.vm.NumericCraftingVM;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Backward coverability for a finite, exact, monotone production net.
 * A marking is inventory, not a dependency-tree path. Every answer carries an
 * executable witness; limits are UNKNOWN, never missing ingredients. No item IDs,
 * recipe topology or SCC size is special-cased.
 */
public final class ProductionPlanner<K, R> {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE,
            MAX_COUNT = BigInteger.valueOf(Long.MAX_VALUE);
    public enum Status { FEASIBLE, UNREACHABLE, LIMIT }
    public record Recipe<K,R>(R id, Map<K,Long> inputs, Map<K,Long> outputs) {
        public Recipe {
            Objects.requireNonNull(id);
            inputs = positive(inputs); outputs = positive(outputs);
            if (outputs.isEmpty()) throw new IllegalArgumentException("Recipe has no outputs");
        }
        private static <K> Map<K,Long> positive(Map<K,Long> values) {
            values.forEach((k,v) -> { if (k == null || v == null || v <= 0) throw new IllegalArgumentException("Nonpositive quantity"); });
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
    public record Step<R>(R recipe, long count) {
        public Step { if (recipe == null || count <= 0) throw new IllegalArgumentException("Invalid step"); }
    }
    /** A straight-line word repeated without expanding large orders into objects. */
    public record Block<R>(List<Step<R>> word, long repetitions) {
        public Block { word = List.copyOf(word); if (word.isEmpty() || repetitions <= 0) throw new IllegalArgumentException("Invalid block"); }
    }
    public record Result<K,R>(Status status, List<Block<R>> program, Map<K,Long> used,
                              Map<R,Long> counts, int explored,BigInteger maximumAdditionalTarget) {
        public Result(Status status,List<Block<R>> program,Map<K,Long> used,Map<R,Long> counts,int explored) {
            this(status,program,used,counts,explored,null);
        }
    }
    public record Limits(int states, long nanos) {
        public Limits { if (states <= 0 || nanos <= 0) throw new IllegalArgumentException("Invalid limits"); }
        public static Limits defaults() { return new Limits(30_000, 3_000_000_000L); }
    }
    private final List<K> keys;
    private final Map<K,Integer> keyIndexes=new HashMap<>();
    private final List<Recipe<K,R>> recipes;
    private final Map<R,Integer> recipeIds = new HashMap<>();
    private final SparseAmounts[] inputs, outputs, deltas;
    private final int[][] outputKeyIndices;
    private final int[][] touchedKeyIndices;
    private final BigInteger[] stock;
    private final boolean[] produced;
    private final Limits limits;
    private final boolean allowSccCandidate;
    private long started, serial;
    private boolean clipped;
    private CompiledProductionGraph compiled;
    private boolean dagFastPath;
    boolean dagFastPathUsed(){return dagFastPath;}
    private boolean resourceBound;
    private boolean sccFastPath;
    boolean sccFastPathUsed(){return sccFastPath;}
    boolean resourceBoundUsed(){return resourceBound;}
    private final Deque<Node> fair = new ArrayDeque<>();
    private final class Node {
        final BigInteger[] need;
        final Node next;
        final Block<R> block;
        final double score;
        final long order = serial++;
        boolean active = true;
        boolean expanded;
        Node(BigInteger[] n, Node p, Block<R> b) { need=n; next=p; block=b; score=score(n); }
    }
    public ProductionPlanner(Collection<Recipe<K,R>> source, Map<K,Long> available, Limits limits) {
        this(source,available,limits,true);
    }
    ProductionPlanner(Collection<Recipe<K,R>> source, Map<K,Long> available, Limits limits, boolean allowSccCandidate) {
        this.allowSccCandidate=allowSccCandidate;
        this.recipes = List.copyOf(source); this.limits = limits;
        LinkedHashSet<K> all = new LinkedHashSet<>(available.keySet());
        for (var r : recipes) { all.addAll(r.inputs.keySet()); all.addAll(r.outputs.keySet()); }
        keys = List.copyOf(all); for(int i=0;i<keys.size();i++)keyIndexes.put(keys.get(i),i); stock = vector(); produced = new boolean[keys.size()];
        inputs = new SparseAmounts[recipes.size()]; outputs = new SparseAmounts[recipes.size()];
        deltas = new SparseAmounts[recipes.size()];
        outputKeyIndices = new int[recipes.size()][];
        touchedKeyIndices = new int[recipes.size()][];
        for (int i=0;i<keys.size();i++) {
            long n=available.getOrDefault(keys.get(i),0L);
            if (n<0) throw new IllegalArgumentException("Negative stock");
            stock[i]=BigInteger.valueOf(n);
        }
        for (int r=0;r<recipes.size();r++) {
            if (recipeIds.put(recipes.get(r).id,r)!=null) throw new IllegalArgumentException("Duplicate recipe id");
            inputs[r]=sparse(recipes.get(r).inputs); outputs[r]=sparse(recipes.get(r).outputs);
            outputKeyIndices[r]=outputs[r].keys.clone();
            touchedKeyIndices[r]=java.util.stream.IntStream.concat(
                    Arrays.stream(inputs[r].keys),Arrays.stream(outputs[r].keys)).distinct().sorted().toArray();
            deltas[r]=SparseAmounts.difference(outputs[r],inputs[r]);
            for(int k:outputKeyIndices[r]) produced[k]=true;
        }
    }
    private BigInteger[] vector() { var v=new BigInteger[keys.size()]; Arrays.fill(v,ZERO); return v; }
    private BigInteger[] vector(Map<K,Long> map) {
        var v=vector(); for(int k=0;k<keys.size();k++) v[k]=BigInteger.valueOf(map.getOrDefault(keys.get(k),0L)); return v;
    }
    private boolean le(BigInteger[] a, BigInteger[] b) {
        for(int k=0;k<keys.size();k++) if(a[k].compareTo(b[k])>0) return false; return true;
    }
    private double score(BigInteger[] need) {
        double s=0;
        for(int k=0;k<keys.size();k++) if(need[k].compareTo(stock[k])>0)
            s += 1 + Math.log1p(need[k].subtract(stock[k]).doubleValue());
        return s;
    }
    private boolean expired() {
        if(Thread.currentThread().isInterrupted()) throw new CancellationException("Production planning cancelled");
        return System.nanoTime()-started >= limits.nanos;
    }
    /** pre(t,m) = input(t) + max(0, m-output(t)). */
    private BigInteger[] pre(BigInteger[] goal, BigInteger[] required, BigInteger[] delta, long times) {
        BigInteger n=BigInteger.valueOf(times), before=n.subtract(ONE);
        var result=vector();
        for(int k=0;k<keys.size();k++) result[k]=required[k].add(delta[k].negate().max(ZERO).multiply(before))
                .max(goal[k].subtract(delta[k].multiply(n))).max(ZERO);
        return result;
    }
    private SparseAmounts delta(int r) {
        return deltas[r];
    }
    private SparseAmounts sparse(Map<K,Long> amounts) {
        var values=new HashMap<Integer,BigInteger>();
        amounts.forEach((k,v)->values.put(keyIndexes.get(k),BigInteger.valueOf(v)));
        return new SparseAmounts(values);
    }
    private long jump(BigInteger[] goal,SparseAmounts d) {
        BigInteger count=ONE;
        for(int k:d.keys) if(d.get(k).signum()>0 && goal[k].compareTo(stock[k])>0) {
            var n=goal[k].subtract(stock[k]);count=count.max(n.add(d.get(k)).subtract(ONE).divide(d.get(k)));
        }
        if(count.compareTo(MAX_COUNT)>0) {clipped=true;return 1;}
        return count.longValueExact();
    }
    private long jump(BigInteger[] goal, BigInteger[] d) {
        BigInteger count=ONE;
        for(int k=0;k<keys.size();k++) if(d[k].signum()>0 && goal[k].compareTo(stock[k])>0) {
            var n=goal[k].subtract(stock[k]); count=count.max(n.add(d[k]).subtract(ONE).divide(d[k]));
        }
        if(count.compareTo(MAX_COUNT)>0) { clipped=true; return 1; }
        return count.longValueExact();
    }
    private final class Probe {
        int explored;
        Result<K,R> feasible;
    }
    private boolean probeExpired(long allowance) {
        // Both clocks start at solve(), so preprocessing and the probe never reset
        // the caller's budget. Cancellation must also propagate from heuristic work.
        return expired() || System.nanoTime()-started>=allowance;
    }
    private CompiledProductionGraph compiled(long allowance) {
        if(compiled!=null)return compiled;
        var indices=new HashMap<K,Integer>();for(int k=0;k<keys.size();k++)indices.put(keys.get(k),k);
        var in=new int[recipes.size()][];var out=new int[recipes.size()][];
        var a=new long[recipes.size()][];var b=new long[recipes.size()][];
        for(int r=0;r<recipes.size();r++) {
            if(probeExpired(allowance))return null;
            in[r]=new int[recipes.get(r).inputs.size()];a[r]=new long[in[r].length];int i=0;
            for(var entry:recipes.get(r).inputs.entrySet()){in[r][i]=indices.get(entry.getKey());a[r][i++]=entry.getValue();}
            out[r]=new int[recipes.get(r).outputs.size()];b[r]=new long[out[r].length];i=0;
            for(var entry:recipes.get(r).outputs.entrySet()){out[r][i]=indices.get(entry.getKey());b[r][i++]=entry.getValue();}
        }
        compiled=CompiledProductionGraph.compile(keys.size(),in,a,out,b,()->probeExpired(allowance));return compiled;
    }
    private Result<K,R> dag(int target,BigInteger[] goal,long increment) {
        if(limits.states<64)return null;
        long allowance=Math.min(200_000_000L,limits.nanos/8);
        var graph=compiled(allowance);if(graph==null)return null;
        var candidate=DagBatchPlanner.candidate(graph,stock,target,increment,()->probeExpired(allowance));
        if(candidate==null || probeExpired(allowance))return null;
        Node witness=new Node(goal,null,null);
        var steps=candidate.steps();
        for(int i=steps.size()-1;i>=0;i--) {
            if(probeExpired(allowance))return null;
            var step=steps.get(i);witness=new Node(goal,witness,new Block<>(List.of(new Step<>(recipes.get(step.recipe()).id,step.count())),1));
        }
        try {
            // No DAG shortcut may bypass the SAME prefix-capital/forward proof.
            var result=certify(witness,target,increment,candidate.visited());
            if(!expired() && result.status==Status.FEASIBLE){dagFastPath=true;return result;}
        } catch(CancellationException canceled){throw canceled;}
        catch(IllegalStateException rejected){/* Incomplete heuristic, never a missing-material verdict. */}
        return null;
    }
    /** A bounded constructive hint, not a reachability decision or a pruning rule. */
    private Probe probe(int target, BigInteger[] goal, long increment) {
        var result=new Probe();
        if(limits.states<64) return result;
        long allowance=Math.min(200_000_000L,limits.nanos/8);
        // Leave longer witnesses to backward search, which can prove repeated
        // words as macros instead of retaining a large heuristic straight line.
        // Aggregation passes (below) converge in O(chain depth) passes, so the cap
        // only bounds pathological cyclic nets, not ordinary large quantities.
        int batches=Math.min(4096,Math.max(64,limits.states/4));
        var distances=new int[keys.size()]; Arrays.fill(distances,Integer.MAX_VALUE);
        var recipeDistances=new int[recipes.size()]; Arrays.fill(recipeDistances,Integer.MAX_VALUE);
        var keyOrder=new ArrayList<Integer>();
        var graph=compiled(allowance);if(graph==null)return result;
        // Do not let impossible, unseeded alternatives create phantom demand
        // and divert the stock needed by a viable ring. Presence is optimistic:
        // reachable does NOT mean enough stock, nor an executable plan.
        var reachable=SeedReachability.reachableRecipes(graph,stock,()->probeExpired(allowance));
        if(reachable==null)return result;
        var inputIndices=graph.inputKeys;var outputIndices=graph.outputKeys;
        var producers=graph.producers;var numericProgram=graph.bytecode;
        var pending=new ArrayDeque<Integer>(); pending.add(target); distances[target]=0;
        // Discover topology once, independently of current quantities, including
        // ALL alternative producers. Every relevant input key is visited once.
        while(!pending.isEmpty()) {
            if(probeExpired(allowance)) return result;
            int key=pending.remove();
            keyOrder.add(key);
            for(int r:producers[key]) {
                if(probeExpired(allowance)) return result;
                if(!reachable[r])continue;
                recipeDistances[r]=Math.min(recipeDistances[r],distances[key]);
                for(int k:inputIndices[r]) {
                    if(probeExpired(allowance)) return result;
                    if(distances[k]==Integer.MAX_VALUE) {
                        distances[k]=distances[key]+1; pending.add(k);
                    }
                }
            }
        }
        var candidates=new ArrayList<Integer>();
        for(int r=0;r<recipes.size();r++) {
            if(probeExpired(allowance)) return result;
            if(reachable[r] && recipeDistances[r]!=Integer.MAX_VALUE) candidates.add(r);
        }
        candidates.sort(Comparator.comparingInt((Integer r)->recipeDistances[r]).thenComparingInt(r->r));
        var actual=new long[stock.length];
        for(int k=0;k<stock.length;k++) {
            if(probeExpired(allowance)) return result;
            actual[k]=stock[k].longValueExact();
        }
        var blocks=new ArrayList<Block<R>>();
        int captureStart=0;
        long captureTarget=actual[target],lowTarget=actual[target];
        int lowStart=0;
        while(result.explored<batches) {
            if(probeExpired(allowance)) return result;
            if(BigInteger.valueOf(actual[target]).compareTo(goal[target])>=0) break;
            var hints=vector(); hints[target]=goal[target];
            // Recompute demand from ACTUAL deficits before every batch. Existing
            // target inventory and partially completed upstream work are not new
            // demand. Cycles still get only one BFS pass, not a fixed point: hints
            // may be incomplete and must never become a rejection/proof rule.
            for(int key:keyOrder) {
                if(probeExpired(allowance)) return result;
                var missing=hints[key].subtract(BigInteger.valueOf(actual[key]));
                if(missing.signum()<=0) continue;
                for(int r:producers[key]) {
                    if(probeExpired(allowance)) return result;
                    if(!reachable[r])continue;
                    var rounds=missing.add(outputs[r].get(key)).subtract(ONE).divide(outputs[r].get(key));
                    for(int k:inputIndices[r]) {
                        if(probeExpired(allowance)) return result;
                        hints[k]=hints[k].max(rounds.multiply(inputs[r].get(k)));
                    }
                }
            }
            boolean progress=false;
            var pass=new ArrayList<Step<R>>();
            // Re-scan near-target producers first after every actual batch. An
            // executable recipe is useful only for a positive net hint deficit.
            // Deepest upstream first: a pass must build intermediates before the
            // recipes that consume them can execute against the same inventory.
            for(int ci=candidates.size()-1;ci>=0;ci--) {
                int r=candidates.get(ci);
                if(probeExpired(allowance)) return result;
                var d=delta(r); BigInteger count=ZERO;
                for(int k:outputIndices[r]) {
                    if(probeExpired(allowance)) return result;
                    if(d.get(k).signum()>0 && hints[k].compareTo(BigInteger.valueOf(actual[k]))>0) {
                        var deficit=hints[k].subtract(BigInteger.valueOf(actual[k]));
                        count=count.max(deficit.add(d.get(k)).subtract(ONE).divide(d.get(k)));
                    }
                }
                if(count.signum()==0) continue;
                count=count.min(MAX_COUNT);
                // Real pc/opcode execution supplies a concrete step and detached
                // stock. Failed/timed-out candidates cannot partially commit.
                var execution=NumericCraftingVM.execute(numericProgram,r,count.longValueExact(),
                        actual,()->probeExpired(allowance));
                if(execution.status()==NumericCraftingVM.Status.TIMED_OUT) return result;
                if(execution.status()!=NumericCraftingVM.Status.EXECUTED) continue;
                if(probeExpired(allowance)) return result;
                actual=execution.inventory();
                var step=execution.step();
                pass.add(new Step<>(recipes.get(step.recipe()).id,step.count()));
                result.explored++; progress=true; break;
            }
            if(!progress) return result;
            if(!pass.isEmpty()) blocks.add(new Block<>(List.copyOf(pass),1));
            // A real target-producing excursion is already a concrete candidate word.
            // Its batch counts need not repeat (seeded growth often doubles them),
            // and its length need not fit a fixed ring window. Repetition is allowed
            // only by exact prefix-capital and peak bounds, then independently certified.
            int m=blocks.size();
            if(actual[target]<lowTarget) {lowTarget=actual[target];lowStart=m;}
            if(actual[target]>captureTarget || actual[target]>lowTarget) {
                boolean highRise=actual[target]>captureTarget;
                var word=new ArrayList<Step<R>>();
                for(int i=highRise?captureStart:lowStart;i<m;i++) {
                    if(probeExpired(allowance))return result;
                    word.addAll(blocks.get(i).word());
                }
                if(highRise) {captureStart=m;captureTarget=actual[target];}
                lowStart=m;lowTarget=actual[target];
                var summary=summarize(word);var required=summary[0];var net=summary[1];var peak=summary[2];
                if(net[target].signum()<=0) continue;
                var remaining=goal[target].subtract(BigInteger.valueOf(actual[target]));
                if(remaining.signum()<=0) break;
                var cycles=remaining.add(net[target]).subtract(ONE).divide(net[target]);
                boolean feasible=true;
                for(int k=0;k<keys.size();k++) {
                    var held=BigInteger.valueOf(actual[k]);
                    // Net consumption is not enough: the last round must still have
                    // its prefix working capital, even if that capital is returned.
                    if(held.compareTo(required[k])<0 || held.add(peak[k]).compareTo(MAX_COUNT)>0) {feasible=false;break;}
                    if(net[k].signum()<0) cycles=cycles.min(held.subtract(required[k]).divide(net[k].negate()).add(ONE));
                    if(net[k].signum()>0) cycles=cycles.min(MAX_COUNT.subtract(held).subtract(peak[k]).divide(net[k]).add(ONE));
                }
                if(cycles.signum()<=0) { feasible=false; }
                if(!feasible) continue;
                if(cycles.compareTo(MAX_COUNT)>0) cycles=MAX_COUNT;
                try {
                    for(int k=0;k<keys.size();k++)
                        actual[k]=BigInteger.valueOf(actual[k]).add(net[k].multiply(cycles)).longValueExact();
                } catch(ArithmeticException overflow) { return result; }
                blocks.add(new Block<>(List.copyOf(word),cycles.longValueExact()));
                result.explored++;
                captureStart=blocks.size();captureTarget=actual[target];lowStart=captureStart;lowTarget=captureTarget;
            }
        }
        if(BigInteger.valueOf(actual[target]).compareTo(goal[target])<0 || probeExpired(allowance)) return result;
        if(blocks.isEmpty()) return result;
        // Prefix blocks may repeat recipes (bootstrap of a ring); certify() proves the
        // witness either way. Only unbounded bloat is refused here.
        if(blocks.size()>512) return result;
        Node witness=new Node(goal,null,null);
        for(int i=blocks.size()-1;i>=0;i--) {
            if(probeExpired(allowance)) return result;
            witness=new Node(goal,witness,blocks.get(i));
        }
        // The same independent prefix-capital and forward-replay proof used by
        // backward search is mandatory. Only its FEASIBLE answer may shortcut it.
        var certified=certify(witness,target,increment,result.explored);
        if(!expired() && certified.status==Status.FEASIBLE) result.feasible=certified;
        return result;
    }
    /** Failed regional construction contributes no reachability or shortage claim. */
    private Result<K,R> scc(int target,BigInteger[] goal,long increment) {
        if(!allowSccCandidate || limits.states<64 || expired())return null;
        long candidateStarted=System.nanoTime(),allowance=Math.min(200_000_000L,limits.nanos/8);
        var graph=compiled(Math.min(limits.nanos,System.nanoTime()-started+allowance));
        if(graph==null)return null;
        var candidate=SccCandidatePlanner.candidate(graph,stock,target,increment,
                new Limits(Math.min(limits.states,4096),allowance),
                ()->expired() || System.nanoTime()-candidateStarted>=allowance);
        if(candidate==null || expired())return null;
        Node witness=new Node(goal,null,null);
        for(int i=candidate.program().size()-1;i>=0;i--) {
            if(expired())return null;
            var block=candidate.program().get(i);var word=new ArrayList<Step<R>>();
            for(var step:block.word())word.add(new Step<>(recipes.get(step.recipe()).id,step.count()));
            witness=new Node(goal,witness,new Block<>(word,block.repetitions()));
        }
        try {
            var certified=certify(witness,target,increment,candidate.explored());
            if(!expired() && certified.status==Status.FEASIBLE) {sccFastPath=true;return certified;}
        } catch(CancellationException canceled) {throw canceled;}
        catch(IllegalStateException rejected) { /* Keep the general solver authoritative. */ }
        return null;
    }
    public Result<K,R> solve(K target, long increment) {
        if(increment<=0) throw new IllegalArgumentException("Increment must be positive");
        started=System.nanoTime(); fair.clear(); clipped=false; serial=0;dagFastPath=false;resourceBound=false;sccFastPath=false;
        int targetIndex=keys.indexOf(target);
        if(targetIndex<0) return failure(Status.UNREACHABLE,0);
        var goal=vector(); goal[targetIndex]=stock[targetIndex].add(BigInteger.valueOf(increment));
        var aggregated=dag(targetIndex,goal,increment);if(aggregated!=null)return aggregated;
        // A failed constructive shortcut is NOT missing-material evidence. Try a
        // separately verified conservation certificate before exploring potentially
        // thousands of equivalent per-firing predecessors of an oversized order.
        if(compiled!=null && limits.states>=64 && !expired()) {
            long boundStarted=System.nanoTime(),allowance=Math.min(50_000_000L,limits.nanos/16);
            var bound=ConservedResourceBound.prove(compiled,stock,targetIndex,increment,
                    ()->expired() || System.nanoTime()-boundStarted>=allowance);
            if(bound!=null){resourceBound=true;return new Result<>(Status.UNREACHABLE,List.of(),Map.of(),Map.of(),1,bound.maximumAdditionalTarget());}
        }
        var attempt=probe(targetIndex,goal,increment);
        if(attempt.feasible!=null) return attempt.feasible;
        var regional=scc(targetIndex,goal,increment);if(regional!=null)return regional;
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble((Node n)->n.score).thenComparingLong(n->n.order));
        var basis=new ArrayList<Node>();
        Node root=new Node(goal,null,null); queue.add(root); fair.add(root); basis.add(root);
        // The probe has its own time allowance; never let its step count starve the
        // backward search of its state budget when the probe could not certify.
        int explored=Math.min(attempt.explored,limits.states/2);
        while(!queue.isEmpty()) {
            if(expired() || explored>=limits.states) return failure(Status.LIMIT,explored);
            // Interleave breadth-first work so a tempting huge batch cannot starve
            // the short witnesses from which topology-independent macros are learned.
            while(!fair.isEmpty() && (!fair.peek().active || fair.peek().expanded)) fair.remove();
            Node node=explored%8==7 && !fair.isEmpty()?fair.remove():queue.remove();
            if(!node.active || node.expanded) continue;
            node.expanded=true;
            explored++;
            if(le(node.need,stock)) return certify(node,targetIndex,increment,explored);
            for(int r=0;r<recipes.size();r++) {
                if(expired()) return failure(Status.LIMIT,explored);
                boolean useful=false;
                for(int k:outputKeyIndices[r]) if(node.need[k].signum()>0) { useful=true; break; }
                if(!useful) continue;
                var d=delta(r);
                long batch=jump(node.need,d);
                // Accelerators add edges; the ordinary one-step predecessor is NEVER omitted.
                if(batch>1 && offer(new Node(SparsePreimage.apply(node.need,inputs[r],d,batch,touchedKeyIndices[r]),node,
                        new Block<>(List.of(new Step<>(recipes.get(r).id,batch)),1)),basis,queue))
                    return failure(Status.LIMIT,explored);
                Node child=new Node(SparsePreimage.apply(node.need,inputs[r],d,1,touchedKeyIndices[r]),node,
                        new Block<>(List.of(new Step<>(recipes.get(r).id,1)),1));
                if(offer(child,basis,queue)) return failure(Status.LIMIT,explored);
                // Compose any short word, not a named two-node ring. Repeating a word
                // preserves its exact prefix seed requirement, including all side inputs.
                var word=new ArrayList<Step<R>>(); word.add(new Step<>(recipes.get(r).id,1));
                Node end=node;
                for(int depth=1;depth<16 && end.block!=null && end.block.repetitions==1
                        && end.block.word.size()==1 && end.block.word.getFirst().count==1;depth++) {
                    word.add(end.block.word.getFirst()); end=end.next;
                    var summary=summarize(word);
                    long rounds=jump(end.need,summary[1]);
                    if(rounds>1 && offer(new Node(pre(end.need,summary[0],summary[1],rounds),end,
                            new Block<>(word,rounds)),basis,queue)) return failure(Status.LIMIT,explored);
                }
                if(basis.size()+queue.size()>limits.states*4L) return failure(Status.LIMIT,explored);
            }
        }
        return failure(clipped?Status.LIMIT:Status.UNREACHABLE,explored);
    }
    /** @return true if the deadline expired during dominance scanning. */
    private boolean offer(Node n, List<Node> basis, Queue<Node> queue) {
        // A key with no producer can never be reduced by backwards regression.
        for(int k=0;k<keys.size();k++) {
            if((k&31)==0 && expired()) return true;
            if(!produced[k] && n.need[k].compareTo(stock[k])>0) return false;
        }
        for(var old:basis) {
            int relation=leWithinBudget(old.need,n.need);
            if(relation<0) return true;
            if(relation>0) return false;
        }
        for(var it=basis.iterator();it.hasNext();) {
            var old=it.next();
            int relation=leWithinBudget(n.need,old.need);
            if(relation<0) return true;
            if(relation>0) { old.active=false; it.remove(); }
        }
        basis.add(n); queue.add(n); fair.add(n);
        return false;
    }
    /** -1 = deadline, 0 = not dominated, 1 = dominated. */
    private int leWithinBudget(BigInteger[] a,BigInteger[] b) {
        for(int k=0;k<keys.size();k++) {
            if((k&31)==0 && expired()) return -1;
            if(a[k].compareTo(b[k])>0) return 0;
        }
        return 1;
    }
    /** Minimal prefix capital and signed net change of a straight-line word. */
    private BigInteger[][] summarize(List<Step<R>> word) {
        var required=vector(); var change=vector(); var peak=vector();
        for(var step:word) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException("Production planning cancelled");
            int r=recipeIds.get(step.recipe); var d=delta(r); BigInteger n=BigInteger.valueOf(step.count);
            for(int k=0;k<keys.size();k++) {
                BigInteger seed=inputs[r].get(k).add(d.get(k).negate().max(ZERO).multiply(n.subtract(ONE)));
                required[k]=required[k].max(seed.subtract(change[k]));
                change[k]=change[k].add(d.get(k).multiply(n));
                peak[k]=peak[k].max(change[k]);
            }
        }
        return new BigInteger[][]{required,change,peak};
    }
    private Result<K,R> certify(Node witness,int target,long increment,int explored) {
        var program=new ArrayList<Block<R>>();
        for(Node n=witness;n.block!=null;n=n.next) program.add(n.block);
        var required=vector(); var change=vector(); var counts=new LinkedHashMap<R,Long>();
        try {
            for(var block:program) {
                var summary=summarize(block.word); BigInteger n=BigInteger.valueOf(block.repetitions);
                for(int k=0;k<keys.size();k++) {
                    var seed=summary[0][k].add(summary[1][k].negate().max(ZERO).multiply(n.subtract(ONE)));
                    required[k]=required[k].max(seed.subtract(change[k]));
                    change[k]=change[k].add(summary[1][k].multiply(n));
                    // AE2 counters are signed longs, including intermediate inventories.
                    required[k].max(ZERO).longValueExact();
                }
                for(var step:block.word) counts.merge(step.recipe,Math.multiplyExact(step.count,block.repetitions),Math::addExact);
            }
            if(!le(required,stock) || change[target].compareTo(BigInteger.valueOf(increment))<0)
                throw new IllegalStateException("Invalid production witness");
            var used=new LinkedHashMap<K,Long>();
            for(int k=0;k<keys.size();k++) if(required[k].signum()>0) used.put(keys.get(k),required[k].longValueExact());
            // Independent forward replay of block prefix invariants, with the ACTUAL
            // extracted capital, not the entire network or fictitious missing items.
            var replay=vector(used);
            for(var block:program) {
                var s=summarize(block.word); BigInteger n=BigInteger.valueOf(block.repetitions);
                for(int k=0;k<keys.size();k++) {
                    var seed=s[0][k].add(s[1][k].negate().max(ZERO).multiply(n.subtract(ONE)));
                    if(replay[k].compareTo(seed)<0) throw new IllegalStateException("Unexecutable witness");
                    replay[k].add(s[2][k]).add(s[1][k].max(ZERO).multiply(n.subtract(ONE))).longValueExact();
                    replay[k]=replay[k].add(s[1][k].multiply(n)); replay[k].longValueExact();
                }
            }
            return new Result<>(Status.FEASIBLE,List.copyOf(program),Map.copyOf(used),Map.copyOf(counts),explored);
        } catch(ArithmeticException overflow) { return failure(Status.LIMIT,explored); }
    }
    private Result<K,R> failure(Status status,int explored) { return new Result<>(status,List.of(),Map.of(),Map.of(),explored); }
}
