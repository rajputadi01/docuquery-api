package com.docuquery.api.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
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

import com.docuquery.api.exception.DocumentNotFoundException;
import com.docuquery.api.model.DocumentSummary;
import com.docuquery.api.model.QueryResponse;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RAGService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    
    private final MetadataExtractor metadataExtractor;
    private final DocumentService documentService;
    
    // STATE STORES
    private final Map<String, DocumentSummary> summaryCache = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> chatSessions = new ConcurrentHashMap<>();
    private final Set<String> deletedDocumentIds = ConcurrentHashMap.newKeySet();

    public RAGService(ChatLanguageModel chatLanguageModel, 
                      EmbeddingModel embeddingModel, 
                      EmbeddingStore<TextSegment> embeddingStore,
                      MetadataExtractor metadataExtractor,
                      DocumentService documentService) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.metadataExtractor = metadataExtractor;
        this.documentService = documentService;
    }
    
    // DELETION MECHANISM
    public void deleteDocumentContext(String documentId) {
        summaryCache.remove(documentId);
        chatSessions.remove(documentId);
        deletedDocumentIds.add(documentId);
    }

    public DocumentSummary generateSummary(String documentId) {
        if (deletedDocumentIds.contains(documentId)) {
            throw new DocumentNotFoundException("Document has been deleted.");
        }
        if (summaryCache.containsKey(documentId)) {
            return summaryCache.get(documentId);
        }

        String text = documentService.getRawDocumentText(documentId);
        DocumentSummary summary = metadataExtractor.extractMetadata(text);
        summaryCache.put(documentId, summary);
        
        return summary;
    }

    public QueryResponse queryDocument(String documentId, String question) {
        // Soft-delete guardrail
        if (deletedDocumentIds.contains(documentId)) {
            throw new DocumentNotFoundException("Document has been deleted.");
        }

        // 1. Convert question to vector
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Semantic Search
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(3)
                .filter(metadataKey("documentId").isEqualTo(documentId))
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        // 3. Extract Context, Sources, and Confidence Score
        StringBuilder contextBuilder = new StringBuilder();
        List<String> sources = new ArrayList<>();
        double highestScore = 0.0;

        for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
            String chunkText = match.embedded().text();
            contextBuilder.append(chunkText).append("\n\n");
            sources.add(chunkText);
            
            if (match.score() > highestScore) {
                highestScore = match.score();
            }
        }
        
        String context = contextBuilder.toString();
        if (context.trim().isEmpty()) {
            return new QueryResponse("No relevant information found.", documentId, 0.0, sources);
        }

        // 4. Load Chat History
        List<ChatMessage> history = chatSessions.getOrDefault(documentId, new ArrayList<>());

        // 5. System Prompt (Must include context for RAG)
        String systemPrompt = "You are an enterprise document intelligence assistant. " +
                "Answer the user's question based strictly on the provided context. " +
                "Do not hallucinate.\n\nContext:\n" + context;

        // 6. Build the Message Payload
        List<ChatMessage> messagesToSend = new ArrayList<>();
        messagesToSend.add(SystemMessage.from(systemPrompt));
        messagesToSend.addAll(history); // Inject past conversation
        messagesToSend.add(UserMessage.from(question)); // Add new question

        // 7. Hit Gemini API
        AiMessage aiResponse = chatLanguageModel.generate(messagesToSend).content();

        // 8. Update Chat History
        history.add(UserMessage.from(question));
        history.add(aiResponse);
        
        // Prune to last 6 messages (3 complete turns) to save tokens
        if (history.size() > 6) {
            history = history.subList(history.size() - 6, history.size());
        }
        chatSessions.put(documentId, history);

        // 9. Return the fully auditable response
        return new QueryResponse(aiResponse.text(), documentId, highestScore, sources);
    }
}