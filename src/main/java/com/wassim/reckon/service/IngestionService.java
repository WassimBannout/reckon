package com.wassim.reckon.service;

import com.wassim.reckon.config.RagProperties;
import com.wassim.reckon.exception.IngestionException;
import com.wassim.reckon.model.Document;
import com.wassim.reckon.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final RagProperties ragProperties;

    public IngestionService(VectorStore vectorStore,
                            DocumentRepository documentRepository,
                            RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
        this.ragProperties = ragProperties;
    }

    @Transactional
    public Document ingest(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IngestionException("Uploaded file must have a filename");
        }

        try {
            byte[] bytes = file.getBytes();
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource);
            List<org.springframework.ai.document.Document> pages = reader.get();

            TokenTextSplitter splitter = new TokenTextSplitter(
                    ragProperties.getChunkSize(),
                    ragProperties.getChunkOverlap(),
                    5,
                    10_000,
                    true);
            List<org.springframework.ai.document.Document> chunks = splitter.apply(pages);

            UUID documentId = UUID.randomUUID();
            for (org.springframework.ai.document.Document chunk : chunks) {
                chunk.getMetadata().put("document_id", documentId.toString());
                chunk.getMetadata().put("filename", filename);
            }

            vectorStore.add(chunks);

            Document document = new Document(filename, chunks.size(), Instant.now());
            Document saved = documentRepository.save(document);
            log.info("Ingested document {} ({} chunks)", filename, chunks.size());
            return saved;
        } catch (IOException e) {
            throw new IngestionException("Failed to read uploaded file: " + filename, e);
        } catch (RuntimeException e) {
            throw new IngestionException("Failed to ingest document: " + filename, e);
        }
    }
}
