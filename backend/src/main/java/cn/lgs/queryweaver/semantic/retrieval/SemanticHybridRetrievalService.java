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
package cn.lgs.queryweaver.semantic.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Runtime orchestration boundary for Exact + BM25 + pgvector retrieval and RRF. */
@Service
public class SemanticHybridRetrievalService {

	private static final double BM25_K1 = 1.2d;

	private static final double BM25_B = 0.75d;

	private static final double RRF_K = 60d;

	private static final Pattern LATIN_OR_NUMBER = Pattern.compile("[\\p{L}\\p{N}_:.]+",
			Pattern.UNICODE_CHARACTER_CLASS);

	private static final Logger log = LoggerFactory.getLogger(SemanticHybridRetrievalService.class);

	private final SemanticRetrievalDocumentRepository documentRepository;

	private final SemanticRetrievalIndexService indexService;

	public SemanticHybridRetrievalService(SemanticRetrievalDocumentRepository documentRepository,
			SemanticRetrievalIndexService indexService) {
		this.documentRepository = documentRepository;
		this.indexService = indexService;
	}

	public List<RetrievalHit> retrieve(Long projectId, Long projectVersionId, String catalogHash, String query,
			int limit) {
		return retrieve(projectId, projectVersionId, catalogHash, query, SemanticRetrievalScope.all(), RetrievalMode.HYBRID,
				limit);
	}

	public List<RetrievalHit> retrieve(Long projectId, Long projectVersionId, String catalogHash, String query,
			RetrievalMode mode, int limit) {
		return retrieve(projectId, projectVersionId, catalogHash, query, SemanticRetrievalScope.all(), mode, limit);
	}

	public List<RetrievalHit> retrieve(Long projectId, Long projectVersionId, String catalogHash, String query,
			SemanticRetrievalScope scope, int limit) {
		return retrieve(projectId, projectVersionId, catalogHash, query, scope, RetrievalMode.HYBRID, limit);
	}

	public List<RetrievalHit> retrieve(Long projectId, Long projectVersionId, String catalogHash, String query,
			SemanticRetrievalScope scope, RetrievalMode mode, int limit) {
		if (projectId == null || projectVersionId == null || !StringUtils.hasText(catalogHash)
				|| !StringUtils.hasText(query) || limit <= 0) {
			return List.of();
		}
		SemanticRetrievalScope effectiveScope = scope == null ? SemanticRetrievalScope.all() : scope;
		RetrievalMode effectiveMode = mode == null ? RetrievalMode.HYBRID : mode;
		List<SemanticRetrievalDocument> documents = documentRepository
			.findCatalog(projectId, projectVersionId, catalogHash)
			.stream()
			.filter(effectiveScope::matches)
			.toList();
		if (documents.isEmpty()) {
			return List.of();
		}
		List<String> queryTerms = tokenize(query);
		if (queryTerms.isEmpty()) {
			return List.of();
		}
		Map<String, Double> exactScores = effectiveMode.includesExact() ? exactScores(documents, query, queryTerms) : Map.of();
		Map<String, Double> bm25Scores = effectiveMode.includesBm25() ? bm25Scores(documents, queryTerms) : Map.of();
		Map<String, Double> vectorScores = Map.of();
		if (effectiveMode.includesVector()) {
			try {
				vectorScores = indexService.vectorScores(projectId, projectVersionId, catalogHash, query, effectiveScope,
						Math.max(50, limit * 8));
			}
			catch (SemanticRetrievalIndexService.EmbeddingReindexRequiredException ex) {
				log.warn("Semantic Catalog vector index requires reindex; continuing without vector channel: {}",
						ex.getMessage());
			}
		}
		Map<String, Integer> exactRanks = ranks(exactScores);
		Map<String, Integer> bm25Ranks = ranks(bm25Scores);
		Map<String, Integer> vectorRanks = ranks(vectorScores);
		Map<String, SemanticRetrievalDocument> byId = documents.stream()
			.collect(Collectors.toMap(SemanticRetrievalDocument::id, value -> value, (left, right) -> left));
		Set<String> candidateIds = new LinkedHashSet<>();
		candidateIds.addAll(exactRanks.keySet());
		candidateIds.addAll(bm25Ranks.keySet());
		candidateIds.addAll(vectorRanks.keySet());
		List<RetrievalHit> hits = new ArrayList<>();
		for (String id : candidateIds) {
			SemanticRetrievalDocument document = byId.get(id);
			if (document == null) {
				continue;
			}
			LinkedHashMap<String, Integer> channelRanks = new LinkedHashMap<>();
			LinkedHashMap<String, Double> channelScores = new LinkedHashMap<>();
			double rrf = 0d;
			if (exactRanks.containsKey(id)) {
				channelRanks.put("EXACT", exactRanks.get(id));
				channelScores.put("EXACT", exactScores.get(id));
				rrf += 2d / (RRF_K + exactRanks.get(id));
			}
			if (bm25Ranks.containsKey(id)) {
				channelRanks.put("BM25", bm25Ranks.get(id));
				channelScores.put("BM25", bm25Scores.get(id));
				rrf += 1d / (RRF_K + bm25Ranks.get(id));
			}
			if (vectorRanks.containsKey(id)) {
				channelRanks.put("VECTOR", vectorRanks.get(id));
				channelScores.put("VECTOR", vectorScores.get(id));
				rrf += 1d / (RRF_K + vectorRanks.get(id));
			}
			hits.add(new RetrievalHit(document.documentType(), document.assetType(), document.assetKey(),
					document.modelCode(), document.physicalTable(), rrf, Map.copyOf(channelRanks),
					Map.copyOf(channelScores)));
		}
		return hits.stream()
			.sorted(Comparator.comparingDouble(RetrievalHit::score)
				.reversed()
				.thenComparing(RetrievalHit::modelCode)
				.thenComparing(RetrievalHit::assetKey))
			.limit(limit)
			.toList();
	}

