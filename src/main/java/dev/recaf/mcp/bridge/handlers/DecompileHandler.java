package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.AndroidDecompiler;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles class decompilation requests.
 */
public class DecompileHandler {
	private static final Logger logger = Logging.get(DecompileHandler.class);

	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;

	public DecompileHandler(WorkspaceManager workspaceManager, DecompilerManager decompilerManager) {
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
	}

	/**
	 * POST /decompile  { "className": "com/example/Foo" }
	 */
	public void handle(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String classKind = JsonUtil.getString(req, "classKind", "auto");
		String decompilerName = JsonUtil.getString(req, "decompiler", null);

		if (className == null || className.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath;
		if ("jvm".equalsIgnoreCase(classKind)) {
			classPath = workspace.findJvmClass(normalizedName);
		} else if ("android".equalsIgnoreCase(classKind)) {
			classPath = workspace.findAndroidClass(normalizedName);
		} else {
			classPath = workspace.findClass(normalizedName);
		}
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		var classInfo = classPath.getValue();
		logger.info("[MCP] Decompiling class: {}", normalizedName);

		try {
			DecompileResult result;
			String decompilerUsed;
			if (classInfo.isJvmClass()) {
				JvmClassInfo jvmClassInfo = classInfo.asJvmClass();
				JvmDecompiler target = decompilerName == null ? decompilerManager.getTargetJvmDecompiler() : decompilerManager.getJvmDecompiler(decompilerName);
				if (target == null) {
					BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
							ErrorMapper.INVALID_PARAMS,
							"Unknown JVM decompiler: " + decompilerName,
							"Call decompile_class without 'decompiler' or use a valid JVM decompiler name."));
					return;
				}
				result = decompilerManager.decompile(target, workspace, jvmClassInfo).get(30, TimeUnit.SECONDS);
				decompilerUsed = target.getName();
			} else {
				AndroidClassInfo androidClassInfo = classInfo.asAndroidClass();
				AndroidDecompiler target = decompilerName == null ? decompilerManager.getTargetAndroidDecompiler() : decompilerManager.getAndroidDecompiler(decompilerName);
				if (target == null) {
					BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
							ErrorMapper.INVALID_PARAMS,
							"Unknown Android decompiler: " + decompilerName,
							"Call decompile_class without 'decompiler' or use a valid Android decompiler name."));
					return;
				}
				result = decompilerManager.decompile(target, workspace, androidClassInfo).get(30, TimeUnit.SECONDS);
				decompilerUsed = target.getName();
			}

			JsonObject data = new JsonObject();
			data.addProperty("className", classInfo.getName());
			data.addProperty("classKind", classInfo.isJvmClass() ? "jvm" : "android");

			if (result.getText() != null) {
				data.addProperty("source", result.getText());
				data.addProperty("decompiler", decompilerUsed);
			} else {
				data.addProperty("source", "// Decompilation failed - no output");
				if (result.getException() != null) {
					data.addProperty("error", result.getException().getMessage());
				}
			}

			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (TimeoutException e) {
			logger.error("Decompilation timed out for '{}'", className);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
					ErrorMapper.DECOMPILE_TIMEOUT,
					"Decompilation of " + className + " timed out after 30 seconds",
					"The class may be too complex. Try a different decompiler or a simpler class."));
		} catch (Exception e) {
			logger.error("Decompilation failed for '{}'", className, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Decompile " + className, e));
		}
	}
}
