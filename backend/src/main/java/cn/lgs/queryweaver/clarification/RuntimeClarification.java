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
package cn.lgs.queryweaver.clarification;

import cn.lgs.queryweaver.semantic.domain.SemanticIssueType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record RuntimeClarification(String clarificationId, String runId, String question,
		List<ClarificationOption> options, String recommendedOption, String reason, String evidence,
		SemanticIssueType issueType, String assetType, String assetKey, String rawExpression, String resolvedValue,
		String resolutionSource, ClarificationStatus status, String selectedOption, String customAnswer,
		SemanticBindingScope selectedScope, String answeredBy, long revision, LocalDateTime createTime,
		LocalDateTime updateTime) {

	public enum ClarificationStatus {

		PENDING, ANSWERED, SUPERSEDED, EXPIRED

	}

	public record ClarificationOption(String code, String label, String value, String reason, String evidence) {
	}

}
