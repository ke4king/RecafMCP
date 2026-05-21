package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.script.GenerateResult;
import software.coley.recaf.services.script.ScriptEngine;
import software.coley.recaf.services.script.ScriptResult;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controlled script execution endpoints.
 */
public class ScriptHandler {
	private static final Logger logger = Logging.get(ScriptHandler.class);

	private final ScriptEngine scriptEngine;
	private final AtomicLong compileIdSeq = new AtomicLong(1);
	private final AtomicLong runIdSeq = new AtomicLong(1);
	private final Map<Long, GenerateResult> compileCache = new ConcurrentHashMap<>();
	private final Map<Long, RunRecord> runs = new ConcurrentHashMap<>();
	private final AtomicBoolean running = new AtomicBoolean(false);

	public ScriptHandler(ScriptEngine scriptEngine) {
		this.scriptEngine = scriptEngine;
	}

	public void handleCompile(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String script = JsonUtil.getString(req, "script", null);
		if (script == null || script.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("script"));
			return;
		}

		try {
			GenerateResult result = scriptEngine.compile(script).join();
			long compileId = compileIdSeq.getAndIncrement();
			compileCache.put(compileId, result);

			JsonObject data = new JsonObject();
			data.addProperty("summary", result.wasSuccess() ? "Script compile succeeded" : "Script compile failed");
			data.addProperty("modifiedCount", 0);
			data.add("findings", new JsonArray());
			data.add("failures", new JsonArray());
			data.addProperty("elapsedMs", 0);
			data.addProperty("compileId", compileId);
			data.addProperty("success", result.wasSuccess());
			data.add("diagnostics", diagnosticsToJson(result.diagnostics()));
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("script compile failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Script compile", e));
		}
	}

	public void handleRun(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		boolean confirm = JsonUtil.getBoolean(req, "confirm", false);
		if (!confirm) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS,
					"Script execution requires confirm=true",
					"Set confirm=true after reviewing compile diagnostics."));
			return;
		}
		if (!running.compareAndSet(false, true)) {
			BridgeServer.sendJson(exchange, 409, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS,
					"Another script run is already in progress",
					"Wait for completion or cancel the active run first."));
			return;
		}

		try {
			GenerateResult generateResult;
			if (req.has("compileId")) {
				long compileId = JsonUtil.getLong(req, "compileId", -1);
				generateResult = compileCache.get(compileId);
				if (generateResult == null) {
					running.set(false);
					BridgeServer.sendJson(exchange, 404, ErrorMapper.errorResponse(
							ErrorMapper.INVALID_PARAMS,
							"compileId not found: " + compileId,
							"Compile script first using script_compile."));
					return;
				}
			} else {
				String script = JsonUtil.getString(req, "script", null);
				if (script == null || script.isBlank()) {
					running.set(false);
					BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("script or compileId"));
					return;
				}
				generateResult = scriptEngine.compile(script).join();
			}

			long runId = runIdSeq.getAndIncrement();
			CompletableFuture<ScriptResult> future = scriptEngine.run(generateResult);
			RunRecord record = new RunRecord(runId, generateResult, future, Instant.now().toString());
			runs.put(runId, record);
			future.whenComplete((r, t) -> running.set(false));

			JsonObject data = new JsonObject();
			data.addProperty("summary", "Script execution started");
			data.addProperty("modifiedCount", 0);
			data.add("findings", new JsonArray());
			data.add("failures", new JsonArray());
			data.addProperty("elapsedMs", 0);
			data.addProperty("runId", runId);
			data.addProperty("status", "running");
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			running.set(false);
			logger.error("script run failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Script run", e));
		}
	}

	public void handleCancel(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		long runId = JsonUtil.getLong(req, "runId", -1);
		RunRecord record = runs.get(runId);
		if (record == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS, "runId not found: " + runId, "Call script_result to list known runs."));
			return;
		}
		record.generateResult.requestStop();
		record.future.cancel(true);
		running.set(false);
		JsonObject data = new JsonObject();
		data.addProperty("summary", "Script cancellation requested");
		data.addProperty("modifiedCount", 0);
		data.add("findings", new JsonArray());
		data.add("failures", new JsonArray());
		data.addProperty("elapsedMs", 0);
		data.addProperty("runId", runId);
		data.addProperty("status", "cancel_requested");
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	public void handleResult(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		long runId = JsonUtil.getLong(req, "runId", -1);
		RunRecord record = runs.get(runId);
		if (record == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS, "runId not found: " + runId, "Run script first via script_run."));
			return;
		}

		JsonObject data = new JsonObject();
		data.addProperty("summary", "Script execution status");
		data.addProperty("modifiedCount", 0);
		data.add("findings", new JsonArray());
		data.add("failures", new JsonArray());
		data.addProperty("elapsedMs", 0);
		data.addProperty("runId", runId);
		data.addProperty("startedAt", record.startedAt);
		if (!record.future.isDone()) {
			data.addProperty("status", "running");
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			return;
		}

		try {
			ScriptResult result = record.future.join();
			data.addProperty("status", "completed");
			data.addProperty("success", result.wasSuccess());
			data.addProperty("compileFailure", result.wasCompileFailure());
			data.addProperty("runtimeError", result.wasRuntimeError());
			data.addProperty("cancelled", result.wasCancelled());
			data.add("diagnostics", diagnosticsToJson(result.getCompileDiagnostics()));
			if (result.getRuntimeThrowable() != null) {
				data.addProperty("runtimeErrorMessage", result.getRuntimeThrowable().toString());
			}
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Script result", e));
		}
	}

	private JsonArray diagnosticsToJson(java.util.List<CompilerDiagnostic> diagnostics) {
		JsonArray array = new JsonArray();
		for (CompilerDiagnostic diagnostic : diagnostics) {
			JsonObject item = new JsonObject();
			item.addProperty("line", diagnostic.line());
			item.addProperty("column", diagnostic.column());
			item.addProperty("length", diagnostic.length());
			item.addProperty("level", diagnostic.level().name());
			item.addProperty("message", diagnostic.message());
			array.add(item);
		}
		return array;
	}

	private record RunRecord(long runId, GenerateResult generateResult, CompletableFuture<ScriptResult> future, String startedAt) {}
}
