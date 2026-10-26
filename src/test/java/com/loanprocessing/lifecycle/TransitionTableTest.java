package com.loanprocessing.lifecycle;

import com.loanprocessing.model.ApplicationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionTableTest {
    @Test
    void terminalStatusesHaveNoOutboundTransition() {
        for (ApplicationStatus terminal : TransitionTable.terminals()) {
            assertTrue(terminal.terminal());
            for (ApplicationStatus target : ApplicationStatus.values()) {
                assertFalse(TransitionTable.allowed(terminal, target), terminal + " -> " + target);
            }
        }
    }

    @Test
    void payslipReviewAndManualHoldAreDifferentStates() {
        assertTrue(TransitionTable.allowed(ApplicationStatus.PAYSLIP_REVIEW, ApplicationStatus.EXTRACTION_QUEUED));
        assertFalse(TransitionTable.allowed(ApplicationStatus.PAYSLIP_REVIEW, ApplicationStatus.APPROVED));
        assertTrue(TransitionTable.allowed(ApplicationStatus.MANUAL_HOLD, ApplicationStatus.APPROVED));
        assertTrue(TransitionTable.allowed(ApplicationStatus.MANUAL_HOLD, ApplicationStatus.DECLINED));
        assertFalse(TransitionTable.allowed(ApplicationStatus.CREDIT_IN_PROGRESS, ApplicationStatus.APPROVED));
        assertTrue(TransitionTable.allowed(ApplicationStatus.EXTRACTING, ApplicationStatus.APPROVED));
        assertTrue(TransitionTable.allowed(ApplicationStatus.EXTRACTION_QUEUED, ApplicationStatus.MANUAL_HOLD));
    }
}
