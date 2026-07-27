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
package cn.lgs.semevosql.semantic.infrastructure;

import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SemEvoSQLSemanticCatalogMapper {

	@Delete("DELETE FROM qw_semantic_enum_value WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteEnumValues(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_rule WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteRules(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_relationship WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteRelationships(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_metric WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteMetrics(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_dimension WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteDimensions(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_grain WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteGrains(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_column WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteColumns(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Delete("DELETE FROM qw_semantic_model WHERE project_id = #{projectId} AND project_version_id = #{versionId}")
	int deleteModels(@Param("projectId") Long projectId, @Param("versionId") Long versionId);

	@Insert("""
			INSERT INTO qw_semantic_model
			(project_id, project_version_id, datasource_id, model_code, physical_table, business_name, model_type, description,
			 evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{datasourceId}, #{modelCode}, #{physicalTable}, #{businessName},
			 #{modelType}, #{description}, #{evidence}, #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertModel(SemanticCatalogSnapshot.Model model);

	@Insert("""
			INSERT INTO qw_semantic_column
			(project_id, project_version_id, model_code, column_name, business_name, data_type, role, expression,
			 synonyms, description, nullable_flag, sensitivity_level, masking_policy, allow_aggregation, allow_filter,
			 allow_projection, allow_export, allow_send_to_llm, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{modelCode}, #{columnName}, #{businessName}, #{dataType}, #{role},
			 #{expression}, #{synonyms}, #{description}, #{nullable}, #{sensitivityLevel}, #{maskingPolicy},
			 #{allowAggregation}, #{allowFilter}, #{allowProjection}, #{allowExport}, #{allowSendToLlm}, #{evidence},
			 #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertColumn(SemanticCatalogSnapshot.Column column);

	@Insert("""
			INSERT INTO qw_semantic_metric
			(project_id, project_version_id, model_code, metric_code, business_name, expression, aggregation, unit,
			 time_column, filter_expression, additive_type, description, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{modelCode}, #{metricCode}, #{businessName}, #{expression},
			 #{aggregation}, #{unit}, #{timeColumn}, #{filterExpression}, #{additiveType}, #{description}, #{evidence},
			 #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertMetric(SemanticCatalogSnapshot.Metric metric);

	@Insert("""
			INSERT INTO qw_semantic_dimension
			(project_id, project_version_id, model_code, dimension_code, business_name, column_name, expression,
			 dimension_type, hierarchy, description, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{modelCode}, #{dimensionCode}, #{businessName}, #{columnName},
			 #{expression}, #{dimensionType}, #{hierarchy}, #{description}, #{evidence}, #{status}, #{createTime},
			 #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertDimension(SemanticCatalogSnapshot.Dimension dimension);

	@Insert("""
			INSERT INTO qw_semantic_relationship
			(project_id, project_version_id, relationship_code, source_model_code, target_model_code, cardinality,
			 join_type, join_condition, description, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{relationshipCode}, #{sourceModelCode}, #{targetModelCode},
			 #{cardinality}, #{joinType}, #{joinCondition}, #{description}, #{evidence}, #{status}, #{createTime},
			 #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertRelationship(SemanticCatalogSnapshot.Relationship relationship);

	@Insert("""
			INSERT INTO qw_semantic_grain
			(project_id, project_version_id, model_code, grain_code, key_columns, time_column, uniqueness_rule,
			 description, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{modelCode}, #{grainCode},
			 to_jsonb(string_to_array(CAST(#{keyColumns} AS TEXT), ',')), #{timeColumn},
			 #{uniquenessRule}, #{description}, #{evidence}, #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertGrain(SemanticCatalogSnapshot.Grain grain);

	@Insert("""
			INSERT INTO qw_semantic_enum_value
			(project_id, project_version_id, model_code, column_name, value_code, business_name, aliases, description,
			 sort_order, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{modelCode}, #{columnName}, #{valueCode}, #{businessName},
			 to_jsonb(string_to_array(NULLIF(CAST(#{aliases} AS TEXT), ''), ',')),
			 #{description}, #{sortOrder}, #{evidence}, #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertEnumValue(SemanticCatalogSnapshot.EnumValue value);

	@Insert("""
			INSERT INTO qw_semantic_rule
			(project_id, project_version_id, model_code, rule_code, rule_type, business_name, expression, severity,
			 description, evidence, status, create_time, update_time)
			VALUES
			(#{projectId}, #{projectVersionId}, #{modelCode}, #{ruleCode}, #{ruleType}, #{businessName},
			 #{expression}, #{severity}, #{description}, #{evidence}, #{status}, #{createTime}, #{updateTime})
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
	int insertRule(SemanticCatalogSnapshot.Rule rule);

	@Select("SELECT * FROM qw_semantic_model WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY model_code")
	List<SemanticCatalogSnapshot.Model> findModels(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT id, project_id, project_version_id, model_code, column_name, business_name, data_type, role,
			expression, synonyms, description, nullable_flag AS nullable, sensitivity_level, masking_policy,
			allow_aggregation, allow_filter, allow_projection, allow_export, allow_send_to_llm, evidence, status,
			create_time, update_time
			FROM qw_semantic_column
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY model_code, column_name
			""")
	List<SemanticCatalogSnapshot.Column> findColumns(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("SELECT * FROM qw_semantic_metric WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY metric_code")
	List<SemanticCatalogSnapshot.Metric> findMetrics(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("SELECT * FROM qw_semantic_dimension WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY dimension_code")
	List<SemanticCatalogSnapshot.Dimension> findDimensions(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("SELECT * FROM qw_semantic_relationship WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY relationship_code")
	List<SemanticCatalogSnapshot.Relationship> findRelationships(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT id, project_id, project_version_id, model_code, grain_code,
			       CASE
			           WHEN jsonb_typeof(key_columns) = 'array' THEN (
			               SELECT string_agg(item.value, ',' ORDER BY item.ordinality)
			               FROM jsonb_array_elements_text(key_columns) WITH ORDINALITY AS item(value, ordinality)
			           )
			           WHEN jsonb_typeof(key_columns) = 'string' THEN key_columns #>> '{}'
			           ELSE key_columns::text
			       END AS key_columns,
			       time_column, uniqueness_rule, description, evidence, status, create_time, update_time
			FROM qw_semantic_grain
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY model_code, grain_code
			""")
	List<SemanticCatalogSnapshot.Grain> findGrains(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("""
			SELECT id, project_id, project_version_id, model_code, column_name, value_code, business_name,
			       CASE
			           WHEN aliases IS NULL THEN NULL
			           WHEN jsonb_typeof(aliases) = 'array' THEN (
			               SELECT string_agg(item.value, ',' ORDER BY item.ordinality)
			               FROM jsonb_array_elements_text(aliases) WITH ORDINALITY AS item(value, ordinality)
			           )
			           WHEN jsonb_typeof(aliases) = 'string' THEN aliases #>> '{}'
			           ELSE aliases::text
			       END AS aliases,
			       description, sort_order, evidence, status, create_time, update_time
			FROM qw_semantic_enum_value
			WHERE project_id = #{projectId} AND project_version_id = #{versionId}
			ORDER BY model_code, column_name, sort_order, id
			""")
	List<SemanticCatalogSnapshot.EnumValue> findEnumValues(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

	@Select("SELECT * FROM qw_semantic_rule WHERE project_id = #{projectId} AND project_version_id = #{versionId} ORDER BY rule_code")
	List<SemanticCatalogSnapshot.Rule> findRules(@Param("projectId") Long projectId,
			@Param("versionId") Long versionId);

}
