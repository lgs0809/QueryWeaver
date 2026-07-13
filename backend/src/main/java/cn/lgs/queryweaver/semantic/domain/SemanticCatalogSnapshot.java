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
package cn.lgs.queryweaver.semantic.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticCatalogSnapshot {

	private Long projectId;

	private Long projectVersionId;

	@Builder.Default
	private List<Model> models = new ArrayList<>();

	@Builder.Default
	private List<Column> columns = new ArrayList<>();

	@Builder.Default
	private List<Metric> metrics = new ArrayList<>();

	@Builder.Default
	private List<Dimension> dimensions = new ArrayList<>();

	@Builder.Default
	private List<Relationship> relationships = new ArrayList<>();

	@Builder.Default
	private List<Grain> grains = new ArrayList<>();

	@Builder.Default
	private List<EnumValue> enumValues = new ArrayList<>();

	@Builder.Default
	private List<Rule> rules = new ArrayList<>();

	public Set<Integer> enabledDatasourceIds() {
		return models.stream()
			.filter(Model::isEnabled)
			.map(Model::getDatasourceId)
			.filter(Objects::nonNull)
			.collect(Collectors.toUnmodifiableSet());
	}

	public Set<String> enabledPhysicalTables() {
		return models.stream()
			.filter(Model::isEnabled)
			.map(Model::getPhysicalTable)
			.filter(Objects::nonNull)
			.collect(Collectors.toUnmodifiableSet());
	}

	public SemanticCatalogSnapshot filterByPhysicalTables(Set<String> physicalTables) {
		Set<String> modelCodes = models.stream()
			.filter(Model::isEnabled)
			.filter(model -> physicalTables.contains(model.getPhysicalTable()))
			.map(Model::getModelCode)
			.collect(Collectors.toUnmodifiableSet());
		return SemanticCatalogSnapshot.builder()
			.projectId(projectId)
			.projectVersionId(projectVersionId)
			.models(models.stream().filter(model -> modelCodes.contains(model.getModelCode())).toList())
			.columns(columns.stream().filter(column -> modelCodes.contains(column.getModelCode())).toList())
			.metrics(metrics.stream().filter(metric -> modelCodes.contains(metric.getModelCode())).toList())
			.dimensions(dimensions.stream().filter(dimension -> modelCodes.contains(dimension.getModelCode())).toList())
			.relationships(relationships.stream()
				.filter(relationship -> modelCodes.contains(relationship.getSourceModelCode())
						&& modelCodes.contains(relationship.getTargetModelCode()))
				.toList())
			.grains(grains.stream().filter(grain -> modelCodes.contains(grain.getModelCode())).toList())
			.enumValues(enumValues.stream().filter(value -> modelCodes.contains(value.getModelCode())).toList())
			.rules(rules.stream()
				.filter(rule -> rule.getModelCode() == null || modelCodes.contains(rule.getModelCode()))
				.toList())
			.build();
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Model {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private Integer datasourceId;

		private String modelCode;

		private String physicalTable;

		private String businessName;

		private String modelType;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

		@JsonIgnore
		public boolean isEnabled() {
			return status == SemanticAssetStatus.ENABLED;
		}

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Column {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String columnName;

		private String businessName;

		private String dataType;

		private SemanticColumnRole role;

		private String expression;

		private String synonyms;

		private String description;

		private Boolean nullable;

		@Builder.Default
		private String sensitivityLevel = "PUBLIC";

		@Builder.Default
		private String maskingPolicy = "NONE";

		@Builder.Default
		private Boolean allowAggregation = true;

		@Builder.Default
		private Boolean allowFilter = true;

		@Builder.Default
		private Boolean allowProjection = true;

		@Builder.Default
		private Boolean allowExport = true;

		@Builder.Default
		private Boolean allowSendToLlm = true;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Metric {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String metricCode;

		private String businessName;

		private String expression;

		private String aggregation;

		private String unit;

		private String timeColumn;

		private String filterExpression;

		private String additiveType;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Dimension {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String dimensionCode;

		private String businessName;

		private String columnName;

		private String expression;

		private String dimensionType;

		private String hierarchy;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Relationship {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String relationshipCode;

		private String sourceModelCode;

		private String targetModelCode;

		private RelationshipCardinality cardinality;

		private String joinType;

		private String joinCondition;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Grain {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String grainCode;

		private String keyColumns;

		private String timeColumn;

		private String uniquenessRule;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class EnumValue {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String columnName;

		private String valueCode;

		private String businessName;

		private String aliases;

		private String description;

		private Integer sortOrder;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Rule {

		private Long id;

		private Long projectId;

		private Long projectVersionId;

		private String modelCode;

		private String ruleCode;

		private String ruleType;

		private String businessName;

		private String expression;

		private String severity;

		private String description;

		private String evidence;

		private SemanticAssetStatus status;

		private LocalDateTime createTime;

		private LocalDateTime updateTime;

	}

}
