package com.docuquery.api.controller;

import com.docuquery.api.exception.CapacityExceededException;
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

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final RAGService ragService;

    public DocumentController(DocumentService documentService, RAGService ragService) {
        this.documentService = documentService;
        this.ragService = ragService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadDocument(@RequestParam("file") MultipartFile file) 
    {
        if (documentService.getDocumentCount() >= 5) 
        {
            throw new CapacityExceededException("Server capacity reached. Maximum of 5 active documents allowed. Please delete an existing document first.");
        }
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
            @Valid @RequestBody QueryRequest request) { 
        
        documentService.verifyDocumentExists(documentId); 
        
        // RAGService now returns the fully populated QueryResponse object directly
        QueryResponse response = ragService.queryDocument(documentId, request.question());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<DocumentSummary> getDocumentSummary(@PathVariable("id") String documentId) {
        documentService.verifyDocumentExists(documentId); 
        DocumentSummary summary = ragService.generateSummary(documentId);
        return ResponseEntity.ok(summary);
    }

    // NEW: Deletion Endpoint
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable("id") String documentId) {
        // 1. Remove from DocumentService (Raw Text)
        documentService.deleteDocument(documentId);
        
        // 2. Remove from RAGService (Caches, Memory, and apply Soft Delete)
        ragService.deleteDocumentContext(documentId);
        
        return ResponseEntity.noContent().build(); // Returns a clean HTTP 204
    }
}