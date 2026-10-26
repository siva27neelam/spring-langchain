package com.loanprocessing.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class FaultSwitch {
    private final boolean enabled;
    private final AtomicBoolean databaseDown = new AtomicBoolean();
    private final AtomicBoolean auditFail = new AtomicBoolean();
    private final AtomicBoolean kmsFail = new AtomicBoolean();
    private final AtomicBoolean poisonCredit = new AtomicBoolean();

    public FaultSwitch(@Value("${loan.fault-injection:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void reset() {
        databaseDown.set(false);
        auditFail.set(false);
        kmsFail.set(false);
        poisonCredit.set(false);
    }

    public boolean enabled() {
        return enabled;
    }

    public void setDatabaseDown(boolean value) {
        databaseDown.set(enabled && value);
    }

    public void setAuditFail(boolean value) {
        auditFail.set(enabled && value);
    }

    public void setKmsFail(boolean value) {
        kmsFail.set(enabled && value);
    }

    public void setPoisonCredit(boolean value) {
        poisonCredit.set(enabled && value);
    }

    public boolean databaseDown() {
        return enabled && databaseDown.get();
    }

    public boolean auditFail() {
        return enabled && auditFail.get();
    }

    public boolean kmsFail() {
        return enabled && kmsFail.get();
    }

    public boolean poisonCredit() {
        return enabled && poisonCredit.get();
    }
}
