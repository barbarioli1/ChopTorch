package com.example.choptorch;

public final class MonitoringStartFlowTest {
    public static void main(String[] args) {
        testAllowlistedStartsWithoutGuidance();
        testContinueWithoutChangingStartsOnce();
        testSettingsReturnRequiresConfirmation();
        testUnavailableSettingsCanContinueOrCancel();
        testSavedPendingStateNeverStartsByItself();
        System.out.println("PASS: monitoring-start flow transitions");
    }

    private static void testAllowlistedStartsWithoutGuidance() {
        MonitoringStartFlow flow = new MonitoringStartFlow();
        check(flow.begin(), "begin allowlisted flow");
        check(!flow.begin(), "ignore duplicate start");
        check(flow.cameraReady(true), "accept camera prerequisites");
        check(flow.isReadyToStart(), "allowlisted path is ready");
        check(flow.consumeStart(), "consume allowlisted start");
        check(!flow.consumeStart(), "start can only be consumed once");
    }

    private static void testContinueWithoutChangingStartsOnce() {
        MonitoringStartFlow flow = new MonitoringStartFlow();
        flow.begin();
        flow.cameraReady(false);
        check(flow.isAwaitingBatteryChoice(), "non-allowlisted path asks for choice");
        check(flow.continueWithoutChanging(), "continue with current settings");
        check(flow.consumeStart(), "consume continued start");
        check(!flow.isPending(), "flow returns to idle after start");
    }

    private static void testSettingsReturnRequiresConfirmation() {
        MonitoringStartFlow flow = new MonitoringStartFlow();
        flow.begin();
        flow.cameraReady(false);
        flow.openSettings();
        check(flow.settingsReturned(true), "recheck allowlist on return");
        check(flow.wasAllowlistedWhenReturned(), "retain observed allowlist status");
        check(!flow.isReadyToStart(), "settings return does not start monitoring");
        check(flow.confirmAfterSettings(), "require explicit return confirmation");
        check(flow.consumeStart(), "start after confirmation");
    }

    private static void testUnavailableSettingsCanContinueOrCancel() {
        MonitoringStartFlow flow = new MonitoringStartFlow();
        flow.begin();
        flow.cameraReady(false);
        flow.openSettings();
        check(flow.settingsUnavailable(), "recover when no settings activity exists");
        check(flow.isAwaitingBatteryChoice(), "unavailable settings leave an explicit choice");
        flow.cancel();
        check(!flow.isPending(), "cancel unavailable-settings flow");
    }

    private static void testSavedPendingStateNeverStartsByItself() {
        MonitoringStartFlow original = new MonitoringStartFlow();
        original.begin();
        original.cameraReady(false);
        original.openSettings();
        original.settingsReturned(true);

        MonitoringStartFlow restored = new MonitoringStartFlow();
        restored.restoreState(original.saveState(), original.saveAllowlistedOnReturn());
        check(restored.isAwaitingReturnConfirmation(), "restore confirmation state");
        check(restored.wasAllowlistedWhenReturned(), "restore observed allowlist status");
        check(!restored.isReadyToStart(), "restored state cannot start automatically");
        check(restored.confirmAfterSettings(), "restored state still needs confirmation");
        check(restored.consumeStart(), "confirmed restored start can be consumed");

        original.cancel();
        original.begin();
        original.cameraReady(true);
        restored.restoreState(original.saveState(), original.saveAllowlistedOnReturn());
        check(!restored.isPending(), "discard transient ready state after recreation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}