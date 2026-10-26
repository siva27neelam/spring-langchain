package com.loanprocessing.security;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

public class RedactingPatternLayout extends PatternLayout {
    @Override
    public String doLayout(ILoggingEvent event) {
        return LogRedactor.redact(super.doLayout(event));
    }
}
