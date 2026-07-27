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
package cn.lgs.semevosql.common;

import java.util.Arrays;
import org.springframework.stereotype.Service;

/** Fail-closed service-side authorization for governed SemEvoSQL mutations. */
@Service
public class OperatorAuthorizationService {

	public OperatorContext requireAtLeast(OperatorContext operator, OperatorRole required, String operation) {
		if (operator == null || operator.role() == null || !operator.role().atLeast(required)) {
			throw new OperatorAuthorizationException(operation, required, operator == null ? null : operator.role());
		}
		return operator;
	}

	public OperatorContext requireAny(OperatorContext operator, String operation, OperatorRole... allowed) {
		if (operator != null && operator.role() != null && Arrays.stream(allowed)
			.anyMatch(role -> role == operator.role() || operator.role() == OperatorRole.ADMIN)) {
			return operator;
		}
		throw new OperatorAuthorizationException(operation, allowed.length == 0 ? null : allowed[0],
				operator == null ? null : operator.role());
	}

	public static class OperatorAuthorizationException extends SecurityException {

		public OperatorAuthorizationException(String operation, OperatorRole required, OperatorRole actual) {
			super("Operator role " + actual + " is not allowed to perform " + operation + "; required=" + required);
		}

	}

}
