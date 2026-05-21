package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.phantom.PhantomGenerator;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * Workspace utility operations: attach support resources and phantom generation.
 */
public class WorkspaceOpsHandler {
	private static final Logger logger = Logging.get(WorkspaceOpsHandler.class);

	private final WorkspaceManager workspaceManager;
	private final ResourceImporter resourceImporter;
	private final PhantomGenerator phantomGenerator;

	public WorkspaceOpsHandler(WorkspaceManager workspaceManager,
							   ResourceImporter resourceImporter,
							   PhantomGenerator phantomGenerator) {
		this.workspaceManager = workspaceManager;
		this.resourceImporter = resourceImporter;
		this.phantomGenerator = phantomGenerator;
	}

	public void handleAttachSupportResource(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String path = JsonUtil.getString(req, "path", null);
		if (path == null || path.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("path"));
			return;
		}
		try {
			var resource = resourceImporter.importResource(Paths.get(path));
			workspace.addSupportingResource(resource);
			JsonObject data = new JsonObject();
			data.addProperty("summary", "Support resource attached");
			data.addProperty("modifiedCount", 0);
			data.add("findings", new JsonArray());
			data.add("failures", new JsonArray());
			data.addProperty("elapsedMs", 0);
			data.addProperty("path", path);
			data.addProperty("supportingResources", workspace.getSupportingResources().size());
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("attach support resource failed: {}", path, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Attach support resource", e));
		}
	}

	public void handleListSupportResources(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}
		JsonArray items = new JsonArray();
		for (var res : workspace.getSupportingResources()) {
			JsonObject item = new JsonObject();
			item.addProperty("resourceType", res.getClass().getSimpleName());
			item.addProperty("jvmClassBundles", (int) res.jvmAllClassBundleStreamRecursive().count());
			item.addProperty("androidClassBundles", (int) res.androidClassBundleStreamRecursive().count());
			item.addProperty("fileBundles", (int) res.fileBundleStreamRecursive().count());
			items.add(item);
		}
		JsonObject data = new JsonObject();
		data.addProperty("summary", "Support resource list");
		data.addProperty("modifiedCount", 0);
		data.add("findings", new JsonArray());
		data.add("failures", new JsonArray());
		data.addProperty("elapsedMs", 0);
		data.add("resources", items);
		data.addProperty("count", items.size());
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	public void handleGeneratePhantoms(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}
		try {
			var generated = phantomGenerator.createPhantomsForWorkspace(workspace);
			workspace.addSupportingResource(generated);
			JsonObject data = new JsonObject();
			data.addProperty("summary", "Phantom resources generated and attached");
			data.addProperty("modifiedCount", 0);
			data.add("findings", new JsonArray());
			data.add("failures", new JsonArray());
			data.addProperty("elapsedMs", 0);
			data.addProperty("supportingResources", workspace.getSupportingResources().size());
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("generate phantoms failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Generate phantoms", e));
		}
	}
}
