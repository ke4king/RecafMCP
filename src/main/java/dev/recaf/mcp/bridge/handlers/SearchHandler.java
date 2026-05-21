package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.SimilaritySearchService;
import software.coley.recaf.services.search.match.NumberPredicate;
import software.coley.recaf.services.search.match.NumberPredicateProvider;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.DeclarationQuery;
import software.coley.recaf.services.search.query.InstructionQuery;
import software.coley.recaf.services.search.query.NumberQuery;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.query.StringQuery;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.search.result.Results;
import software.coley.recaf.services.search.similarity.MemberOrderMode;
import software.coley.recaf.services.search.similarity.ParameterMatchMode;
import software.coley.recaf.services.search.similarity.ReturnMatchMode;
import software.coley.recaf.services.search.similarity.SimilarClassSearchOptions;
import software.coley.recaf.services.search.similarity.SimilarClassSearchScope;
import software.coley.recaf.services.search.similarity.SimilarMethodSearchOptions;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handles search requests: string/number/instruction/reference/declaration and similarity search.
 */
public class SearchHandler {
	private static final Logger logger = Logging.get(SearchHandler.class);

	private final WorkspaceManager workspaceManager;
	private final SearchService searchService;
	private final SimilaritySearchService similaritySearchService;
	private final StringPredicateProvider stringPredicateProvider;
	private final NumberPredicateProvider numberPredicateProvider;

	public SearchHandler(WorkspaceManager workspaceManager,
						 SearchService searchService,
						 SimilaritySearchService similaritySearchService,
						 StringPredicateProvider stringPredicateProvider,
						 NumberPredicateProvider numberPredicateProvider) {
		this.workspaceManager = workspaceManager;
		this.searchService = searchService;
		this.similaritySearchService = similaritySearchService;
		this.stringPredicateProvider = stringPredicateProvider;
		this.numberPredicateProvider = numberPredicateProvider;
	}

	/**
	 * POST /search
	 * {
	 *   "query": "...",
	 *   "type": "string|number|instruction|class|method|field|declaration|similar_class|similar_method",
	 *   "maxResults": 100
	 * }
	 */
	public void handle(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String type = JsonUtil.getString(req, "type", "string").toLowerCase(Locale.ROOT);
		String query = JsonUtil.getString(req, "query", "");
		int maxResults = JsonUtil.getInt(req, "maxResults", 100);

		try {
			if ("similar_class".equals(type)) {
				handleSimilarClassSearch(exchange, workspace, req, maxResults);
				return;
			}
			if ("similar_method".equals(type)) {
				handleSimilarMethodSearch(exchange, workspace, req, maxResults);
				return;
			}

			if (query.isBlank() && !"number".equals(type)) {
				BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("query"));
				return;
			}

			Results results = runStandardSearch(workspace, req, query, type);
			List<JsonObject> resultList = new ArrayList<>();
			int count = 0;
			for (Result<?> result : results) {
				if (count >= maxResults) break;
				JsonObject item = toSearchResult(result);
				resultList.add(item);
				count++;
			}

			JsonObject data = new JsonObject();
			data.addProperty("summary", "Search completed");
			data.addProperty("modifiedCount", 0);
			data.add("findings", new JsonArray());
			data.add("failures", new JsonArray());
			data.addProperty("elapsedMs", 0);
			data.addProperty("query", query);
			data.addProperty("type", type);
			data.addProperty("count", resultList.size());
			data.add("results", JsonUtil.gson().toJsonTree(resultList));
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("Search failed for query '{}' type '{}'", query, type, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Search", e));
		}
	}

	private Results runStandardSearch(Workspace workspace, JsonObject req, String query, String type) {
		String normalizedQuery = query.replace('.', '/');
		return switch (type) {
			case "class" -> searchService.search(workspace, new ReferenceQuery(
					stringPredicateProvider.newContainsPredicate(normalizedQuery), null, null));
			case "method" -> searchService.search(workspace, new ReferenceQuery(
					null, stringPredicateProvider.newContainsPredicate(query), null));
			case "field" -> searchService.search(workspace, new ReferenceQuery(
					null, stringPredicateProvider.newContainsPredicate(query), null));
			case "declaration" -> searchService.search(workspace, new DeclarationQuery(
					stringPredicateProvider.newContainsPredicate(normalizedQuery),
					stringPredicateProvider.newContainsPredicate(query),
					null));
			case "number" -> {
				NumberPredicate predicate = parseNumberPredicate(req, query);
				yield searchService.search(workspace, new NumberQuery(predicate));
			}
			case "instruction" -> {
				List<StringPredicate> predicates = new ArrayList<>();
				for (String token : query.split("\\s+")) {
					if (!token.isBlank()) predicates.add(stringPredicateProvider.newContainsPredicate(token, true));
				}
				if (predicates.isEmpty()) {
					predicates.add(stringPredicateProvider.newContainsPredicate(query, true));
				}
				yield searchService.search(workspace, new InstructionQuery(predicates));
			}
			default -> searchService.search(workspace, new StringQuery(
					stringPredicateProvider.newContainsPredicate(query)));
		};
	}

