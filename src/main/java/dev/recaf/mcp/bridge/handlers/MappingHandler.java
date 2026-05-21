package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.bridge.WorkspaceRegistry;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import dev.recaf.mcp.util.OperationResponse;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.aggregate.AggregatedMappings;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.mapping.gen.MappingGenerator;
import software.coley.recaf.services.mapping.gen.filter.NameGeneratorFilter;
import software.coley.recaf.services.mapping.gen.naming.IncrementingNameGenerator;
import software.coley.recaf.services.mapping.matching.SimilarityMappingOptions;
import software.coley.recaf.services.mapping.matching.SimilarityMappingService;
import software.coley.recaf.services.mapping.matching.SimilarityMappingsReport;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Handles symbol rename, mapping generation and mapping export.
 */
public class MappingHandler {
	private static final Logger logger = Logging.get(MappingHandler.class);

	private final WorkspaceManager workspaceManager;
	private final WorkspaceRegistry workspaceRegistry;
	private final MappingApplierService mappingApplierService;
	private final MappingGenerator mappingGenerator;
	private final SimilarityMappingService similarityMappingService;
	private final InheritanceGraphService inheritanceGraphService;
	private final MappingFormatManager mappingFormatManager;
	private final AggregateMappingManager aggregateMappingManager;
	private final IntermediateMappings generatedMappings = new IntermediateMappings();

	public MappingHandler(WorkspaceManager workspaceManager,
						  MappingApplierService mappingApplierService,
						  MappingGenerator mappingGenerator,
						  SimilarityMappingService similarityMappingService,
						  InheritanceGraphService inheritanceGraphService,
						  MappingFormatManager mappingFormatManager,
						  AggregateMappingManager aggregateMappingManager,
						  WorkspaceRegistry workspaceRegistry) {
		this.workspaceManager = workspaceManager;
		this.mappingApplierService = mappingApplierService;
		this.mappingGenerator = mappingGenerator;
		this.similarityMappingService = similarityMappingService;
		this.inheritanceGraphService = inheritanceGraphService;
		this.mappingFormatManager = mappingFormatManager;
		this.aggregateMappingManager = aggregateMappingManager;
		this.workspaceRegistry = workspaceRegistry;
	}

	public void handleRename(HttpExchange exchange) throws IOException {
		long start = System.currentTimeMillis();
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);

