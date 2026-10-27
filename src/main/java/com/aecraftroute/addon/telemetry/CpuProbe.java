package com.aecraftroute.addon.telemetry;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only description of why a CPU refused a job.
 *
 * <p>AE2 reports {@code CPU_OFFLINE} from a single boolean, so the log never says which
 * predicate failed: power, a grid that has not finished booting, missing channels, or a
 * core block entity that is not loaded at all. Every third-party CPU (AdvancedAE quantum
 * computers, computation multiblocks, virtual CPUs) repeats that same shape, and those
 * classes are optional at runtime. Reflection keeps this diagnostic loadable without them
 * and guarantees it never changes behaviour: every call is wrapped and returns text only.
 */
public final class CpuProbe {
    private static final String[] FLAGS = {
            "isActive", "isOnline", "isPowered", "hasGridBooted", "meetsChannelRequirements",
            "isBusy", "isMarkedForDeletion"
    };
    private static final String[] NUMBERS = {"getAvailableStorage", "getCoProcessors", "getMaxChannels", "getUsedChannels"};

    private CpuProbe() { }

    /** One CPU (or cluster, or grid node) as {@code Class{flag=value,...}}. */
    public static String describe(Object subject) {
        if (subject == null) return "<automatic>";
        var text = new StringBuilder(subject.getClass().getName()).append('{');
        boolean first = true;
        for (var name : FLAGS) {
            var value = call(subject, name);
            if (value == null) continue;
            if (!first) text.append(',');
            first = false;
            text.append(name).append('=').append(value);
        }
        for (var name : NUMBERS) {
            var value = call(subject, name);
            if (value == null) continue;
            if (!first) text.append(',');
            first = false;
            text.append(name).append('=').append(value);
        }
        var name = call(subject, "getName");
        if (name != null) {
            if (!first) text.append(',');
            first = false;
            text.append("name=").append(plain(name));
        }
        text.append('}');
        var node = call(subject, "getNode");
        if (node != null && node != subject) text.append(" node=").append(describe(node));
        return text.toString();
    }

    /**
     * True only when the object positively reports that it cannot accept a job right now
     * ({@code isActive() == false}), which every AE2 CPU family and the third-party CPUs
     * derived from it expose. Absent or unreadable state stays "unknown" so nothing is
     * ever skipped on a guess.
     */
    public static boolean isProvablyInactive(Object subject) {
        Object active = call(subject, "isActive");
        return active instanceof Boolean value && !value;
    }

    /**
     * Why a positively-inactive object is inactive, in the same terms AE2 tests:
     * power, grid boot, channels, and whether a core block entity exists at all.
     */
    public static String describeInactiveReason(Object subject) {
        if (subject == null) return "<none>";
        Object node = call(subject, "getNode");
        if (node == null) return "no grid node (core block not loaded or not formed)";
        return describe(node);
    }

    /** The submitted plan in the terms a submission can be refused for. */
    public static String describePlan(ICraftingPlan plan) {
        if (plan == null) return "<null plan>";
        var text = new StringBuilder(plan.getClass().getName()).append('{');
        text.append("simulation=").append(plan.simulation());
        text.append(",bytes=").append(plan.bytes());
        GenericStack output = plan.finalOutput();
        text.append(",output=").append(output);
        text.append(",patterns=").append(plan.patternTimes().size());
        text.append(",missing=").append(plan.missingItems().size());
        return text.append('}').toString();
    }

    /** Every CPU this grid currently offers, so an offline selection can be compared. */
    public static String describeGrid(IGrid grid) {
        if (grid == null) return "<no grid>";
        try {
            var service = grid.getCraftingService();
            if (service == null) return "<no crafting service>";
            List<String> cpus = new ArrayList<>();
            for (ICraftingCPU cpu : service.getCpus()) {
                cpus.add(describe(cpu));
                if (cpus.size() >= 12) { cpus.add("..."); break; }
            }
            return cpus.isEmpty() ? "<no CPUs on this grid>" : String.join(" | ", cpus);
        } catch (RuntimeException failed) {
            return "<CPU list unavailable: " + failed + ">";
        }
    }

    private static Object call(Object subject, String name) {
        if (subject == null) return null;
        try {
            Method method = subject.getClass().getMethod(name);
            if (method.getParameterCount() != 0) return null;
            return method.invoke(subject);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /** Identity, not toString(): a grid's display text does not prove object identity. */
    public static String identity(Object subject) {
        return subject == null ? "<null>" : subject.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(subject));
    }

    private static String plain(Object value) {
        if (value instanceof CharSequence text) return text.toString();
        try {
            Method getString = value.getClass().getMethod("getString");
            Object text = getString.invoke(value);
            return String.valueOf(text);
        } catch (ReflectiveOperationException | RuntimeException notAComponent) {
            return String.valueOf(value);
        }
    }
}