	private Map<String, Double> exactScores(List<SemanticRetrievalDocument> documents, String query,
			List<String> queryTerms) {
		String normalizedQuery = normalize(query);
		Map<String, Double> scores = new LinkedHashMap<>();
		for (SemanticRetrievalDocument document : documents) {
			String normalized = normalize(document.lexicalText());
			double score = 0d;
			if (normalized.contains(normalizedQuery)) {
				score += 5d;
			}
			Set<String> terms = new LinkedHashSet<>(tokenize(document.lexicalText()));
			long overlap = queryTerms.stream().filter(terms::contains).distinct().count();
			if (overlap > 0) {
				score += overlap / (double) Math.max(1, new LinkedHashSet<>(queryTerms).size());
			}
			if (score > 0d) {
				scores.put(document.id(), score);
			}
		}
		return scores;
	}

	private Map<String, Double> bm25Scores(List<SemanticRetrievalDocument> documents, List<String> queryTerms) {
		Map<String, List<String>> termsByDocument = new LinkedHashMap<>();
		for (SemanticRetrievalDocument document : documents) {
			termsByDocument.put(document.id(), tokenize(document.lexicalText()));
		}
		double averageLength = termsByDocument.values().stream().mapToInt(List::size).average().orElse(1d);
		Map<String, Integer> documentFrequency = new HashMap<>();
		for (List<String> terms : termsByDocument.values()) {
			for (String term : new LinkedHashSet<>(terms)) {
				documentFrequency.merge(term, 1, Integer::sum);
			}
		}
		int totalDocuments = documents.size();
		Map<String, Double> result = new LinkedHashMap<>();
		for (SemanticRetrievalDocument document : documents) {
			List<String> terms = termsByDocument.get(document.id());
			Map<String, Long> frequencies = terms.stream()
				.collect(Collectors.groupingBy(value -> value, Collectors.counting()));
			double score = 0d;
			for (String queryTerm : new LinkedHashSet<>(queryTerms)) {
				long frequency = frequencies.getOrDefault(queryTerm, 0L);
				if (frequency == 0) {
					continue;
				}
				int df = documentFrequency.getOrDefault(queryTerm, 0);
				double idf = Math.log(1d + (totalDocuments - df + 0.5d) / (df + 0.5d));
				double denominator = frequency
						+ BM25_K1 * (1d - BM25_B + BM25_B * terms.size() / Math.max(averageLength, 1d));
				score += idf * (frequency * (BM25_K1 + 1d)) / denominator;
			}
			if (score > 0d) {
				result.put(document.id(), score);
			}
		}
		return result;
	}

	private Map<String, Integer> ranks(Map<String, Double> scores) {
		List<Map.Entry<String, Double>> sorted = scores.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
			.toList();
		Map<String, Integer> result = new LinkedHashMap<>();
		for (int index = 0; index < sorted.size(); index++) {
			result.put(sorted.get(index).getKey(), index + 1);
		}
		return result;
	}

	private List<String> tokenize(String text) {
		if (!StringUtils.hasText(text)) {
			return List.of();
		}
		String normalized = normalize(text);
		LinkedHashSet<String> tokens = new LinkedHashSet<>();
		Matcher matcher = LATIN_OR_NUMBER.matcher(normalized);
		while (matcher.find()) {
			String token = matcher.group();
			if (StringUtils.hasText(token)) {
				tokens.add(token);
				addCharacterNgrams(token, tokens);
			}
		}
		return List.copyOf(tokens);
	}

	private void addCharacterNgrams(String value, Set<String> tokens) {
		if (value.codePointCount(0, value.length()) < 2) {
			return;
		}
		int[] codePoints = value.codePoints().toArray();
		for (int index = 0; index < codePoints.length; index++) {
			if (Character.UnicodeScript.of(codePoints[index]) == Character.UnicodeScript.HAN) {
				tokens.add(new String(codePoints, index, 1));
				if (index + 1 < codePoints.length
						&& Character.UnicodeScript.of(codePoints[index + 1]) == Character.UnicodeScript.HAN) {
					tokens.add(new String(codePoints, index, 2));
				}
			}
		}
	}

	private String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_:.]+", " ").trim();
	}

	public record RetrievalHit(SemanticRetrievalDocument.DocumentType documentType, String assetType, String assetKey,
			String modelCode, String physicalTable, double score, Map<String, Integer> channelRanks,
			Map<String, Double> channelScores) {

		public Map<String, Double> channels() {
			return channelScores;
		}

		public Set<String> matchedAssetKeys() {
			return Set.of(assetKey);
		}
	}

	public enum RetrievalMode {

		EXACT(true, false, false), BM25(false, true, false), VECTOR(false, false, true), HYBRID(true, true, true);

		private final boolean exact;

		private final boolean bm25;

		private final boolean vector;

		RetrievalMode(boolean exact, boolean bm25, boolean vector) {
			this.exact = exact;
			this.bm25 = bm25;
			this.vector = vector;
		}

		public boolean includesExact() {
			return exact;
		}

		public boolean includesBm25() {
			return bm25;
		}

		public boolean includesVector() {
			return vector;
		}
	}

}
