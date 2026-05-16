package com.wassim.reckon.service;

import com.wassim.reckon.dto.Citation;
import com.wassim.reckon.dto.QaResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class QaService {

    private static final String SYSTEM_PROMPT = """
            You are a financial analyst assistant answering questions about SEC filings.
            Use ONLY the numbered context excerpts below to answer. If the answer is not
            present in the context, say "I cannot answer this from the provided documents."

            When you use information from an excerpt, cite it inline as [n], where n is
            the excerpt number. Be precise. Do not invent numbers, dates, or names.
            """;

    private final ChatClient chatClient;
    private final RetrievalService retrievalService;

    public QaService(ChatClient.Builder chatClientBuilder, RetrievalService retrievalService) {
        this.chatClient = chatClientBuilder.build();
        this.retrievalService = retrievalService;
    }

    public QaResponse answer(String question) {
        List<Document> contextDocs = retrievalService.retrieve(question);

        if (contextDocs.isEmpty()) {
            return new QaResponse(
                    "I cannot answer this from the provided documents.",
                    List.of());
        }

        String contextBlock = IntStream.range(0, contextDocs.size())
                .mapToObj(i -> formatExcerpt(i + 1, contextDocs.get(i)))
                .collect(Collectors.joining("\n\n"));

        String userPrompt = """
                Context excerpts:
                %s

                Question: %s
                """.formatted(contextBlock, question);

        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        List<Citation> citations = IntStream.range(0, contextDocs.size())
                .mapToObj(i -> toCitation(i + 1, contextDocs.get(i)))
                .toList();

        return new QaResponse(answer, citations);
    }

    private String formatExcerpt(int index, Document doc) {
        Object page = doc.getMetadata().get("page_number");
        String filename = String.valueOf(doc.getMetadata().getOrDefault("filename", "unknown"));
        String header = "[%d] %s%s".formatted(
                index,
                filename,
                page != null ? " (page " + page + ")" : "");
        return header + "\n" + doc.getText();
    }

    private Citation toCitation(int index, Document doc) {
        String documentId = String.valueOf(doc.getMetadata().getOrDefault("document_id", ""));
        String filename = String.valueOf(doc.getMetadata().getOrDefault("filename", "unknown"));
        Object page = doc.getMetadata().get("page_number");
        Integer pageNumber = page instanceof Number n ? n.intValue() : null;
        return new Citation(index, documentId, filename, pageNumber, doc.getText());
    }
}
