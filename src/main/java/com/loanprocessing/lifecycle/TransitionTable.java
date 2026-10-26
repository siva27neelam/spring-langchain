package com.loanprocessing.lifecycle;

import com.loanprocessing.model.ApplicationStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class TransitionTable {
    private static final Map<ApplicationStatus, Set<ApplicationStatus>> EDGES = Map.ofEntries(
            Map.entry(ApplicationStatus.RECEIVED, EnumSet.of(ApplicationStatus.CREDIT_IN_PROGRESS, ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.CREDIT_IN_PROGRESS, EnumSet.of(
                    ApplicationStatus.CREDIT_IN_PROGRESS,
                    ApplicationStatus.PAYSLIP_REVIEW,
                    ApplicationStatus.DECLINED,
                    ApplicationStatus.MANUAL_HOLD,
                    ApplicationStatus.CREDIT_FAILED,
                    ApplicationStatus.WITHDRAWN
            )),
            Map.entry(ApplicationStatus.CREDIT_FAILED, EnumSet.of(ApplicationStatus.CREDIT_IN_PROGRESS, ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.PAYSLIP_REVIEW, EnumSet.of(
                    ApplicationStatus.PAYSLIP_REVIEW,
                    ApplicationStatus.EXTRACTION_QUEUED,
                    ApplicationStatus.ABANDONED,
                    ApplicationStatus.WITHDRAWN
            )),
            Map.entry(ApplicationStatus.EXTRACTION_QUEUED, EnumSet.of(ApplicationStatus.EXTRACTING, ApplicationStatus.WITHDRAWN, ApplicationStatus.MANUAL_HOLD)),
            Map.entry(ApplicationStatus.EXTRACTING, EnumSet.of(
                    ApplicationStatus.PAYSLIP_REVIEW,
                    ApplicationStatus.EXTRACTION_QUEUED,
                    ApplicationStatus.EXTRACTION_FAILED,
                    ApplicationStatus.MANUAL_HOLD,
                    ApplicationStatus.DECLINED,
                    ApplicationStatus.APPROVED,
                    ApplicationStatus.WITHDRAWN
            )),
            Map.entry(ApplicationStatus.EXTRACTION_FAILED, EnumSet.of(ApplicationStatus.EXTRACTION_QUEUED, ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.MANUAL_HOLD, EnumSet.of(ApplicationStatus.APPROVED, ApplicationStatus.DECLINED, ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.APPROVED, EnumSet.noneOf(ApplicationStatus.class)),
            Map.entry(ApplicationStatus.DECLINED, EnumSet.noneOf(ApplicationStatus.class)),
            Map.entry(ApplicationStatus.ABANDONED, EnumSet.noneOf(ApplicationStatus.class)),
            Map.entry(ApplicationStatus.WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class))
    );

    private TransitionTable() {
    }

    public static boolean allowed(ApplicationStatus from, ApplicationStatus to) {
        if (from == null || to == null || from.terminal()) {
            return false;
        }
        return EDGES.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<ApplicationStatus> terminals() {
        return EnumSet.of(ApplicationStatus.APPROVED, ApplicationStatus.DECLINED, ApplicationStatus.ABANDONED, ApplicationStatus.WITHDRAWN);
    }
}
