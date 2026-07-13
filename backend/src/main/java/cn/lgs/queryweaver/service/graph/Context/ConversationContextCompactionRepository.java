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
package cn.lgs.queryweaver.service.graph.Context;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Database-neutral latest-snapshot repository for conversation compaction. */
@Repository
public class ConversationContextCompactionRepository {

	private final JdbcTemplate jdbc;

	public ConversationContextCompactionRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<ConversationContextCompactionSnapshot> find(String threadId) {
		List<ConversationContextCompactionSnapshot> values = jdbc.query("""
				SELECT thread_id, covered_through_sequence, summary_json, source_digest, summary_version,
				       create_time, update_time
				FROM qw_conversation_context_compaction WHERE thread_id = ?
				""",
				(rs, rowNum) -> new ConversationContextCompactionSnapshot(rs.getString("thread_id"),
						rs.getLong("covered_through_sequence"), rs.getString("summary_json"),
						rs.getString("source_digest"), rs.getInt("summary_version"),
						time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time"))),
				threadId);
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	@Transactional
	public void upsert(ConversationContextCompactionSnapshot snapshot) {
		LocalDateTime now = snapshot.updateTime() == null ? LocalDateTime.now() : snapshot.updateTime();
		int updated = update(snapshot, now);
		if (updated > 0) {
			return;
		}
		LocalDateTime created = snapshot.createTime() == null ? now : snapshot.createTime();
		try {
			jdbc.update("""
					INSERT INTO qw_conversation_context_compaction(thread_id, covered_through_sequence, summary_json,
					 source_digest, summary_version, create_time, update_time)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					""", snapshot.threadId(), snapshot.coveredThroughSequence(), snapshot.summaryJson(),
					snapshot.sourceDigest(), snapshot.summaryVersion(), Timestamp.valueOf(created),
					Timestamp.valueOf(now));
		}
		catch (DuplicateKeyException ex) {
			update(snapshot, now);
		}
	}

	private int update(ConversationContextCompactionSnapshot snapshot, LocalDateTime now) {
		return jdbc.update("""
				UPDATE qw_conversation_context_compaction
				SET covered_through_sequence = ?, summary_json = ?, source_digest = ?, summary_version = ?,
				    update_time = ?
				WHERE thread_id = ?
				""", snapshot.coveredThroughSequence(), snapshot.summaryJson(), snapshot.sourceDigest(),
				snapshot.summaryVersion(), Timestamp.valueOf(now), snapshot.threadId());
	}

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

}
