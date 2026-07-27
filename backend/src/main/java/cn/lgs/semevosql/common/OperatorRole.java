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

import java.util.Locale;
import org.springframework.util.StringUtils;

/** Server-side operator capabilities ordered from read-only to full administration. */
public enum OperatorRole {

	VIEWER(0),

	EDITOR(10),

	REVIEWER(20),

	PUBLISHER(30),

	ADMIN(100);

	private final int rank;

	OperatorRole(int rank) {
		this.rank = rank;
	}

	public boolean atLeast(OperatorRole required) {
		return required != null && rank >= required.rank;
	}

	public static OperatorRole parse(Object value) {
		if (!StringUtils.hasText(value == null ? null : value.toString())) {
			throw new IllegalArgumentException("Operator role is required");
		}
		try {
			return valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unsupported Operator role: " + value, ex);
		}
	}

}
