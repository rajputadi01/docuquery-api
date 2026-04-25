package com.docuquery.api.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class RAGService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    // Inject our AI Beans
    public RAGService(ChatLanguageModel chatLanguageModel, EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    public String queryDocument(String documentId, String question) {
        
        // 1. Convert the user's question into a vector
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Search the vector store for the most similar chunks
        // INTERVIEW POINT: Notice the Metadata Filter. This ensures we only search 
        // the specific PDF the user asked about, preventing cross-document contamination.
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(5) // Get the top 5 most relevant chunks
                .filter(metadataKey("documentId").isEqualTo(documentId))
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        // 3. Assemble the retrieved chunks into a single context string
        StringBuilder contextBuilder = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
            contextBuilder.append(match.embedded().text()).append("\n\n");
        }
        String context = contextBuilder.toString();

        if (context.trim().isEmpty()) {
            return "No relevant information found in the document to answer your question.";
        }

        // 4. The AI Guardrails (System Prompt)
        // INTERVIEW POINT: This strictly limits the LLM to the document context, preventing hallucinations.
        String systemPrompt = "You are an enterprise document intelligence assistant. " +
                "Your job is to answer the user's question based strictly and ONLY on the provided document context. " +
                "If the answer cannot be found within the context, you must reply exactly with: 'Information not found in document.' " +
                "Do not use outside knowledge. Do not hallucinate.";

        String userPrompt = "Context Information:\n" + context + "\n\nUser Question:\n" + question;

        // 5. Send the prompt to Gemini and return the answer
        return chatLanguageModel.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
        ).content().text();
    }
}