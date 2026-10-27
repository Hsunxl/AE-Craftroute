package com.aecraftroute.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;
import java.util.Map;
import java.util.function.ToLongFunction;

/** Counter-only accounting for legacy feedback packets already present in CPU stock.
 * The CPU adapter owns inventory, waitingFor, requester calls and dirty marking.
 * Offers never mutate this ledger; commit only after the corresponding operation.
 */
public final class DeferredFeedbackReservation {
    public record Release(long seed,long directLimit) {}
    private long held;

    public long held(){return held;}

    /** Upper bound, not a claim that every alternative will actually be consumed.
     * Each slot contributes its maximum template amount, never the sum of mutually
     * exclusive alternatives. Unused held stock must later be released.
     */
    public static <T> long potentialInput(Map<IPatternDetails,T> tasks,ToLongFunction<T> count,
            AEKey feedback,Level level) {
        long total=0;
        for(var task:tasks.entrySet()) {
            long times=count.applyAsLong(task.getValue());
            if(times<=0)continue;
            var inputs=task.getKey().getInputs();
            if(inputs==null)throw new IllegalArgumentException("Missing feedback input slots");
            for(var input:inputs) {
                if(input==null)throw new IllegalArgumentException("Missing feedback input slot");
                // Returned catalysts still have to remain available until dispatch.
                if(!input.isValid(feedback,level))continue;
                var choices=input.getPossibleInputs();
                if(choices==null || choices.length==0 || input.getMultiplier()<=0)
                    throw new IllegalArgumentException("Invalid feedback input candidates");
                long maximum=0;
                for(var choice:choices) {
                    if(choice==null || choice.what()==null || choice.amount()<=0)
                        throw new IllegalArgumentException("Invalid feedback input candidate");
                    maximum=Math.max(maximum,choice.amount());
                }
                try {total=Math.addExact(total,Math.multiplyExact(times,
                        Math.multiplyExact(input.getMultiplier(),maximum)));}
                catch(ArithmeticException overflow){return Long.MAX_VALUE;}
            }
        }
        return total;
    }

    /** Snapshot BEFORE calling the provider, which may clear/mutate input counters.
     * Commit this count only on successful push; failed pushes are reinjected natively.
     */
    public static long consumedInput(IPatternDetails pattern,KeyCounter[] extracted,AEKey feedback) {
        var slots=pattern.getInputs();
        if(slots==null || extracted==null || slots.length!=extracted.length)
            throw new IllegalArgumentException("Feedback extraction shape changed");
        long total=0;
        for(int i=0;i<slots.length;i++) {
            if(slots[i]==null || extracted[i]==null)throw new IllegalArgumentException("Missing feedback extraction slot");
            long amount=extracted[i].get(feedback);nonnegative(amount);
            // This is physical transfer, not net destruction. Even a returned
            // catalyst leaves CPU stock; native code separately adds its return
            // to waitingFor after provider acceptance, then receive buffers it anew.
            if(amount==0)continue;
            try {total=Math.addExact(total,amount);}
            catch(ArithmeticException overflow){return Long.MAX_VALUE;}
        }
        return total;
    }

    /** Buffer all expected terminal packets until no remaining task can consume them.
     * This avoids predicting which candidate a future dispatch will choose and avoids
     * native insert's eager delivery accounting. The adapter flushes on a later tick.
     */
    public long offerBuffer(long incoming,long waiting) {
        nonnegative(incoming,waiting);
        if(held>waiting)throw new IllegalStateException("Deferred feedback exceeds outstanding output");
        return Math.min(incoming,waiting-held);
    }

    public void retain(long amount) {
        nonnegative(amount);
        held=Math.addExact(held,amount);
    }

    /** Called only after a provider accepted the inputs, not on attempted extraction. */
    public long consumed(long amount) {
        nonnegative(amount);
        long used=Math.min(held,amount);held-=used;return used;
    }

    public Release offerRelease(long pendingInput,long stored,long restoreSeed,long remainingOrder) {
        nonnegative(pendingInput,stored,restoreSeed,remainingOrder);
        if(pendingInput!=0)return new Release(0,0);
        long available=Math.min(held,stored);
        long seed=Math.min(restoreSeed,available);
        return new Release(seed,Math.min(remainingOrder,available-seed));
    }

    /** Both seed restoration and successful requester acceptance settle held credit. */
    public void settle(long amount) {
        nonnegative(amount);
        if(amount>held)throw new IllegalStateException("Deferred feedback settled twice");
        held-=amount;
    }

    /** Native CPU persistence owns waiting/stock; all three must agree on restore. */
    public void restore(long saved,long waiting,long stored) {
        nonnegative(saved,waiting,stored);
        if(saved>waiting || saved>stored)
            throw new IllegalArgumentException("Deferred feedback save disagrees with native CPU state");
        held=saved;
    }

    private static void nonnegative(long... values) {
        for(long value:values)if(value<0)throw new IllegalArgumentException("Negative feedback accounting value");
    }
}
