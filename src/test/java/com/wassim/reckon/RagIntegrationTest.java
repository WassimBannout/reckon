package com.wassim.reckon;

import com.wassim.reckon.dto.DocumentResponse;
import com.wassim.reckon.dto.QaRequest;
import com.wassim.reckon.dto.QaResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RagIntegrationTest.TestStubs.class)
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.vectorstore.pgvector.dimensions=64",
        "rag.similarity-threshold=0.0"
})
class RagIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("reckon")
            .withUsername("reckon")
            .withPassword("reckon");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void ingestThenQuery_returnsCitedAnswer() throws IOException {
        byte[] pdfBytes = generatePdf(
                "Apple Inc reported total revenue of $383 billion for fiscal year 2023. " +
                "Net income was $97 billion. The company has 161,000 full-time employees.");

        DocumentResponse ingested = uploadPdf(pdfBytes, "apple-10k.pdf");
        assertThat(ingested.id()).isNotNull();
        assertThat(ingested.chunkCount()).isPositive();
        assertThat(ingested.filename()).isEqualTo("apple-10k.pdf");

        ResponseEntity<DocumentResponse[]> listResp =
                restTemplate.getForEntity("/api/documents", DocumentResponse[].class);
        assertThat(listResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(listResp.getBody()).hasSize(1);

        QaRequest qaRequest = new QaRequest("What was Apple's revenue in fiscal 2023?");
        ResponseEntity<QaResponse> qaResp =
                restTemplate.postForEntity("/api/qa", qaRequest, QaResponse.class);

        assertThat(qaResp.getStatusCode().is2xxSuccessful()).isTrue();
        QaResponse body = qaResp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.answer()).isNotBlank();
        assertThat(body.citations()).isNotEmpty();
        assertThat(body.citations().get(0).filename()).isEqualTo("apple-10k.pdf");
    }

    private DocumentResponse uploadPdf(byte[] bytes, String filename) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<DocumentResponse> resp = restTemplate.postForEntity(
                "/api/documents",
                new HttpEntity<>(body, headers),
                DocumentResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private byte[] generatePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @TestConfiguration
    static class TestStubs {

        private static final int DIM = 64;

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new EmbeddingModel() {
                @Override
                public float[] embed(Document document) {
                    return deterministicVector(document.getText());
                }

                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    List<Embedding> embeddings = request.getInstructions().stream()
                            .map(this::toEmbedding)
                            .toList();
                    return new EmbeddingResponse(embeddings);
                }

                private Embedding toEmbedding(String text) {
                    int index = 0;
                    return new Embedding(deterministicVector(text), index);
                }

                @Override
                public int dimensions() {
                    return DIM;
                }
            };
        }

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return prompt -> {
                String canned = "Apple reported revenue of $383 billion in fiscal 2023 [1].";
                AssistantMessage message = new AssistantMessage(canned);
                Generation generation = new Generation(message, ChatGenerationMetadata.NULL);
                return new ChatResponse(List.of(generation));
            };
        }

        private static float[] deterministicVector(String text) {
            // Constant unit vector so cosine similarity is always 1.0.
            // This test validates plumbing (ingest -> retrieve -> answer), not
            // retrieval quality. Real embeddings provide semantic similarity;
            // a stub that mimics that would just reimplement an embedding model.
            float[] vec = new float[DIM];
            float v = (float) (1.0 / Math.sqrt(DIM));
            for (int i = 0; i < DIM; i++) {
                vec[i] = v;
            }
            return vec;
        }
    }
}
