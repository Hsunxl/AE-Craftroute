package com.aecraftroute.addon.planner;

import java.util.*;

/** Execution position advances ONLY after a provider accepts the real inputs. */
public final class ProductionCursor<R> {
    private final List<ProductionPlanner.Block<R>> program;
    private int block,step;
    private long round,completed;
    public record Position(int block,int step,long round,long completed) {}
    public ProductionCursor(List<ProductionPlanner.Block<R>> program) { this.program=List.copyOf(program); }
    public List<ProductionPlanner.Block<R>> program() { return program; }
    public boolean done() { return block==program.size(); }
    public R current() { return done()?null:program.get(block).word().get(step).recipe(); }
    public Position position() { return new Position(block,step,round,completed); }
    /** Counts not yet dispatched; expected return packets are deliberately separate. */
    public Map<R,Long> remainingCounts() {
        var counts=new LinkedHashMap<R,Long>();
        for(int b=block;b<program.size();b++) {
            var word=program.get(b); long rounds=word.repetitions()-(b==block?round:0);
            for(int s=0;s<word.word().size();s++) {
                var instruction=word.word().get(s);
                long n=Math.multiplyExact(instruction.count(),rounds);
                if(b==block) {
                    if(s<step) n=Math.subtractExact(n,instruction.count());
                    else if(s==step) n=Math.subtractExact(n,completed);
                }
                if(n>0) counts.merge(instruction.recipe(),n,Math::addExact);
            }
        }
        return Map.copyOf(counts);
    }
    public void restore(Position p) {
        if(p.block<0 || p.block>program.size() || p.step<0 || p.round<0 || p.completed<0) throw new IllegalArgumentException("Invalid cursor");
        if(p.block==program.size()) {
            if(p.step!=0 || p.round!=0 || p.completed!=0) throw new IllegalArgumentException("Invalid completed cursor");
        } else {
            var b=program.get(p.block);
            if(p.step>=b.word().size() || p.round>=b.repetitions() || p.completed>=b.word().get(p.step).count())
                throw new IllegalArgumentException("Cursor outside program");
        }
        block=p.block; step=p.step; round=p.round; completed=p.completed;
    }
    public void accepted(R recipe) {
        if(done() || !Objects.equals(current(),recipe)) throw new IllegalStateException("Out-of-order dispatch");
        var b=program.get(block);
        if(++completed==b.word().get(step).count()) {
            completed=0;
            if(++step==b.word().size()) {
                step=0;
                if(++round==b.repetitions()) { round=0; block++; }
            }
        }
    }
}
