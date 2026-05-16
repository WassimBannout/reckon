# reckon

[![CI](https://github.com/WassimBannout/reckon/actions/workflows/ci.yml/badge.svg)](https://github.com/WassimBannout/reckon/actions/workflows/ci.yml)

A small Spring Boot service that ingests SEC 10-K filings and answers questions
about them with **inline source citations**, using Retrieval-Augmented Generation
(RAG) built on Spring AI and pgvector.

> *reckon* — verb. (1) to calculate or figure out; (2) to settle accounts.
> Both senses apply: the service reckons what a filing says, and reckons with
> the numbers in it.

This is a focused proof-of-concept, not a production system. The goal is to
demonstrate clean Spring fundamentals and end-to-end RAG mechanics in a small,
reviewable codebase.

---

## Problem

Analysts reading SEC 10-K filings ask the same questions repeatedly across
filings — revenue, net income, headcount, risk factors, segment performance.
Manually finding answers is slow, and asking a general-purpose LLM directly is
unsafe: it will hallucinate numbers and cite nothing.

This service ingests a 10-K PDF, splits it into chunks, embeds them into a
pgvector store, and at query time retrieves the most relevant passages, then
asks an LLM to answer **using only those passages, with inline `[n]` citations
back to the source excerpts**.

If the retrieved context doesn't contain the answer, the system is instructed
to say so rather than guess. This citation-grounded approach is what makes RAG
useful in regulated domains like finance.

---

## Architecture

```
                ┌────────────────────────────────────┐
                │             Client                 │
                │   curl / Postman / future UI       │
                └────────────────┬───────────────────┘
                                 │ HTTP/JSON
                ┌────────────────▼───────────────────┐
                │       Spring Boot REST API         │
                │  DocumentController │ QaController │
                └─────┬──────────────────────┬───────┘
                      │                      │
            ┌─────────▼────────┐    ┌────────▼────────┐
            │ IngestionService │    │    QaService    │
            │  PDF → chunks    │    │   prompt + LLM  │
            └─────────┬────────┘    └────────┬────────┘
                      │                      │
                      │           ┌──────────▼─────────┐
                      │           │ RetrievalService   │
                      │           │ vector similarity  │
                      │           └──────────┬─────────┘
                      │                      │
            ┌─────────▼──────────────────────▼─────────┐
            │             Spring AI                    │
            │   ChatClient   │   EmbeddingModel        │
            │              VectorStore                 │
            └─────────┬──────────────────────┬─────────┘
                      │                      │
            ┌─────────▼────────┐    ┌────────▼────────┐
            │  PostgreSQL +    │    │  OpenAI API     │
            │     pgvector     │    │  (chat + embed) │
            └──────────────────┘    └─────────────────┘
```

**Layering:** controllers handle HTTP only and contain no business logic.
Services own the RAG flow. Repositories own persistence. DTOs are records.
Exceptions flow into a single `@RestControllerAdvice`.

---

## Key design choices and trade-offs

| Choice | Why | Trade-off |
|---|---|---|
| **pgvector** instead of Pinecone/Qdrant | Single Docker Compose — a reviewer runs the whole system locally with one command, no SaaS account needed. Also keeps source of truth (document metadata) and embeddings in one database. | At larger scale, a dedicated vector DB outperforms pgvector. Fine for this scope. |
| **Spring AI** instead of Python + LangChain | Aligns with Murex's stack; demonstrates AI integration on the JVM. | Python ecosystem has more RAG tooling. |
| **Spring AI `TokenTextSplitter`** with 800-token chunks, 100-token overlap | Token-aware splitting respects the embedding model's context window. Overlap reduces the risk of cutting an answer mid-sentence. | Naive — does not respect document structure (sections, tables). See "next steps". |
| **`PagePdfDocumentReader`** (page-by-page) | Preserves `page_number` metadata, which is what makes citations meaningful to an analyst. | Tables and footnotes still get flattened to text. |
| **Citation in the prompt** with `[n]` markers | Forces the LLM to ground its answer in retrieved context. The retrieved excerpts are returned alongside the answer so the user can verify. | Citation discipline depends on the LLM following instructions; not enforced post-hoc. |
| **`similarityThreshold = 0.65`** | Empirical default; tune per embedding model. Filters out weak matches that would otherwise dilute the prompt. | Too high → "I cannot answer" on valid questions. Too low → noise. Needs an eval set to tune properly. |
| **Ollama** (`gemma3:4b` chat + `nomic-embed-text` embeddings) | Zero-cost, runs offline, no API key required, no vendor lock-in. Lets a reviewer clone and run the project end-to-end without signing up for anything. | Smaller models follow the citation format less reliably than hosted frontier models; quality of generated answers is noticeably lower than gpt-4o-mini or Claude. Production would swap to a hosted provider. |

---

## Running locally

### Prerequisites
- JDK 21
- Maven 3.9+
- Docker (for pgvector and Testcontainers)
- [Ollama](https://ollama.com) running locally with two models pulled:
  ```bash
  ollama pull gemma3:4b           # chat
  ollama pull nomic-embed-text    # embeddings
  ```

### Start dependencies
```bash
docker compose up -d
```

### Run the app
```bash
./mvnw spring-boot:run
```

The app listens on `http://localhost:8080`.

### Try it
```bash
# 1. Ingest a 10-K (download one from https://www.sec.gov/edgar)
curl -F "file=@aapl-10k-2023.pdf" http://localhost:8080/api/documents

# 2. List ingested documents
curl http://localhost:8080/api/documents

# 3. Ask a question
curl -X POST http://localhost:8080/api/qa \
  -H 'Content-Type: application/json' \
  -d '{"question":"What was total revenue in fiscal 2023?"}'
```

Response shape:
```json
{
  "answer": "Apple reported total net sales of $383.3 billion in fiscal 2023 [1].",
  "citations": [
    {
      "index": 1,
      "documentId": "…",
      "filename": "aapl-10k-2023.pdf",
      "pageNumber": 28,
      "excerpt": "Total net sales for the year ended September 30, 2023 were $383,285 million…"
    }
  ]
}
```

---

## Tests

```bash
./mvnw test
```

The integration test (`RagIntegrationTest`) spins up a pgvector container via
Testcontainers and stubs the embedding and chat models with deterministic
in-process fakes. This proves the full ingest → retrieve → answer round trip
without depending on an external LLM API.

**Note on Docker API version:** docker-java defaults to API 1.32, which newer
Docker daemons (29+) reject. The Surefire plugin in `pom.xml` sets
`api.version=1.44` and `DOCKER_API_VERSION=1.44` to negotiate a compatible
version. Bump these if your daemon requires a newer minimum.

---

## What I'd build next (deferred for scope, not because they don't matter)

This is a one-day proof-of-concept. The following are the gaps I'd close to
make it production-grade:

1. **Evaluation framework.** A golden set of question/answer/expected-citation
   triples, with metrics for retrieval recall@k, answer faithfulness, and
   hallucination rate. Without this, "improving the RAG" is guesswork.
2. **Hybrid retrieval + reranking.** Dense vector search misses keyword-exact
   matches (e.g., ticker symbols, GAAP line items). BM25 + dense + a cross-encoder
   reranker measurably improves recall on financial text.
3. **Structure-aware ingestion.** 10-Ks contain tables, footnotes, and
   cross-references that flat text extraction loses. A pass that detects and
   preserves tables (e.g., via Camelot or Unstructured) would materially
   improve numerical questions.
4. **Tool use for arithmetic.** LLMs hallucinate when asked to compute
   (e.g., "revenue growth from 2022 to 2023"). A calculator tool the model
   can invoke is the standard fix.
5. **Async ingestion.** Large 10-Ks (300+ pages) shouldn't block an HTTP
   request. A queue (Spring Cloud Stream over RabbitMQ, or Kafka) with a
   status endpoint is the right shape.
6. **Observability.** Structured logging with correlation IDs, Micrometer
   metrics, OpenTelemetry traces, and per-query cost tracking (tokens in/out).
7. **AuthN/Z and multi-tenancy.** Documents and queries scoped to a tenant ID,
   with row-level filtering on the vector store.
8. **Streaming responses.** `ChatClient.stream()` for token-by-token answers
   over server-sent events.
9. **Schema migrations.** Replace `ddl-auto=update` with Flyway.

---

## Project layout

```
src/main/java/com/wassim/reckon/
├── ReckonApplication.java
├── config/
│   ├── AppConfig.java
│   └── RagProperties.java
├── controller/
│   ├── DocumentController.java
│   └── QaController.java
├── dto/
│   ├── Citation.java
│   ├── DocumentResponse.java
│   ├── ErrorResponse.java
│   ├── QaRequest.java
│   └── QaResponse.java
├── exception/
│   ├── GlobalExceptionHandler.java
│   └── IngestionException.java
├── model/
│   └── Document.java
├── repository/
│   └── DocumentRepository.java
└── service/
    ├── IngestionService.java
    ├── QaService.java
    └── RetrievalService.java
```
