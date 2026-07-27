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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.learning.QueryCaseHints;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline.PlanningResult;
import cn.lgs.semevosql.semantic.application.SemanticBlueprintPipeline.PlanningTrace;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Durable, non-chain-of-thought evidence envelope for one execution stage.
 *
 * <p>Only governed asset identifiers, counts and timings are recorded. Model reasoning and prompt
 * internals are intentionally excluded.
 */
public record QueryExecutionEvidence(String evidenceId, EvidenceType evidenceType, String planningId,
		String catalogHash, PlanningTrace planningTrace, SelectedAssets selectedAssets,
		List<RetrievalCandidate> retrievalCandidates, List<String> historicalExampleIds) {

	private static final int MAX_RETRIEVAL_CANDIDATES = 100;

	public enum EvidenceType {
		SEMANTIC_PLANNING,
		SQL_COMPILATION,
		SQL_EXECUTION,
		CLARIFICATION,
		LEARNING
	}

	public static QueryExecutionEvidence semanticPlanning(PlanningResult result) {
		QueryCaseHints binding = result.binding();
		List<RetrievalCandidate> retrieval = result.candidateSet() == null ? List.of()
				: result.candidateSet()
					.retrievalEvidence()
					.stream()
					.sorted(Comparator.comparingDouble(
							cn.lgs.semevosql.semantic.domain.SemanticCandidateSet.RetrievalEvidence::rrfScore)
						.reversed())
					.limit(MAX_RETRIEVAL_CANDIDATES)
					.map(value -> new RetrievalCandidate(value.documentType(), value.assetType(), value.assetKey(),
							value.modelCode(), value.physicalTable(), value.rrfScore(), value.channelRanks(), value.channelScores()))
					.toList();
		return new QueryExecutionEvidence(UUID.randomUUID().toString(), EvidenceType.SEMANTIC_PLANNING,
				result.trace().planningId(), result.trace().catalogHash(), result.trace(),
				new SelectedAssets(binding.metricCodes(), binding.dimensionCodes(), binding.ruleCodes(),
						binding.relationshipCodes(), binding.grainCodes()), retrieval,
				result.historicalHints().sourceExampleIds());
	}

	public QueryExecutionEvidence {
		retrievalCandidates = List.copyOf(retrievalCandidates == null ? List.of() : retrievalCandidates);
		historicalExampleIds = List.copyOf(historicalExampleIds == null ? List.of() : historicalExampleIds);
	}

	public record SelectedAssets(Set<String> metricCodes, Set<String> dimensionCodes, Set<String> ruleCodes,
			Set<String> relationshipCodes, Set<String> grainCodes) {
		public SelectedAssets {
			metricCodes = Set.copyOf(metricCodes == null ? Set.of() : metricCodes);
			dimensionCodes = Set.copyOf(dimensionCodes == null ? Set.of() : dimensionCodes);
			ruleCodes = Set.copyOf(ruleCodes == null ? Set.of() : ruleCodes);
			relationshipCodes = Set.copyOf(relationshipCodes == null ? Set.of() : relationshipCodes);
			grainCodes = Set.copyOf(grainCodes == null ? Set.of() : grainCodes);
		}
	}

	public record RetrievalCandidate(String documentType, String assetType, String assetKey, String modelCode,
			String physicalTable, double rrfScore, Map<String, Integer> channelRanks,
			Map<String, Double> channelScores) {
		public RetrievalCandidate {
			channelRanks = Map.copyOf(channelRanks == null ? Map.of() : channelRanks);
			channelScores = Map.copyOf(channelScores == null ? Map.of() : channelScores);
		}
	}
}
