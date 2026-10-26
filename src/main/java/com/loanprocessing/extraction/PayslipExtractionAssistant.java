package com.loanprocessing.extraction;

import dev.langchain4j.service.UserMessage;

public interface PayslipExtractionAssistant {
    String extract(@UserMessage String documentText);
}
