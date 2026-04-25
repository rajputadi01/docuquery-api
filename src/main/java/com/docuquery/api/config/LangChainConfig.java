package com.docuquery.api.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.docuquery.api.service.MetadataExtractor;
import dev.langchain4j.service.AiServices;

@Configuration
public class LangChainConfig {

    // Pulls the API key from your application.properties / environment variables
    @Value("${gemini.api.key}")
    private String geminiApiKey;

    /**
     * The Chat Model is responsible for reading the context and generating the final answer.
     * Interview Point: Mention that you set the temperature low (0.3) intentionally 
     * to prevent hallucinations and force the model to be strictly factual.
     */
    /**
     * The Chat Model is responsible for reading the context and generating the final answer.
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                // UPDATE THIS LINE: Use the current generation Gemini model
                .modelName("gemini-2.5-flash") 
                .temperature(0.3) 
                .build();
    }

    /**
     * The Embedding Model converts chunks of text into vector arrays.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(geminiApiKey)
                // UPDATE THIS LINE: Use the new 2026 Gemini Embedding Model
                .modelName("gemini-embedding-2") 
                .build();
    }

    /**
     * The Vector Store holds the embeddings.
     * Interview Point: Explain this is an interface. While you use an InMemory store here,
     * the architecture allows swapping this out for Pinecone or pgvector in production 
     * with zero changes to the underlying service logic.
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }
    
    /**
     * Creates the declarative AI service for structured data extraction.
     */
    @Bean
    public MetadataExtractor metadataExtractor(ChatLanguageModel chatLanguageModel) {
        return AiServices.create(MetadataExtractor.class, chatLanguageModel);
    }
}