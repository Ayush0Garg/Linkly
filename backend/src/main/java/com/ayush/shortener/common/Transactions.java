package com.ayush.shortener.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class Transactions {

    private Transactions() {
    }

    /**
     * Runs the action once the surrounding transaction has committed (immediately if there is none).
     * Cache eviction must happen here, not before the commit: evict first and a reader can reload the OLD row from the
     * database into the cache before the change is visible, leaving the stale entry in place.
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
