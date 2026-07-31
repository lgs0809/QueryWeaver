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
package cn.lgs.semevosql.learning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidatedSemanticSqlPatternServiceTest {

	@Test
	void storedAdvancedPatternShapeRemovesRuntimeLiterals() {
		String shape = ValidatedSemanticSqlPatternService.parameterizedSqlShape("""
				WITH monthly AS (
				  SELECT channel, SUM(paid_amount) amount
				  FROM pay_order
				  WHERE paid_at >= '2026-07-01 00:00:00' AND status = 1
				  GROUP BY channel
				)
				SELECT * FROM monthly WHERE amount > 1000.50
				""");

		assertThat(shape).doesNotContain("2026-07-01", "1000.50", "status = 1");
		assertThat(shape).contains("WITH monthly", "status = ?", "amount > ?");
	}

	@Test
	void repeatedBadRecallQuarantinesPattern() {
		assertThat(ValidatedSemanticSqlPatternService.shouldQuarantine(10, 1, 1)).isFalse();
		assertThat(ValidatedSemanticSqlPatternService.shouldQuarantine(10, 2, 2)).isTrue();
		assertThat(ValidatedSemanticSqlPatternService.shouldQuarantine(3, 3, 1)).isTrue();
	}
}
