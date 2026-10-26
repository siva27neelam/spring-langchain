package com.loanprocessing.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "loan.llm.client", havingValue = "scripted")
public class ScriptedChatModel implements ChatModel {
    private final AtomicReference<Function<String, String>> handler = new AtomicReference<>(ScriptedChatModel::echo);
    private final AtomicInteger calls = new AtomicInteger();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void reset() {
        handler.set(ScriptedChatModel::echo);
        calls.set(0);
    }

    public void setHandler(Function<String, String> next) {
        handler.set(next);
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        calls.incrementAndGet();
        StringBuilder user = new StringBuilder();
        for (ChatMessage message : request.messages()) {
            if (message instanceof UserMessage userMessage) {
                user.append(userMessage.singleText()).append('\n');
            }
        }
        String json = handler.get().apply(user.toString());
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(json))
                .tokenUsage(new TokenUsage(user.length() / 4, json.length() / 4, user.length() / 4 + json.length() / 4))
                .build();
    }

    public static String echo(String userText) {
        String document = userText;
        int start = userText.indexOf("<<<DOCUMENT");
        int end = userText.indexOf("DOCUMENT>>>");
        if (start >= 0 && end > start) {
            document = userText.substring(start + "<<<DOCUMENT".length(), end);
        }
        ObjectNode node = MAPPER.createObjectNode();
        put(node, "employeeFirstName", field(document, "firstName"));
        put(node, "employeeLastName", field(document, "lastName"));
        String first = field(document, "firstName");
        String last = field(document, "lastName");
        if (first != null && last != null) {
            node.put("rawNameLine", first + " " + last);
            node.put("nameEvidence", first + " " + last);
        }
        put(node, "addressLine1", field(document, "line1"));
        put(node, "addressEvidence", field(document, "line1"));
        put(node, "city", field(document, "city"));
        put(node, "region", field(document, "region"));
        put(node, "postalCode", field(document, "postalCode"));
        put(node, "grossAmount", field(document, "gross"));
        put(node, "grossEvidence", field(document, "gross"));
        put(node, "cadence", field(document, "cadence"));
        put(node, "currency", field(document, "currency"));
        put(node, "documentKind", field(document, "documentKind") == null ? "PAYSLIP" : field(document, "documentKind"));
        put(node, "ssnLast4", field(document, "ssnLast4"));
        put(node, "dateOfBirth", field(document, "dateOfBirth"));
        put(node, "payPeriodEnd", field(document, "periodEnd"));
        put(node, "employer", field(document, "employer"));
        node.put("salaryConfidence", 0.99);
        node.put("identityConfidence", 0.99);
        return node.toString();
    }

    private static void put(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static String field(String document, String name) {
        Matcher matcher = Pattern.compile("(?im)^" + Pattern.quote(name) + "\\s*:\\s*(.+)$").matcher(document);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).trim();
    }
}
