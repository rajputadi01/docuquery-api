package com.docuquery.api.controller;

import com.docuquery.api.model.DocumentSummary;
import com.docuquery.api.model.QueryRequest;
import com.docuquery.api.model.QueryResponse;
import com.docuquery.api.model.UploadResponse;
import com.docuquery.api.service.DocumentService;
import com.docuquery.api.service.RAGService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final RAGService ragService;
    private final com.docuquery.api.service.MetadataExtractor metadataExtractor;

    // Updated Constructor
    public DocumentController(DocumentService documentService, RAGService ragService, com.docuquery.api.service.MetadataExtractor metadataExtractor) {
        this.documentService = documentService;
        this.ragService = ragService;
        this.metadataExtractor = metadataExtractor;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadDocument(@RequestParam("file") MultipartFile file) {
        String documentId = documentService.processAndStoreDocument(file);
        UploadResponse response = new UploadResponse(
            documentId,
            file.getOriginalFilename(),
            "Document successfully uploaded and parsed."
        );
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PostMapping("/{id}/query")
    public ResponseEntity<QueryResponse> queryDocument(
            @PathVariable("id") String documentId,
            @Valid @RequestBody QueryRequest request) { // @Valid enforces the @NotBlank rule
        
        // Guardrail: Will throw a 404 before hitting the AI if the ID is wrong
        documentService.verifyDocumentExists(documentId); 
        
        String answer = ragService.queryDocument(documentId, request.question());
        
        return ResponseEntity.ok(new QueryResponse(answer, documentId));
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<DocumentSummary> getDocumentSummary(@PathVariable("id") String documentId) {
        
        // Guardrail: Will throw a 404 before hitting the AI if the ID is wrong
        documentService.verifyDocumentExists(documentId); 
        
        String rawText = documentService.getRawDocumentText(documentId);
        DocumentSummary summary = metadataExtractor.extractMetadata(rawText);
        
        return ResponseEntity.ok(summary);
    }
}