		Workspace workspace = resolveWorkspace(req, null);
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.noWorkspace());
			return;
		}

		String mode = JsonUtil.getString(req, "mode", "manual");
		String applyMode = JsonUtil.getString(req, "applyMode", "preview");
		try {
			IntermediateMappings mappings = switch (mode.toLowerCase()) {
				case "auto" -> buildAutoMappings(workspace);
				case "similarity" -> buildSimilarityMappings(req);
				default -> buildSingleManualMapping(req);
			};
			handleApply(exchange, workspace, mappings, applyMode, "rename_symbol", start);
		} catch (Exception e) {
			logger.error("rename_symbol failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Rename", e));
		}
	}

	public void handleBatchRename(HttpExchange exchange) throws IOException {
		long start = System.currentTimeMillis();
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);

		Workspace workspace = resolveWorkspace(req, null);
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.noWorkspace());
			return;
		}

		String mode = JsonUtil.getString(req, "mode", "manual");
		String applyMode = JsonUtil.getString(req, "applyMode", "preview");
		try {
			IntermediateMappings mappings = switch (mode.toLowerCase()) {
				case "auto" -> buildAutoMappings(workspace);
				case "similarity" -> buildSimilarityMappings(req);
				default -> buildManualMappings(req);
			};
			handleApply(exchange, workspace, mappings, applyMode, "batch_rename", start);
		} catch (Exception e) {
			logger.error("batch_rename failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Batch rename", e));
		}
	}

	/**
	 * POST /mapping/generate
	 * Generate mappings without applying to workspace.
	 */
	public void handleGenerateMappings(HttpExchange exchange) throws IOException {
		long start = System.currentTimeMillis();
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);

		Workspace workspace = resolveWorkspace(req, null);
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.noWorkspace());
			return;
		}

		try {
			String mode = JsonUtil.getString(req, "mode", "auto");
			IntermediateMappings mappings = "similarity".equalsIgnoreCase(mode)
					? buildSimilarityMappings(req)
					: buildAutoMappings(workspace);
			synchronized (generatedMappings) {
				generatedMappings.putAll(mappings);
			}

			JsonObject extra = new JsonObject();
			extra.addProperty("generatedClasses", mappings.getClasses().size());
			extra.addProperty("generatedFieldOwners", mappings.getFields().size());
			extra.addProperty("generatedMethodOwners", mappings.getMethods().size());
			extra.add("previewMappings", JsonUtil.gson().toJsonTree(mappings));
			String response = OperationResponse.ok("Generated mappings", 0, new JsonArray(), new JsonArray(),
					System.currentTimeMillis() - start, extra);
			BridgeServer.sendJson(exchange, 200, response);
		} catch (Exception e) {
			logger.error("mapping/generate failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Generate mappings", e));
		}
	}

	public void handleExport(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String format = JsonUtil.getString(req, "format", null);
		String outputPath = JsonUtil.getString(req, "outputPath", null);

		if (format == null || outputPath == null) {
			if (format == null && outputPath == null) {
				Set<String> formats = mappingFormatManager.getMappingFileFormats();
				JsonObject data = new JsonObject();
				data.add("availableFormats", JsonUtil.gson().toJsonTree(formats));
				BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
				return;
			}
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("format", "outputPath"));
			return;
		}

		try {
			IntermediateMappings merged = new IntermediateMappings();
			AggregatedMappings aggMappings = aggregateMappingManager.getAggregatedMappings();
			if (aggMappings != null) {
				merged.putAll(aggMappings.exportIntermediate());
			}
			synchronized (generatedMappings) {
				merged.putAll(generatedMappings);
			}
			if (merged.isEmpty()) {
				BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
						ErrorMapper.INVALID_PARAMS,
						"No mappings available to export",
						"Apply or generate mappings first."));
				return;
			}

			MappingFileFormat fileFormat = mappingFormatManager.createFormatInstance(format);
			if (fileFormat == null) {
				Set<String> formats = mappingFormatManager.getMappingFileFormats();
				BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
						ErrorMapper.INVALID_PARAMS,
						"Unknown format: " + format,
						"Available formats: " + formats));
				return;
			}

			String exportText = fileFormat.exportText(merged);
			Path path = Paths.get(outputPath);
			Files.writeString(path, exportText);

			JsonObject data = new JsonObject();
			data.addProperty("format", format);
			data.addProperty("outputPath", outputPath);
			data.addProperty("size", exportText.length());
			data.addProperty("sources", "aggregate+generated");
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("Mapping export failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Export mappings", e));
		}
	}

	private void handleApply(HttpExchange exchange, Workspace workspace, IntermediateMappings mappings, String applyMode, String op, long start) throws IOException {
		synchronized (generatedMappings) {
			generatedMappings.putAll(mappings);
		}
		JsonArray failures = new JsonArray();
		JsonObject extra = new JsonObject();
		extra.addProperty("mode", applyMode);
		extra.addProperty("mappingClasses", mappings.getClasses().size());
		extra.addProperty("mappingFieldOwners", mappings.getFields().size());
		extra.addProperty("mappingMethodOwners", mappings.getMethods().size());

		int modifiedCount = 0;
		if ("apply".equalsIgnoreCase(applyMode)) {
			var applier = mappingApplierService.inWorkspace(workspace);
			if (applier == null) {
				BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
						ErrorMapper.INTERNAL_ERROR,
						"No workspace applier available",
						"Ensure a workspace is open and try again."));
				return;
			}
			MappingResults results = applier.applyToPrimaryResource(mappings);
			results.apply();
			modifiedCount = results.getMappedClasses().size();
			extra.addProperty("affectedClasses", modifiedCount);
		} else {
			extra.add("previewMappings", JsonUtil.gson().toJsonTree(mappings));
		}

		String summary = "apply".equalsIgnoreCase(applyMode) ? "Mappings applied" : "Mappings prepared (preview)";
		String response = OperationResponse.ok(summary, modifiedCount, new JsonArray(), failures, System.currentTimeMillis() - start, extra);
		BridgeServer.sendJson(exchange, 200, response);
		logger.info("[MCP] {} completed in mode={} for {}", op, applyMode, workspace);
	}

	private Workspace resolveWorkspace(JsonObject req, Workspace fallback) {
		String workspaceId = JsonUtil.getString(req, "workspaceId", null);
		if (workspaceId == null || workspaceId.isBlank()) {
			return fallback != null ? fallback : workspaceManager.getCurrent();
		}
		return workspaceRegistry.get(workspaceId);
	}

	private IntermediateMappings buildSingleManualMapping(JsonObject req) {
		String type = JsonUtil.getString(req, "type", null);
		String oldName = JsonUtil.getString(req, "oldName", null);
		String newName = JsonUtil.getString(req, "newName", null);
		String className = JsonUtil.getString(req, "className", null);
		String descriptor = JsonUtil.getString(req, "descriptor", null);
		if (type == null || oldName == null || newName == null) {
			throw new IllegalArgumentException("Missing required fields: type, oldName, newName");
		}
		IntermediateMappings mappings = new IntermediateMappings();
		switch (type.toLowerCase()) {
			case "class" -> mappings.addClass(oldName.replace('.', '/'), newName.replace('.', '/'));
			case "field" -> mappings.addField(className.replace('.', '/'), descriptor, oldName, newName);
			case "method" -> mappings.addMethod(className.replace('.', '/'), descriptor, oldName, newName);
			default -> throw new IllegalArgumentException("Invalid type: " + type);
		}
		return mappings;
	}

	private IntermediateMappings buildManualMappings(JsonObject req) {
		JsonElement mappingsEl = req.get("mappings");
		if (mappingsEl == null || !mappingsEl.isJsonArray()) {
			throw new IllegalArgumentException("Missing mappings array");
		}
		IntermediateMappings mappings = new IntermediateMappings();
		for (JsonElement el : mappingsEl.getAsJsonArray()) {
			JsonObject entry = el.getAsJsonObject();
			String type = JsonUtil.getString(entry, "type", null);
			String oldName = JsonUtil.getString(entry, "oldName", null);
			String newName = JsonUtil.getString(entry, "newName", null);
			String className = JsonUtil.getString(entry, "className", null);
			String descriptor = JsonUtil.getString(entry, "descriptor", null);
			if (type == null || oldName == null || newName == null) continue;
			switch (type.toLowerCase()) {
				case "class" -> mappings.addClass(oldName.replace('.', '/'), newName.replace('.', '/'));
				case "field" -> mappings.addField(className.replace('.', '/'), descriptor, oldName, newName);
				case "method" -> mappings.addMethod(className.replace('.', '/'), descriptor, oldName, newName);
			}
		}
		return mappings;
	}

	private IntermediateMappings buildAutoMappings(Workspace workspace) {
		var inheritance = inheritanceGraphService.getOrCreateInheritanceGraph(workspace);
		var generator = new IncrementingNameGenerator();
		generator.setWorkspace(workspace);
		NameGeneratorFilter allFilter = new NameGeneratorFilter(null, true) {};
		var mappings = mappingGenerator.generate(workspace, workspace.getPrimaryResource(), inheritance, generator, allFilter);
		return mappings.exportIntermediate();
	}

	private IntermediateMappings buildSimilarityMappings(JsonObject req) {
		String sourceWorkspaceId = JsonUtil.getString(req, "sourceWorkspaceId", null);
		String targetWorkspaceId = JsonUtil.getString(req, "targetWorkspaceId", null);
		if (sourceWorkspaceId == null || targetWorkspaceId == null) {
			throw new IllegalArgumentException("Similarity mode requires sourceWorkspaceId and targetWorkspaceId");
		}

		Workspace source = workspaceRegistry.get(sourceWorkspaceId);
		Workspace target = workspaceRegistry.get(targetWorkspaceId);
		if (source == null || target == null) {
			throw new IllegalArgumentException("Source/target workspace not found");
		}

		SimilarityMappingOptions options = new SimilarityMappingOptions(
				JsonUtil.getInt(req, "classSimilarityThresholdPercent", 75),
				JsonUtil.getInt(req, "classCertaintyGapPercent", 10),
				JsonUtil.getInt(req, "memberSimilarityThresholdPercent", 70),
				JsonUtil.getInt(req, "maxFullScoreCandidates", 8),
				JsonUtil.getInt(req, "shortlistGapThresholdPercent", 5)
		);
		SimilarityMappingsReport report = similarityMappingService.analyze(
				target, target.getPrimaryResource(), source.getPrimaryResource(), options, null);
		return report.getMappings();
	}
}
