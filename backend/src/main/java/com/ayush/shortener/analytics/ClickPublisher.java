package com.ayush.shortener.analytics;

/** Somewhere a click can be sent without waiting for the answer. */
interface ClickPublisher {

    /**
     * Hands the click over and returns at once.
     *
     * @param onFailure called later, from another thread, if the hand-over turns out to have failed: the click must then
     *                  be kept some other way
     * @return false if the click was NOT taken (the caller keeps it); true if it was submitted
     */
    boolean publish(PendingClick click, Runnable onFailure);
}
