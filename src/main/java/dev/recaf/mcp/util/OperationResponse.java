package dev.recaf.mcp.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Standard operation response envelope for MCP bridge operations.
 * Keeps backward compatibility with {@code status + data} while unifying result fields.
 */
public final class OperationResponse {
	private OperationResponse() {}

	public static String ok(String summary, int modifiedCount, JsonArray findings, JsonArray failures, long elapsedMs, JsonObject extra) {
		JsonObject data = new JsonObject();
		data.addProperty("summary", summary);
		data.addProperty("modifiedCount", modifiedCount);
		data.add("findings", findings == null ? new JsonArray() : findings);
		data.add("failures", failures == null ? new JsonArray() : failures);
		data.addProperty("elapsedMs", elapsedMs);
		if (extra != null) {
			for (String key : extra.keySet()) {
				data.add(key, extra.get(key));
			}
		}
		return JsonUtil.successResponse(data);
	}

	public static String ok(String summary, JsonObject extra, long elapsedMs) {
		return ok(summary, 0, new JsonArray(), new JsonArray(), elapsedMs, extra);
	}

	public static JsonObject failure(String kind, String target, String message) {
		JsonObject failure = new JsonObject();
		failure.addProperty("kind", kind);
		if (target != null) failure.addProperty("target", target);
		failure.addProperty("message", message);
		return failure;
	}

	public static JsonObject finding(String category, String severity, String className, String methodName, String reason) {
		JsonObject finding = new JsonObject();
		finding.addProperty("category", category);
		finding.addProperty("severity", severity);
		if (className != null) finding.addProperty("className", className);
		if (methodName != null) finding.addProperty("methodName", methodName);
		finding.addProperty("reason", reason);
		return finding;
	}

	public static JsonArray asArray(JsonElement element) {
		if (element != null && element.isJsonArray()) return element.getAsJsonArray();
		return new JsonArray();
	}
}
