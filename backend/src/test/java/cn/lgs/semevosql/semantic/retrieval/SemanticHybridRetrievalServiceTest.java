/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.semantic.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticHybridRetrievalServiceTest {

	private SemanticRetrievalDocumentRepository documentRepository;

	private SemanticRetrievalIndexService indexService;

	private RerankModelProvider rerankModelProvider;

	private SemanticHybridRetrievalService service;

	@BeforeEach
	void setUp() {
		documentRepository = mock(SemanticRetrievalDocumentRepository.class);
		indexService = mock(SemanticRetrievalIndexService.class);
		rerankModelProvider = mock(RerankModelProvider.class);
		service = new SemanticHybridRetrievalService(documentRepository, indexService, rerankModelProvider);
		when(indexService.vectorScores(anyLong(), anyLong(), anyString(), anyString(), any(), anyInt()))
			.thenReturn(Map.of());
		when(documentRepository.findCatalog(1L, 2L, "catalog")).thenReturn(List.of(
				document("metric:a", "metric amount", "metric amount"),
				document("metric:b", "amount alternative", "the exact business payment amount requested")));
	}

	@Test
	void rerankerCanReorderRrfCandidatesAndKeepsEvidence() {
		when(rerankModelProvider.currentRerankModel()).thenReturn((query, documents, topN) -> List.of(
				new RerankModel.RerankScore(1, 0.99d), new RerankModel.RerankScore(0, 0.25d)));

		List<SemanticHybridRetrievalService.RetrievalHit> hits = service.retrieve(1L, 2L, "catalog", "metric amount", 2);

		assertThat(hits).extracting(SemanticHybridRetrievalService.RetrievalHit::assetKey)
			.containsExactly("metric:b", "metric:a");
		assertThat(hits.get(0).channelRanks()).containsKeys("RRF", "RERANK");
		assertThat(hits.get(0).channelScores()).containsEntry("RERANK", 0.99d).containsKey("RRF");
	}

	@Test
	void rerankerUsesBoundedTopCandidatesAndKeepsRrfTail() {
		List<SemanticRetrievalDocument> documents = IntStream.range(0, 12)
			.mapToObj(index -> document("metric:" + index, "metric amount " + index, "metric amount document " + index))
			.toList();
		when(documentRepository.findCatalog(1L, 2L, "catalog")).thenReturn(documents);
		AtomicInteger rerankedDocumentCount = new AtomicInteger();
		when(rerankModelProvider.currentRerankModel()).thenReturn((query, rerankDocuments, topN) -> {
			rerankedDocumentCount.set(rerankDocuments.size());
			return IntStream.range(0, rerankDocuments.size())
				.mapToObj(index -> new RerankModel.RerankScore(index, 1d - index * 0.01d))
				.toList();
		});

		List<SemanticHybridRetrievalService.RetrievalHit> hits = service.retrieve(1L, 2L, "catalog", "metric amount", 12);

		assertThat(rerankedDocumentCount).hasValue(4);
		assertThat(hits).hasSize(12);
		assertThat(hits.subList(0, 4)).allSatisfy(hit -> assertThat(hit.channelRanks()).containsKey("RERANK"));
		assertThat(hits.subList(4, 12)).allSatisfy(hit -> assertThat(hit.channelRanks()).doesNotContainKey("RERANK"));
	}

	@Test
	void missingRerankerFallsBackToGovernedRrfCandidates() {
		when(rerankModelProvider.currentRerankModel())
			.thenThrow(new IllegalStateException("No active RERANK model configured."));

		List<SemanticHybridRetrievalService.RetrievalHit> hits = service.retrieve(1L, 2L, "catalog", "metric amount", 1);

		assertThat(hits).singleElement().satisfies(hit -> {
			assertThat(hit.assetKey()).isEqualTo("metric:a");
			assertThat(hit.channelRanks()).containsKey("RRF").doesNotContainKey("RERANK");
		});
	}

	@Test
	void rerankerFailureFallsBackWithoutFailingRetrieval() {
		when(rerankModelProvider.currentRerankModel()).thenReturn((query, documents, topN) -> {
			throw new IllegalStateException("rerank unavailable");
		});

		List<SemanticHybridRetrievalService.RetrievalHit> hits = service.retrieve(1L, 2L, "catalog", "metric amount", 2);

		assertThat(hits).extracting(SemanticHybridRetrievalService.RetrievalHit::assetKey)
			.containsExactly("metric:a", "metric:b");
		assertThat(hits).allSatisfy(hit -> assertThat(hit.channelRanks()).doesNotContainKey("RERANK"));
	}

	private SemanticRetrievalDocument document(String assetKey, String lexical, String semantic) {
		return new SemanticRetrievalDocument(assetKey, 1L, 2L, "catalog", SemanticRetrievalDocument.DocumentType.METRIC,
				"METRIC", assetKey, 1, "orders", "orders", lexical, semantic, "source", "content", "test", "1",
				"ENRICHED");
	}

}