	private NumberPredicate parseNumberPredicate(JsonObject req, String query) {
		String numberMode = JsonUtil.getString(req, "numberMode", "equal");
		double value = Double.parseDouble(query);
		return switch (numberMode) {
			case "gt" -> numberPredicateProvider.newGreaterThanPredicate(value);
			case "gte" -> numberPredicateProvider.newGreaterThanOrEqualPredicate(value);
			case "lt" -> numberPredicateProvider.newLessThanPredicate(value);
			case "lte" -> numberPredicateProvider.newLessThanOrEqualPredicate(value);
			default -> numberPredicateProvider.newEqualsPredicate(value);
		};
	}

	private void handleSimilarClassSearch(HttpExchange exchange, Workspace workspace, JsonObject req, int maxResults) throws IOException {
		String className = JsonUtil.getString(req, "className", JsonUtil.getString(req, "query", null));
		if (className == null || className.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		int threshold = JsonUtil.getInt(req, "similarityThresholdPercent", 70);
		SimilarClassSearchScope scope = JsonUtil.getBoolean(req, "primaryResourceOnly", true)
				? SimilarClassSearchScope.selfResource() : SimilarClassSearchScope.allNonInternal();
		SimilarClassSearchOptions options = new SimilarClassSearchOptions(
				threshold,
				ParameterMatchMode.EXACT_COUNT_AND_ORDER,
				ReturnMatchMode.EXACT_TYPE,
				MemberOrderMode.IGNORE_ORDER,
				MemberOrderMode.IGNORE_ORDER,
				SimilarClassSearchOptions.DEFAULT_METHOD_WEIGHT,
				SimilarClassSearchOptions.DEFAULT_FIELD_WEIGHT,
				scope
		);

		var results = similaritySearchService.searchClasses(classPath, options);
		JsonArray array = new JsonArray();
		int count = 0;
		for (var result : results) {
			if (count++ >= maxResults) break;
			JsonObject item = new JsonObject();
			item.addProperty("className", result.path().getValue().getName());
			item.addProperty("similarity", result.similarity());
			item.addProperty("methodScore", result.breakdown().methodSimilarity());
			item.addProperty("fieldScore", result.breakdown().fieldSimilarity());
			array.add(item);
		}

		JsonObject data = new JsonObject();
		data.addProperty("summary", "Similar class search completed");
		data.addProperty("modifiedCount", 0);
		data.add("findings", new JsonArray());
		data.add("failures", new JsonArray());
		data.addProperty("elapsedMs", 0);
		data.addProperty("className", normalizedName);
		data.add("results", array);
		data.addProperty("count", array.size());
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	private void handleSimilarMethodSearch(HttpExchange exchange, Workspace workspace, JsonObject req, int maxResults) throws IOException {
		String className = JsonUtil.getString(req, "className", null);
		String methodName = JsonUtil.getString(req, "methodName", null);
		String methodDesc = JsonUtil.getString(req, "methodDesc", null);
		if (className == null || methodName == null || methodDesc == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className", "methodName", "methodDesc"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}
		ClassMember member = classPath.getValue().getDeclaredMethod(methodName, methodDesc);
		if (member == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.memberNotFound(className, methodName + methodDesc));
			return;
		}

		int threshold = JsonUtil.getInt(req, "similarityThresholdPercent", 70);
		SimilarMethodSearchOptions options = new SimilarMethodSearchOptions(
				threshold,
				ParameterMatchMode.EXACT_COUNT_AND_ORDER,
				ReturnMatchMode.EXACT_TYPE,
				JsonUtil.getBoolean(req, "primaryResourceOnly", true)
		);

		var results = similaritySearchService.searchMethods(classPath.child(member), options);
		JsonArray array = new JsonArray();
		int count = 0;
		for (var result : results) {
			if (count++ >= maxResults) break;
			ClassMember m = result.path().getValue();
			ClassInfo owner = result.path().getParent().getValue();
			JsonObject item = new JsonObject();
			item.addProperty("className", owner.getName());
			item.addProperty("methodName", m.getName());
			item.addProperty("methodDesc", m.getDescriptor());
			item.addProperty("similarity", result.similarity());
			array.add(item);
		}

		JsonObject data = new JsonObject();
		data.addProperty("summary", "Similar method search completed");
		data.addProperty("modifiedCount", 0);
		data.add("findings", new JsonArray());
		data.add("failures", new JsonArray());
		data.addProperty("elapsedMs", 0);
		data.addProperty("className", normalizedName);
		data.addProperty("methodName", methodName);
		data.addProperty("methodDesc", methodDesc);
		data.add("results", array);
		data.addProperty("count", array.size());
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	private JsonObject toSearchResult(Result<?> result) {
		JsonObject item = new JsonObject();
		var path = result.getPath();
		ClassInfo classValue = path.getValueOfType(ClassInfo.class);
		if (classValue != null) {
			item.addProperty("class", classValue.getName());
		}
		ClassMember memberValue = path.getValueOfType(ClassMember.class);
		if (memberValue != null) {
			item.addProperty("member", memberValue.getName());
			item.addProperty("descriptor", memberValue.getDescriptor());
		}
		item.addProperty("pathType", path.getClass().getSimpleName());
		item.addProperty("resultType", result.getClass().getSimpleName());
		return item;
	}
}
