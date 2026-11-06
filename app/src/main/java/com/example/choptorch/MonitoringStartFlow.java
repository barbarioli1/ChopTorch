package com.example.choptorch;

final class MonitoringStartFlow {
    private static final int IDLE = 0;
    private static final int WAITING_FOR_CAMERA = 1;
    private static final int BATTERY_GUIDANCE = 2;
    private static final int WAITING_FOR_SETTINGS = 3;
    private static final int RETURN_CONFIRMATION = 4;
    private static final int READY_TO_START = 5;

    private int state = IDLE;
    private boolean allowlistedOnReturn;

    boolean begin() {
        if (state != IDLE) return false;
        state = WAITING_FOR_CAMERA;
        return true;
    }

    boolean cameraReady(boolean allowlisted) {
        if (state != WAITING_FOR_CAMERA) return false;
        state = allowlisted ? READY_TO_START : BATTERY_GUIDANCE;
        return true;
    }

    boolean continueWithoutChanging() {
        if (state != BATTERY_GUIDANCE) return false;
        state = READY_TO_START;
        return true;
    }

    boolean openSettings() {
        if (state != BATTERY_GUIDANCE) return false;
        state = WAITING_FOR_SETTINGS;
        return true;
    }

    boolean settingsReturned(boolean allowlisted) {
        if (state != WAITING_FOR_SETTINGS) return false;
        allowlistedOnReturn = allowlisted;
        state = RETURN_CONFIRMATION;
        return true;
    }

    boolean settingsUnavailable() {
        if (state != WAITING_FOR_SETTINGS) return false;
        state = BATTERY_GUIDANCE;
        return true;
    }

    boolean confirmAfterSettings() {
        if (state != RETURN_CONFIRMATION) return false;
        state = READY_TO_START;
        return true;
    }

    boolean consumeStart() {
        if (state != READY_TO_START) return false;
        state = IDLE;
        return true;
    }

    void cancel() {
        state = IDLE;
    }

    boolean isPending() { return state != IDLE; }
    boolean isWaitingForSettings() { return state == WAITING_FOR_SETTINGS; }
    boolean isAwaitingBatteryChoice() { return state == BATTERY_GUIDANCE; }
    boolean isAwaitingReturnConfirmation() { return state == RETURN_CONFIRMATION; }
    boolean isReadyToStart() { return state == READY_TO_START; }
    boolean wasAllowlistedWhenReturned() { return allowlistedOnReturn; }

    int saveState() {
        return state == READY_TO_START ? IDLE : state;
    }

    boolean saveAllowlistedOnReturn() {
        return allowlistedOnReturn;
    }

    void restoreState(int savedState, boolean savedAllowlistedOnReturn) {
        if (savedState >= WAITING_FOR_CAMERA && savedState <= RETURN_CONFIRMATION) state = savedState;
        else state = IDLE;
        allowlistedOnReturn = state == RETURN_CONFIRMATION && savedAllowlistedOnReturn;
    }
}