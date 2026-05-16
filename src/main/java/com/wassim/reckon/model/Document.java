package com.wassim.reckon.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String filename;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected Document() {
    }

    public Document(String filename, int chunkCount, Instant ingestedAt) {
        this.filename = filename;
        this.chunkCount = chunkCount;
        this.ingestedAt = ingestedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }
}
