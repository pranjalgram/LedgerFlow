package com.ledgerflow.reconciliation.internal;

import com.ledgerflow.reconciliation.ReconciliationQueue;
import com.ledgerflow.reconciliation.ReconciliationScanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledgerflow.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationWorker {
    private final ReconciliationQueue queue;
    private final ReconciliationScanner scanner;
    ReconciliationWorker(ReconciliationQueue queue, ReconciliationScanner scanner) { this.queue = queue; this.scanner = scanner; }
    @Scheduled(fixedDelayString = "${ledgerflow.reconciliation.poll-delay:5000}", initialDelayString = "${ledgerflow.reconciliation.initial-delay:5000}")
    public void dispatchOnce() {
        queue.claim().ifPresent(job -> {
            try { scanner.scan(job); }
            catch (DataAccessException unavailable) { queue.failed(job); }
        });
    }
}
