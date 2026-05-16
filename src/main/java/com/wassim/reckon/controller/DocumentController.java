package com.wassim.reckon.controller;

import com.wassim.reckon.dto.DocumentResponse;
import com.wassim.reckon.exception.IngestionException;
import com.wassim.reckon.model.Document;
import com.wassim.reckon.repository.DocumentRepository;
import com.wassim.reckon.service.IngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestionService ingestionService;
    private final DocumentRepository documentRepository;

    public DocumentController(IngestionService ingestionService,
                              DocumentRepository documentRepository) {
        this.ingestionService = ingestionService;
        this.documentRepository = documentRepository;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> ingest(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IngestionException("Uploaded file is empty");
        }
        Document saved = ingestionService.ingest(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.from(saved));
    }

    @GetMapping
    public List<DocumentResponse> list() {
        return documentRepository.findAll().stream()
                .map(DocumentResponse::from)
                .toList();
    }
}
