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
package cn.lgs.queryweaver.external.mcp;

import cn.lgs.queryweaver.external.mcp.ExternalSemanticQueryFacade.ExternalQueryPlan;
import cn.lgs.queryweaver.external.mcp.ExternalSemanticQueryFacade.PlanValidationResult;
import cn.lgs.queryweaver.external.mcp.ExternalSemanticQueryFacade.QueryExecutionResult;
import cn.lgs.queryweaver.external.mcp.ExternalSemanticQueryFacade.SemanticAssetRef;
import cn.lgs.queryweaver.external.mcp.ExternalSemanticQueryFacade.SemanticContextResult;
import cn.lgs.queryweaver.external.mcp.ExternalSemanticQueryFacade.SemanticSearchResult;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** BYO-Agent MCP tools. Query reasoning/analysis belongs to the caller; QueryWeaver serves governed data-plane tools. */
@Component
public class ExternalProjectMcpTools {

    private static final String AUTHORIZATION = "queryweaver.authorization";

    private final McpIntegrationAuthenticator authenticator;
    private final ExternalSemanticQueryFacade semanticFacade;

    public ExternalProjectMcpTools(McpIntegrationAuthenticator authenticator, ExternalSemanticQueryFacade semanticFacade) {
        this.authenticator = authenticator;
        this.semanticFacade = semanticFacade;
    }

    @Tool(name = "search_semantics",
            description = "Search the published QueryWeaver semantic catalog for governed assets before planning. No QueryWeaver chat-model call.")
    public SemanticSearchResult searchSemantics(@ToolParam(description = "Business terms or semantic concepts") String query,
            @ToolParam(description = "Maximum hits, default 20 and capped at 50", required = false) Integer limit,
            ToolContext toolContext) {
        return semanticFacade.search(deployment(toolContext), query, limit);
    }

    @Tool(name = "get_semantic_context",
            description = "Load authoritative definitions for semantic assets returned by search_semantics. No QueryWeaver chat-model call.")
    public SemanticContextResult getSemanticContext(
            @ToolParam(description = "Semantic asset type/key pairs returned by search_semantics") List<SemanticAssetRef> assets,
            ToolContext toolContext) {
        return semanticFacade.context(deployment(toolContext), assets);
    }

    @Tool(name = "validate_query_plan",
            description = "Validate a caller-authored governed semantic query plan against the deployed catalog. Never accepts SQL and does not call QueryWeaver's chat model.")
    public PlanValidationResult validateQueryPlan(@ToolParam(description = "Caller-authored semantic query plan") ExternalQueryPlan plan,
            ToolContext toolContext) {
        return semanticFacade.validate(deployment(toolContext), plan);
    }

    @Tool(name = "execute_query_plan",
            description = "Execute a governed semantic query plan via deterministic compiler, SQL guard and read-only datasource access. Never accepts SQL and does not call QueryWeaver's chat model.")
    public QueryExecutionResult executeQueryPlan(
            @ToolParam(description = "Caller-authored governed semantic query plan") ExternalQueryPlan plan,
            ToolContext toolContext) {
        return semanticFacade.execute(deployment(toolContext), plan);
    }

    @Tool(name = "get_query_result",
            description = "Read status or structured result for a queryId returned by execute_query_plan. Never re-plans or calls QueryWeaver's chat model.")
    public Object getQueryResult(@ToolParam(description = "Durable query id") String queryId, ToolContext toolContext) {
        return semanticFacade.getResult(deployment(toolContext), queryId);
    }

    private ProjectMcpDeployment deployment(ToolContext toolContext) {
        var exchange = McpToolUtils.getMcpExchange(toolContext)
            .orElseThrow(() -> new SecurityException("MCP exchange context is required"));
        Object authorization = exchange.transportContext().get(AUTHORIZATION);
        if (!(authorization instanceof String value)) {
            throw new SecurityException("MCP bearer credential is required");
        }
        return authenticator.authenticateAuthorization(value).deployment();
    }

    public static String authorizationContextKey() {
        return AUTHORIZATION;
    }
}
