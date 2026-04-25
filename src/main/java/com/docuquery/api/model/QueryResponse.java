package com.docuquery.api.model;

public record QueryResponse(
    String answer,
    String documentId
) {}