package com.aecraftroute.addon.vm;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyFeedbackSaveTest {
    @Test void legacyAliasesAndMissingDeferredMarkerRemainSupported() {
        var tag=new CompoundTag();tag.putBoolean("ae2vmGrowthFeedback",true);tag.putLong("ae2vmRestoreTargetSeed",2);
        var state=LegacyFeedbackSave.read(tag);
        assertTrue(state.enabled());assertEquals(2,state.restoreSeed());assertFalse(state.deferred());assertEquals(0,state.held());
    }
    @Test void validCurrentFalseOverridesLegacyTrueAndCurrentSeedOverridesLegacySeed() {
        var tag=new CompoundTag();tag.putBoolean("ae2vmGrowthFeedback",true);tag.putBoolean("aecraftrouteGrowthFeedback",false);
        tag.putLong("ae2vmRestoreTargetSeed",9);tag.putLong("aecraftrouteRestoreTargetSeed",3);
        var state=LegacyFeedbackSave.read(tag);
        assertFalse(state.enabled());assertEquals(3,state.restoreSeed());
    }
    @Test void malformedDeferredMarkerDoesNotBypassHeldValidation() {
        var tag=new CompoundTag();tag.putBoolean("aecraftrouteGrowthFeedback",true);
        tag.put("aecraftrouteDeferredFeedback",StringTag.valueOf("true"));tag.putLong("aecraftrouteDeferredHeld",2);
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(tag));
    }
    @Test void deferredProtocolRequiresGrowthFlagAndLongHeldCount() {
        var noGrowth=new CompoundTag();noGrowth.putBoolean("aecraftrouteDeferredFeedback",true);noGrowth.putLong("aecraftrouteDeferredHeld",1);
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(noGrowth));
        var noHeld=new CompoundTag();noHeld.putBoolean("aecraftrouteGrowthFeedback",true);noHeld.putBoolean("aecraftrouteDeferredFeedback",true);
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(noHeld));
    }
    @Test void malformedCurrentAliasFallsBackToWellTypedLegacyAlias() {
        var tag=new CompoundTag();tag.putString("aecraftrouteGrowthFeedback","bad");tag.putBoolean("ae2vmGrowthFeedback",true);
        tag.putInt("aecraftrouteRestoreTargetSeed",7);tag.putLong("ae2vmRestoreTargetSeed",4);
        var state=LegacyFeedbackSave.read(tag);
        assertTrue(state.enabled());assertEquals(4,state.restoreSeed());
    }
    @Test void malformedAliasesWithoutAValidLegacyValueAreRejected() {
        var tag=new CompoundTag();tag.putString("aecraftrouteGrowthFeedback","bad");
        tag.putInt("aecraftrouteRestoreTargetSeed",1);
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(tag));
    }
    @Test void orphanedHeldCountWithoutDeferredMarkerIsRejected() {
        var withValue=new CompoundTag();withValue.putBoolean("aecraftrouteGrowthFeedback",true);
        withValue.putBoolean("aecraftrouteDeferredFeedback",false);withValue.putLong("aecraftrouteDeferredHeld",2);
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(withValue));
        var wrongType=new CompoundTag();wrongType.putBoolean("aecraftrouteGrowthFeedback",true);
        wrongType.putBoolean("aecraftrouteDeferredFeedback",false);wrongType.putString("aecraftrouteDeferredHeld","2");
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(wrongType));
        var honestZero=new CompoundTag();honestZero.putBoolean("aecraftrouteGrowthFeedback",true);
        honestZero.putBoolean("aecraftrouteDeferredFeedback",false);honestZero.putLong("aecraftrouteDeferredHeld",0);
        assertFalse(LegacyFeedbackSave.read(honestZero).deferred());
    }

    @Test void deferredMarkerWithoutSavedJobIsRejected() {
        var noJob=new CompoundTag();noJob.putBoolean("aecraftrouteGrowthFeedback",true);
        noJob.putBoolean("aecraftrouteDeferredFeedback",true);noJob.putLong("aecraftrouteDeferredHeld",2);
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(noJob));
        var withJob=new CompoundTag();withJob.putBoolean("aecraftrouteGrowthFeedback",true);
        withJob.putBoolean("aecraftrouteDeferredFeedback",true);withJob.putLong("aecraftrouteDeferredHeld",2);
        withJob.put("job",new CompoundTag());
        var state=LegacyFeedbackSave.read(withJob);
        assertTrue(state.deferred());assertEquals(2,state.held());
    }

    @Test void savedJobMustBeACompoundSoNativeStateCanActuallyLoadIt() {
        var wrongType=new CompoundTag();wrongType.putBoolean("aecraftrouteGrowthFeedback",true);
        wrongType.putBoolean("aecraftrouteDeferredFeedback",true);wrongType.putLong("aecraftrouteDeferredHeld",2);
        wrongType.putString("job","not-a-job");
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(wrongType));
    }

    @Test void negativeSeedIsRejected() {
        var tag=new CompoundTag();tag.putBoolean("aecraftrouteGrowthFeedback",true);tag.putLong("aecraftrouteRestoreTargetSeed",-1);
        assertThrows(IllegalArgumentException.class,()->LegacyFeedbackSave.read(tag));
    }
}
