package dev.recaf.mcp.server;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Lightweight MCP Server for Recaf - runs as a standalone process.
 * Implements the MCP JSON-RPC protocol over STDIO directly (no SDK dependency).
 * Relays tool calls to the Recaf Bridge Server via HTTP.
 *
 * Supports 28 tools: workspace management, decompilation, search, analysis,
 * mapping, bytecode editing, class diff, export, compilation, assembly, patches, and deobfuscation.
 */
public class RecafMcpServer {

	private static final Gson GSON = new GsonBuilder().create();
	private final BridgeClient bridge;
	private final OutputStream rawOut;
	private final BufferedReader in;

	public RecafMcpServer() {
		this.bridge = new BridgeClient();
		this.rawOut = System.out;
		this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
	}

	public void start() {
		System.err.println("[MCP Server] Started, waiting for input...");
		try {
			String line;
			while ((line = in.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				System.err.println("[MCP Server] Received: " + line.substring(0, Math.min(line.length(), 200)));
				try {
					JsonObject request = JsonParser.parseString(line).getAsJsonObject();
					handleRequest(request);
				} catch (Exception e) {
					System.err.println("[MCP Server] Error: " + e.getMessage());
					e.printStackTrace(System.err);
				}
			}
		} catch (IOException e) {
			System.err.println("[MCP Server] STDIO error: " + e.getMessage());
		}
		System.err.println("[MCP Server] Input stream closed, exiting.");
	}

	private void handleRequest(JsonObject request) {
		String method = request.has("method") ? request.get("method").getAsString() : null;
		JsonElement idEl = request.get("id");

		// Notifications (no id) - just acknowledge
		if (idEl == null || idEl.isJsonNull()) return;

		switch (method) {
			case "initialize" -> sendResult(idEl, buildInitializeResult());
			case "tools/list" -> sendResult(idEl, buildToolsList());
			case "tools/call" -> handleToolCall(idEl, request.getAsJsonObject("params"));
			case "resources/list" -> sendResult(idEl, buildResourcesList());
			case "resources/read" -> handleResourceRead(idEl, request.getAsJsonObject("params"));
			case "ping" -> sendResult(idEl, new JsonObject());
			default -> sendError(idEl, -32601, "Method not found: " + method);
		}
	}

	// ==================== Initialize ====================

	private JsonObject buildInitializeResult() {
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", "2024-11-05");

		JsonObject capabilities = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		capabilities.add("tools", tools);
		JsonObject resources = new JsonObject();
		resources.addProperty("subscribe", false);
		resources.addProperty("listChanged", false);
		capabilities.add("resources", resources);
		result.add("capabilities", capabilities);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "recaf-mcp");
		serverInfo.addProperty("version", "1.3.0");
		result.add("serverInfo", serverInfo);

		return result;
	}

	// ==================== Tools (28 total) ====================

	private JsonObject buildToolsList() {
		JsonArray tools = new JsonArray();

		// 1. open_jar
		tools.add(toolDef("open_jar", "Open a JAR/APK/class artifact in Recaf. Supports optional supporting resources.",
				openJarSchema()));

		// 2. close_workspace
		tools.add(toolDef("close_workspace", "Close the currently open workspace in Recaf, or close a specific workspace by ID",
				optionalProps(prop("workspaceId", "string", "Optional workspace ID to close. If omitted, closes the current workspace."))));

		// 3. list_classes (with pagination)
		tools.add(toolDef("list_classes", "List classes in current workspace (JVM+Android), with filtering and pagination.",
				optionalProps(
						prop("filter", "string", "Optional filter string to match class names (e.g. 'com/example' or 'Main')"),
						prop("classKind", "string", "Class kind filter: 'all' (default), 'jvm', or 'android'"),
						prop("offset", "integer", "Starting offset for pagination (default: 0)"),
						prop("limit", "integer", "Maximum number of classes to return (default: 500)"))));

		// 4. get_class_info
		tools.add(toolDef("get_class_info", "Get detailed class information for JVM or Android classes",
				classInfoSchema()));

		// 5. decompile_class
		tools.add(toolDef("decompile_class", "Decompile a class to source code with selectable decompiler",
				decompileSchema()));

		// 6. search_code
		tools.add(toolDef("search_code", "Search for strings, class/method/field references, or declarations in the workspace",
				searchSchema()));

		// 7. get_call_graph
		tools.add(toolDef("get_call_graph", "Get the call graph showing which methods call which, with callers and callees",
				callGraphSchema()));

		// 8. get_inheritance
		tools.add(toolDef("get_inheritance", "Get the inheritance hierarchy (parents and/or children) of a class",
				inheritanceSchema()));

		// 9. rename_symbol
		tools.add(toolDef("rename_symbol", "Rename symbols using manual/auto/similarity mode (preview/apply)",
				renameSchema()));

		// 10. export_mappings
		tools.add(toolDef("export_mappings", "Export accumulated rename mappings to a file in the specified format",
				optionalProps(
						prop("format", "string", "Mapping format (e.g. 'TinyV1', 'SRG', 'Proguard'). Omit to list available formats"),
						prop("outputPath", "string", "Absolute path to write the mapping file to"))));

		// 11. switch_workspace
		tools.add(toolDef("switch_workspace", "Switch to a previously opened workspace by its ID",
				requiredProps(prop("workspaceId", "string", "The workspace ID returned by open_jar"))));

		// 12. list_workspaces
		tools.add(toolDef("list_workspaces", "List all currently registered workspaces with their IDs, paths, and class counts",
				new JsonObject()));

		// 13. edit_bytecode
		tools.add(toolDef("edit_bytecode", "Edit bytecode: add/remove/modify methods and fields in a class",
				editBytecodeSchema()));

		// 14. diff_classes
		tools.add(toolDef("diff_classes", "Compare two classes or a class against provided source code, producing a unified diff",
				diffClassesSchema()));

		// 15. export_jar
		tools.add(toolDef("export_jar", "Export the current workspace as a JAR file (includes all modified classes)",
				requiredProps(prop("outputPath", "string", "Absolute path to write the output JAR file"))));

		// 16. export_source
		tools.add(toolDef("export_source", "Export decompiled source code to a directory. Can export a single class or all classes.",
				exportSourceSchema()));

		// 17. compile_java
		tools.add(toolDef("compile_java", "Compile Java source code and apply the compiled class to the workspace",
				compileJavaSchema()));

		// 18. disassemble_class
		tools.add(toolDef("disassemble_class", "Disassemble a class into JASM assembly text representation",
				disassembleClassSchema()));

		// 19. assemble_class
		tools.add(toolDef("assemble_class", "Assemble JASM source text and apply the resulting class to the workspace",
				assembleClassSchema()));

		// 20. method_disassemble
		tools.add(toolDef("method_disassemble", "Disassemble a single method into JASM assembly text",
				methodDisassembleSchema()));

		// 21. method_bytecode
		tools.add(toolDef("method_bytecode", "View detailed bytecode instructions for a method including opcodes, operands, try-catch blocks, and local variables",
				methodBytecodeSchema()));

		// 22. class_outline
		tools.add(toolDef("class_outline", "Get a lightweight class structure outline (fields, methods, access flags) without decompiling code",
				requiredProps(prop("className", "string", "Fully qualified class name (e.g. 'com/example/Main' or 'com.example.Main')"))));

		// 23. read_file
		tools.add(toolDef("read_file", "Read a non-class file from the workspace (e.g. META-INF/MANIFEST.MF, config files, resources)",
				readFileSchema()));

		// 24. class_delete
		tools.add(toolDef("class_delete", "Delete a class from the workspace",
				requiredProps(prop("className", "string", "Fully qualified class name to delete (e.g. 'com/example/Main' or 'com.example.Main')"))));

		// 25. patch
		tools.add(toolDef("patch", "Create or apply a workspace patch. Use 'create' to capture current modifications as a patch, or 'apply' to restore a previously created patch.",
				patchSchema()));

		// 26. batch_rename
		tools.add(toolDef("batch_rename", "Batch rename symbols using manual/auto/similarity mode.",
				batchRenameSchema()));

		// 27. eval_method
		tools.add(toolDef("eval_method", "Execute a static method in sandboxed ClassLoader with audit fields.",
				evalMethodSchema()));

		// 28. simplify_method
		tools.add(toolDef("simplify_method", "Run Recaf transformer pipelines for deobfuscation (preview/apply).",
				simplifyMethodSchema()));

		tools.add(toolDef("generate_mappings", "Generate mappings in auto or similarity mode without applying.",
				generateMappingsSchema()));
		tools.add(toolDef("list_transformers", "List available Recaf deobfuscation transformers and categories.", new JsonObject()));
		tools.add(toolDef("detect_antidecompile", "Preview anti-crasher transforms to detect anti-decompile patterns.",
				optionalProps(prop("className", "string", "Optional target class name"),
						prop("passes", "integer", "Preview passes (default: 1)"))));
		tools.add(toolDef("list_vms", "List local JVM processes available for dynamic attach.", new JsonObject()));
		tools.add(toolDef("attach_vm", "Attach a VM by PID and mount it as supporting resource.",
				requiredProps(prop("pid", "integer", "JVM process PID"))));
		tools.add(toolDef("vm_mbeans", "Read core runtime MBeans from attached VM.",
				requiredProps(prop("pid", "integer", "JVM process PID"))));
		tools.add(toolDef("script_compile", "Compile Recaf script and return diagnostics.",
				requiredProps(prop("script", "string", "Script source"))));
		tools.add(toolDef("script_run", "Run compiled or inline Recaf script (requires confirm=true).",
				scriptRunSchema()));
		tools.add(toolDef("script_cancel", "Cancel running script by runId.",
				requiredProps(prop("runId", "integer", "Run ID returned by script_run"))));
		tools.add(toolDef("script_result", "Get script execution result by runId.",
				requiredProps(prop("runId", "integer", "Run ID returned by script_run"))));
		tools.add(toolDef("attach_support_resource", "Attach supporting JAR/APK/class resource to current workspace.",
				requiredProps(prop("path", "string", "Absolute file path"))));
		tools.add(toolDef("list_support_resources", "List supporting resources of current workspace.", new JsonObject()));
		tools.add(toolDef("generate_phantoms", "Generate phantom classes and attach as support resource.", new JsonObject()));

		JsonObject result = new JsonObject();
		result.add("tools", tools);
		return result;
	}

	private void handleToolCall(JsonElement id, JsonObject params) {
		String name = params.get("name").getAsString();
		JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

		String text;
		boolean isError = false;
		try {
			text = switch (name) {
				case "open_jar" -> bridge.extractData(bridge.post("/workspace/open",
						openJarBody(args)));

				case "close_workspace" -> {
					JsonObject body = new JsonObject();
					if (args.has("workspaceId")) body.addProperty("workspaceId", getString(args, "workspaceId"));
					yield bridge.extractData(bridge.post("/workspace/close", GSON.toJson(body)));
				}

				case "list_classes" -> {
					JsonObject body = new JsonObject();
					if (args.has("filter")) body.addProperty("filter", getString(args, "filter"));
					if (args.has("classKind")) body.addProperty("classKind", getString(args, "classKind"));
					body.addProperty("offset", getIntOr(args, "offset", 0));
					body.addProperty("limit", getIntOr(args, "limit", 500));
					yield bridge.extractData(bridge.post("/workspace/classes", GSON.toJson(body)));
				}

				case "get_class_info" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					if (args.has("classKind")) body.addProperty("classKind", getString(args, "classKind"));
					yield bridge.extractData(bridge.post("/workspace/class-info", GSON.toJson(body)));
				}

				case "decompile_class" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					if (args.has("classKind")) body.addProperty("classKind", getString(args, "classKind"));
					if (args.has("decompiler")) body.addProperty("decompiler", getString(args, "decompiler"));
					if (args.has("resourceScope")) body.addProperty("resourceScope", getString(args, "resourceScope"));
					yield bridge.extractData(bridge.post("/decompile", GSON.toJson(body)));
				}

				case "search_code" -> {
					JsonObject body = new JsonObject();
					if (args.has("query")) body.addProperty("query", getString(args, "query"));
					body.addProperty("type", getStringOr(args, "type", "string"));
					body.addProperty("maxResults", getIntOr(args, "maxResults", 100));
					if (args.has("className")) body.addProperty("className", getString(args, "className"));
					if (args.has("methodName")) body.addProperty("methodName", getString(args, "methodName"));
					if (args.has("methodDesc")) body.addProperty("methodDesc", getString(args, "methodDesc"));
					if (args.has("similarityThresholdPercent")) body.addProperty("similarityThresholdPercent", getIntOr(args, "similarityThresholdPercent", 70));
					if (args.has("primaryResourceOnly")) body.addProperty("primaryResourceOnly", args.get("primaryResourceOnly").getAsBoolean());
					if (args.has("numberMode")) body.addProperty("numberMode", getString(args, "numberMode"));
					yield bridge.extractData(bridge.post("/search", GSON.toJson(body)));
				}

				case "get_call_graph" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					if (args.has("methodName")) body.addProperty("methodName", getString(args, "methodName"));
					body.addProperty("depth", getIntOr(args, "depth", 3));
					yield bridge.extractData(bridge.post("/analysis/call-graph", GSON.toJson(body)));
				}

				case "get_inheritance" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					body.addProperty("direction", getStringOr(args, "direction", "both"));
					yield bridge.extractData(bridge.post("/analysis/inheritance", GSON.toJson(body)));
				}

				case "rename_symbol" -> {
					JsonObject body = new JsonObject();
					if (args.has("type")) body.addProperty("type", getString(args, "type"));
					if (args.has("oldName")) body.addProperty("oldName", getString(args, "oldName"));
					if (args.has("newName")) body.addProperty("newName", getString(args, "newName"));
					if (args.has("className")) body.addProperty("className", getString(args, "className"));
					if (args.has("descriptor")) body.addProperty("descriptor", getString(args, "descriptor"));
					if (args.has("mode")) body.addProperty("mode", getString(args, "mode"));
					if (args.has("applyMode")) body.addProperty("applyMode", getString(args, "applyMode"));
					if (args.has("workspaceId")) body.addProperty("workspaceId", getString(args, "workspaceId"));
					if (args.has("sourceWorkspaceId")) body.addProperty("sourceWorkspaceId", getString(args, "sourceWorkspaceId"));
					if (args.has("targetWorkspaceId")) body.addProperty("targetWorkspaceId", getString(args, "targetWorkspaceId"));
					yield bridge.extractData(bridge.post("/mapping/rename", GSON.toJson(body)));
				}

				case "export_mappings" -> {
					JsonObject body = new JsonObject();
					if (args.has("format")) body.addProperty("format", getString(args, "format"));
					if (args.has("outputPath")) body.addProperty("outputPath", getString(args, "outputPath"));
					yield bridge.extractData(bridge.post("/mapping/export", GSON.toJson(body)));
				}

				case "switch_workspace" -> bridge.extractData(bridge.post("/workspace/switch",
						jsonBody("workspaceId", getString(args, "workspaceId"))));

				case "list_workspaces" -> bridge.extractData(bridge.get("/workspace/list-workspaces"));

				case "edit_bytecode" -> {
					String operation = getStringOr(args, "operation", "");
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					String endpoint = switch (operation) {
						case "edit_method" -> {
							body.addProperty("methodName", getString(args, "methodName"));
							body.addProperty("methodDesc", getString(args, "methodDesc"));
							if (args.has("accessFlags")) body.addProperty("accessFlags", args.get("accessFlags").getAsInt());
							yield "/bytecode/edit-method";
						}
						case "edit_field" -> {
							body.addProperty("fieldName", getString(args, "fieldName"));
							if (args.has("descriptor")) body.addProperty("descriptor", getString(args, "descriptor"));
							if (args.has("accessFlags")) body.addProperty("accessFlags", args.get("accessFlags").getAsInt());
							yield "/bytecode/edit-field";
						}
						case "remove_member" -> {
							body.addProperty("memberName", getString(args, "memberName"));
							body.addProperty("memberType", getString(args, "memberType"));
							if (args.has("descriptor")) body.addProperty("descriptor", getString(args, "descriptor"));
							yield "/bytecode/remove-member";
						}
						case "add_field" -> {
							body.addProperty("fieldName", getString(args, "fieldName"));
							body.addProperty("descriptor", getString(args, "descriptor"));
							if (args.has("accessFlags")) body.addProperty("accessFlags", args.get("accessFlags").getAsInt());
							yield "/bytecode/add-field";
						}
						case "add_method" -> {
							body.addProperty("methodName", getString(args, "methodName"));
							body.addProperty("methodDesc", getString(args, "methodDesc"));
							if (args.has("accessFlags")) body.addProperty("accessFlags", args.get("accessFlags").getAsInt());
							yield "/bytecode/add-method";
						}
						default -> throw new IllegalArgumentException(
								"Unknown operation: " + operation + ". Use: edit_method, edit_field, remove_member, add_field, add_method");
					};
					yield bridge.extractData(bridge.post(endpoint, GSON.toJson(body)));
				}

				case "diff_classes" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className1", getString(args, "className1"));
					if (args.has("className2")) body.addProperty("className2", getString(args, "className2"));
					if (args.has("source")) body.addProperty("source", getString(args, "source"));
					yield bridge.extractData(bridge.post("/diff", GSON.toJson(body)));
				}

				case "export_jar" -> bridge.extractData(bridge.post("/export/jar",
						jsonBody("outputPath", getString(args, "outputPath"))));

				case "export_source" -> {
					JsonObject body = new JsonObject();
					body.addProperty("outputDir", getString(args, "outputDir"));
					if (args.has("className")) body.addProperty("className", getString(args, "className"));
					yield bridge.extractData(bridge.post("/export/source", GSON.toJson(body)));
				}

				case "compile_java" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					body.addProperty("source", getString(args, "source"));
					if (args.has("targetVersion")) body.addProperty("targetVersion", args.get("targetVersion").getAsInt());
					if (args.has("debug")) body.addProperty("debug", args.get("debug").getAsBoolean());
					yield bridge.extractData(bridge.post("/compile", GSON.toJson(body)));
				}

				case "disassemble_class" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					if (args.has("maxChars")) body.addProperty("maxChars", args.get("maxChars").getAsInt());
					yield bridge.extractData(bridge.post("/disassemble", GSON.toJson(body)));
				}

				case "assemble_class" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					body.addProperty("source", getString(args, "source"));
					yield bridge.extractData(bridge.post("/assemble", GSON.toJson(body)));
				}

				case "method_disassemble" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					body.addProperty("methodName", getString(args, "methodName"));
					body.addProperty("methodDesc", getString(args, "methodDesc"));
					if (args.has("maxChars")) body.addProperty("maxChars", args.get("maxChars").getAsInt());
					yield bridge.extractData(bridge.post("/disassemble/method", GSON.toJson(body)));
				}

				case "method_bytecode" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					body.addProperty("methodName", getString(args, "methodName"));
					body.addProperty("methodDesc", getString(args, "methodDesc"));
					yield bridge.extractData(bridge.post("/bytecode/instructions", GSON.toJson(body)));
				}

				case "class_outline" -> bridge.extractData(bridge.post("/workspace/outline",
						jsonBody("className", getString(args, "className"))));

				case "read_file" -> {
					JsonObject body = new JsonObject();
					body.addProperty("path", getString(args, "path"));
					if (args.has("maxChars")) body.addProperty("maxChars", args.get("maxChars").getAsInt());
					yield bridge.extractData(bridge.post("/workspace/read-file", GSON.toJson(body)));
				}

				case "class_delete" -> bridge.extractData(bridge.post("/workspace/delete-class",
						jsonBody("className", getString(args, "className"))));

				case "patch" -> {
					JsonObject body = new JsonObject();
					body.addProperty("action", getString(args, "action"));
					if (args.has("patchJson")) body.addProperty("patchJson", getString(args, "patchJson"));
					yield bridge.extractData(bridge.post("/patch", GSON.toJson(body)));
				}

				case "batch_rename" -> {
					JsonObject body = new JsonObject();
					if (args.has("mappings")) body.add("mappings", args.getAsJsonArray("mappings"));
					if (args.has("mode")) body.addProperty("mode", getString(args, "mode"));
					if (args.has("applyMode")) body.addProperty("applyMode", getString(args, "applyMode"));
					if (args.has("workspaceId")) body.addProperty("workspaceId", getString(args, "workspaceId"));
					if (args.has("sourceWorkspaceId")) body.addProperty("sourceWorkspaceId", getString(args, "sourceWorkspaceId"));
					if (args.has("targetWorkspaceId")) body.addProperty("targetWorkspaceId", getString(args, "targetWorkspaceId"));
					yield bridge.extractData(bridge.post("/mapping/batch-rename", GSON.toJson(body)));
				}

				case "eval_method" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					body.addProperty("methodName", getString(args, "methodName"));
					body.addProperty("methodDesc", getString(args, "methodDesc"));
					if (args.has("arguments")) body.add("arguments", args.getAsJsonArray("arguments"));
					if (args.has("timeout")) body.addProperty("timeout", args.get("timeout").getAsInt());
					if (args.has("auditTag")) body.addProperty("auditTag", getString(args, "auditTag"));
					yield bridge.extractData(bridge.post("/deobf/eval-method", GSON.toJson(body)));
				}

				case "simplify_method" -> {
					JsonObject body = new JsonObject();
					if (args.has("className")) body.addProperty("className", getString(args, "className"));
					if (args.has("methodName")) body.addProperty("methodName", getString(args, "methodName"));
					if (args.has("methodDesc")) body.addProperty("methodDesc", getString(args, "methodDesc"));
					if (args.has("passes")) body.addProperty("passes", args.get("passes").getAsInt());
					if (args.has("mode")) body.addProperty("mode", getString(args, "mode"));
					if (args.has("preset")) body.addProperty("preset", getString(args, "preset"));
					if (args.has("transformers")) body.add("transformers", args.getAsJsonArray("transformers"));
					yield bridge.extractData(bridge.post("/deobf/simplify-method", GSON.toJson(body)));
				}

				case "generate_mappings" -> bridge.extractData(bridge.post("/mapping/generate", GSON.toJson(args)));
				case "list_transformers" -> bridge.extractData(bridge.get("/deobf/transformers"));
				case "detect_antidecompile" -> bridge.extractData(bridge.post("/deobf/detect-antidecompile", GSON.toJson(args)));
				case "list_vms" -> bridge.extractData(bridge.get("/attach/list-vms"));
				case "attach_vm" -> bridge.extractData(bridge.post("/attach/attach-vm", GSON.toJson(args)));
				case "vm_mbeans" -> bridge.extractData(bridge.post("/attach/mbeans", GSON.toJson(args)));
				case "script_compile" -> bridge.extractData(bridge.post("/script/compile", GSON.toJson(args)));
				case "script_run" -> bridge.extractData(bridge.post("/script/run", GSON.toJson(args)));
				case "script_cancel" -> bridge.extractData(bridge.post("/script/cancel", GSON.toJson(args)));
				case "script_result" -> bridge.extractData(bridge.post("/script/result", GSON.toJson(args)));
				case "attach_support_resource" -> bridge.extractData(bridge.post("/workspace/support-resource", GSON.toJson(args)));
				case "list_support_resources" -> bridge.extractData(bridge.get("/workspace/list-support-resources"));
				case "generate_phantoms" -> bridge.extractData(bridge.post("/workspace/generate-phantoms", "{}"));

				default -> "{\"error\":\"Unknown tool: " + name + "\"}";
			};
		} catch (Exception e) {
			text = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
		}

		// Detect error responses and set isError flag
		if (text != null && text.contains("\"error\"")) {
			isError = true;
		}

		// Build CallToolResult
		JsonObject result = new JsonObject();
		JsonArray content = new JsonArray();
		JsonObject textContent = new JsonObject();
		textContent.addProperty("type", "text");
		textContent.addProperty("text", text);
		content.add(textContent);
		result.add("content", content);
		result.addProperty("isError", isError);

		sendResult(id, result);
	}

	// ==================== Resources ====================

	private JsonObject buildResourcesList() {
		JsonArray resources = new JsonArray();

		JsonObject ws = new JsonObject();
		ws.addProperty("uri", "recaf://workspace");
		ws.addProperty("name", "Current Workspace");
		ws.addProperty("description", "Information about the currently open workspace in Recaf");
		ws.addProperty("mimeType", "application/json");
		resources.add(ws);

		JsonObject cls = new JsonObject();
		cls.addProperty("uri", "recaf://classes");
		cls.addProperty("name", "Class List");
		cls.addProperty("description", "List of all classes in the current workspace");
		cls.addProperty("mimeType", "application/json");
		resources.add(cls);

		JsonObject result = new JsonObject();
		result.add("resources", resources);
		return result;
	}

	private void handleResourceRead(JsonElement id, JsonObject params) {
		String uri = params.get("uri").getAsString();
		String data;
		try {
			data = switch (uri) {
				case "recaf://workspace" -> bridge.extractData(bridge.get("/workspace/info"));
				case "recaf://classes" -> bridge.extractData(bridge.post("/workspace/classes", "{}"));
				default -> "{\"error\":\"Unknown resource: " + uri + "\"}";
			};
		} catch (Exception e) {
			data = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
		}

		JsonObject result = new JsonObject();
		JsonArray contents = new JsonArray();
		JsonObject item = new JsonObject();
		item.addProperty("uri", uri);
		item.addProperty("mimeType", "application/json");
		item.addProperty("text", data);
		contents.add(item);
		result.add("contents", contents);

		sendResult(id, result);
	}

	// ==================== JSON-RPC Helpers ====================

	private void sendResult(JsonElement id, JsonObject result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		writeLine(GSON.toJson(response));
	}

	private void sendError(JsonElement id, int code, String message) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		response.add("error", error);
		writeLine(GSON.toJson(response));
	}

	private synchronized void writeLine(String json) {
		try {
			byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
			rawOut.write(bytes);
			rawOut.flush();
			System.err.println("[MCP Server] Sent: " + json.substring(0, Math.min(json.length(), 200)));
		} catch (IOException e) {
			System.err.println("[MCP Server] Write error: " + e.getMessage());
		}
	}

	// ==================== Schema Helpers ====================

	private static JsonObject toolDef(String name, String description, JsonObject inputSchema) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		inputSchema.addProperty("type", "object");
		tool.add("inputSchema", inputSchema);
		return tool;
	}

	private static JsonObject prop(String name, String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("name", name);
		p.addProperty("type", type);
		p.addProperty("description", description);
		return p;
	}

	private static JsonObject requiredProps(JsonObject... props) {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();
		for (JsonObject p : props) {
			String name = p.get("name").getAsString();
			JsonObject propDef = new JsonObject();
			propDef.addProperty("type", p.get("type").getAsString());
			propDef.addProperty("description", p.get("description").getAsString());
			properties.add(name, propDef);
			required.add(name);
		}
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}

	private static JsonObject optionalProps(JsonObject... props) {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		for (JsonObject p : props) {
			String name = p.get("name").getAsString();
			JsonObject propDef = new JsonObject();
			propDef.addProperty("type", p.get("type").getAsString());
			propDef.addProperty("description", p.get("description").getAsString());
			properties.add(name, propDef);
		}
		schema.add("properties", properties);
		return schema;
	}

	private static JsonObject searchSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		JsonObject query = new JsonObject();
		query.addProperty("type", "string");
		query.addProperty("description", "Search query string");
		properties.add("query", query);
		JsonObject type = new JsonObject();
		type.addProperty("type", "string");
		type.addProperty("description", "Type of search");
		JsonArray enumVals = new JsonArray();
		enumVals.add("string");
		enumVals.add("number");
		enumVals.add("instruction");
		enumVals.add("class");
		enumVals.add("method");
		enumVals.add("field");
		enumVals.add("declaration");
		enumVals.add("similar_class");
		enumVals.add("similar_method");
		type.add("enum", enumVals);
		properties.add("type", type);
		properties.add("className", typedProp("string", "Class name for similar_class/similar_method"));
		properties.add("methodName", typedProp("string", "Method name for similar_method"));
		properties.add("methodDesc", typedProp("string", "Method descriptor for similar_method"));
		properties.add("similarityThresholdPercent", typedProp("integer", "Similarity threshold percent (default: 70)"));
		properties.add("primaryResourceOnly", typedProp("boolean", "Search only primary resource for similarity (default: true)"));
		properties.add("numberMode", typedProp("string", "Number match mode: equal|gt|gte|lt|lte"));
		JsonObject maxResults = new JsonObject();
		maxResults.addProperty("type", "integer");
		maxResults.addProperty("description", "Maximum number of results to return (default: 100)");
		properties.add("maxResults", maxResults);
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		schema.add("required", required);
		return schema;
	}

	private static JsonObject callGraphSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main')"));
		properties.add("methodName", typedProp("string", "Optional method name to focus on"));
		properties.add("depth", typedProp("integer", "Maximum depth of call graph traversal (default: 3)"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject inheritanceSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main')"));
		JsonObject dir = typedProp("string", "Direction: 'both', 'parents', or 'children' (default: 'both')");
		JsonArray enumVals = new JsonArray();
		enumVals.add("both"); enumVals.add("parents"); enumVals.add("children");
		dir.add("enum", enumVals);
		properties.add("direction", dir);
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject renameSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("mode", typedProp("string", "Rename mode: manual|auto|similarity"));
		properties.add("applyMode", typedProp("string", "Execution mode: preview|apply"));
		properties.add("workspaceId", typedProp("string", "Target workspace ID (optional)"));
		properties.add("sourceWorkspaceId", typedProp("string", "Source workspace ID for similarity mode"));
		properties.add("targetWorkspaceId", typedProp("string", "Target workspace ID for similarity mode"));
		JsonObject type = typedProp("string", "Type of symbol to rename");
		JsonArray enumVals = new JsonArray();
		enumVals.add("class"); enumVals.add("field"); enumVals.add("method");
		type.add("enum", enumVals);
		properties.add("type", type);
		properties.add("oldName", typedProp("string", "Current name of the symbol"));
		properties.add("newName", typedProp("string", "New name for the symbol"));
		properties.add("className", typedProp("string", "Owning class name (required for field/method renames)"));
		properties.add("descriptor", typedProp("string", "Optional descriptor for field/method disambiguation"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("mode");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject editBytecodeSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main')"));
		JsonObject op = typedProp("string", "Operation: 'edit_method', 'edit_field', 'remove_member', 'add_field', 'add_method'");
		JsonArray enumVals = new JsonArray();
		enumVals.add("edit_method"); enumVals.add("edit_field"); enumVals.add("remove_member");
		enumVals.add("add_field"); enumVals.add("add_method");
		op.add("enum", enumVals);
		properties.add("operation", op);
		properties.add("methodName", typedProp("string", "Method name (for edit_method, add_method)"));
		properties.add("methodDesc", typedProp("string", "Method descriptor e.g. '(I)V' (for edit_method, add_method)"));
		properties.add("fieldName", typedProp("string", "Field name (for edit_field, add_field)"));
		properties.add("descriptor", typedProp("string", "Field descriptor e.g. 'I' or 'Ljava/lang/String;' (for edit_field, add_field, remove_member)"));
		properties.add("accessFlags", typedProp("integer", "Access flags as integer (e.g. 1=public, 2=private)"));
		properties.add("memberName", typedProp("string", "Member name to remove (for remove_member)"));
		JsonObject memberType = typedProp("string", "Member type to remove: 'method' or 'field' (for remove_member)");
		JsonArray mtEnum = new JsonArray();
		mtEnum.add("method"); mtEnum.add("field");
		memberType.add("enum", mtEnum);
		properties.add("memberType", memberType);
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className"); required.add("operation");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject diffClassesSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className1", typedProp("string", "First class to compare (will be decompiled)"));
		properties.add("className2", typedProp("string", "Second class to compare (will be decompiled). Provide this OR 'source'."));
		properties.add("source", typedProp("string", "Source code to compare against className1. Provide this OR 'className2'."));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className1");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject exportSourceSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("outputDir", typedProp("string", "Absolute path to the output directory for decompiled source files"));
		properties.add("className", typedProp("string", "Optional: export only this class. If omitted, exports all classes."));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("outputDir");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject compileJavaSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name in dot format (e.g. 'com.example.Main')"));
		properties.add("source", typedProp("string", "Java source code to compile"));
		properties.add("targetVersion", typedProp("integer", "Java target version (e.g. 11, 17, 21). If omitted, uses default."));
		properties.add("debug", typedProp("boolean", "Include debug info (line numbers, variables). Default: true"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		required.add("source");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject disassembleClassSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main' or 'com.example.Main')"));
		properties.add("maxChars", typedProp("integer", "Maximum characters to return (default: 120000). Truncates if exceeded."));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject assembleClassSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main' or 'com.example.Main')"));
		properties.add("source", typedProp("string", "JASM assembly source text to assemble"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		required.add("source");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject methodDisassembleSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main')"));
		properties.add("methodName", typedProp("string", "Method name to disassemble"));
		properties.add("methodDesc", typedProp("string", "Method descriptor (e.g. '(Ljava/lang/String;)V')"));
		properties.add("maxChars", typedProp("integer", "Maximum characters to return (default: 120000). Truncates if exceeded."));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		required.add("methodName");
		required.add("methodDesc");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject methodBytecodeSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main')"));
		properties.add("methodName", typedProp("string", "Method name to inspect"));
		properties.add("methodDesc", typedProp("string", "Method descriptor (e.g. '(I)V')"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		required.add("methodName");
		required.add("methodDesc");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject readFileSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("path", typedProp("string", "File path relative to archive root (e.g. 'META-INF/MANIFEST.MF')"));
		properties.add("maxChars", typedProp("integer", "Maximum characters to return (default: 60000). Truncates if exceeded."));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("path");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject patchSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		JsonObject action = typedProp("string", "Patch action: 'create' to capture workspace changes, 'apply' to restore a patch");
		JsonArray enumVals = new JsonArray();
		enumVals.add("create");
		enumVals.add("apply");
		action.add("enum", enumVals);
		properties.add("action", action);
		properties.add("patchJson", typedProp("string", "Patch JSON string (required when action is 'apply')"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("action");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject batchRenameSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("mode", typedProp("string", "Rename mode: manual|auto|similarity"));
		properties.add("applyMode", typedProp("string", "Execution mode: preview|apply"));
		properties.add("workspaceId", typedProp("string", "Target workspace ID (optional)"));
		properties.add("sourceWorkspaceId", typedProp("string", "Source workspace ID for similarity mode"));
		properties.add("targetWorkspaceId", typedProp("string", "Target workspace ID for similarity mode"));
		JsonObject mappings = new JsonObject();
		mappings.addProperty("type", "array");
		mappings.addProperty("description", "Array of rename mappings. Each entry: {type, oldName, newName, className?, descriptor?}");
		JsonObject items = new JsonObject();
		items.addProperty("type", "object");
		JsonObject itemProps = new JsonObject();
		JsonObject type = typedProp("string", "Type of symbol: 'class', 'field', or 'method'");
		JsonArray enumVals = new JsonArray();
		enumVals.add("class"); enumVals.add("field"); enumVals.add("method");
		type.add("enum", enumVals);
		itemProps.add("type", type);
		itemProps.add("oldName", typedProp("string", "Current name of the symbol"));
		itemProps.add("newName", typedProp("string", "New name for the symbol"));
		itemProps.add("className", typedProp("string", "Owning class name (required for field/method)"));
		itemProps.add("descriptor", typedProp("string", "Optional descriptor for field/method disambiguation"));
		items.add("properties", itemProps);
		JsonArray itemRequired = new JsonArray();
		itemRequired.add("type"); itemRequired.add("oldName"); itemRequired.add("newName");
		items.add("required", itemRequired);
		mappings.add("items", items);
		properties.add("mappings", mappings);
		schema.add("properties", properties);
		schema.add("required", new JsonArray());
		return schema;
	}

	private static JsonObject evalMethodSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name containing the target method (e.g. 'com/example/StringDecryptor')"));
		properties.add("methodName", typedProp("string", "Static method name to execute"));
		properties.add("methodDesc", typedProp("string", "Method descriptor for overload resolution (e.g. '(Ljava/lang/String;)Ljava/lang/String;')"));
		JsonObject arguments = new JsonObject();
		arguments.addProperty("type", "array");
		arguments.addProperty("description", "Method arguments as JSON array of {type, value} objects. Supported types: string, int, long, float, double, boolean, byte, short, char, null");
		JsonObject argItem = new JsonObject();
		argItem.addProperty("type", "object");
		arguments.add("items", argItem);
		properties.add("arguments", arguments);
		properties.add("timeout", typedProp("integer", "Maximum execution time in milliseconds (default: 5000)"));
		properties.add("auditTag", typedProp("string", "Audit tag for script/eval traceability"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		required.add("methodName");
		required.add("methodDesc");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject simplifyMethodSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Optional class scope filter"));
		properties.add("mode", typedProp("string", "Execution mode: preview|apply"));
		properties.add("preset", typedProp("string", "Transformer preset: generic_all|generic_anticrasher|generic_optimize|generic_restoration|specific"));
		properties.add("transformers", typedProp("array", "Optional explicit transformer class names/simple names"));
		properties.add("passes", typedProp("integer", "Maximum transformer passes (default: 3)"));
		schema.add("properties", properties);
		schema.add("required", new JsonArray());
		return schema;
	}

	private static JsonObject openJarSchema() {
		JsonObject schema = requiredProps(prop("path", "string", "Absolute path to JAR/APK/class file"));
		var properties = schema.getAsJsonObject("properties");
		JsonObject arr = new JsonObject();
		arr.addProperty("type", "array");
		arr.addProperty("description", "Optional supporting resource paths");
		JsonObject item = new JsonObject();
		item.addProperty("type", "string");
		arr.add("items", item);
		properties.add("supportingPaths", arr);
		return schema;
	}

	private static JsonObject classInfoSchema() {
		JsonObject schema = requiredProps(prop("className", "string", "Fully qualified class name"));
		schema.getAsJsonObject("properties").add("classKind", typedProp("string", "Optional class kind: all|jvm|android"));
		return schema;
	}

	private static JsonObject decompileSchema() {
		JsonObject schema = requiredProps(prop("className", "string", "Fully qualified class name"));
		var props = schema.getAsJsonObject("properties");
		props.add("classKind", typedProp("string", "Optional class kind: auto|jvm|android"));
		props.add("decompiler", typedProp("string", "Optional decompiler name"));
		props.add("resourceScope", typedProp("string", "Optional resource scope filter"));
		return schema;
	}

	private static JsonObject generateMappingsSchema() {
		JsonObject schema = new JsonObject();
		JsonObject props = new JsonObject();
		props.add("mode", typedProp("string", "Generation mode: auto|similarity"));
		props.add("workspaceId", typedProp("string", "Workspace ID for auto mode"));
		props.add("sourceWorkspaceId", typedProp("string", "Source workspace ID for similarity mode"));
		props.add("targetWorkspaceId", typedProp("string", "Target workspace ID for similarity mode"));
		props.add("classSimilarityThresholdPercent", typedProp("integer", "Similarity threshold for class matching"));
		props.add("memberSimilarityThresholdPercent", typedProp("integer", "Similarity threshold for member matching"));
		schema.add("properties", props);
		return schema;
	}

	private static JsonObject scriptRunSchema() {
		JsonObject schema = new JsonObject();
		JsonObject props = new JsonObject();
		props.add("compileId", typedProp("integer", "Optional compileId returned by script_compile"));
		props.add("script", typedProp("string", "Optional inline script source"));
		props.add("confirm", typedProp("boolean", "Must be true to run"));
		schema.add("properties", props);
		JsonArray required = new JsonArray();
		required.add("confirm");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject typedProp(String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		return p;
	}

	// ==================== Arg Helpers ====================

	private static String getString(JsonObject obj, String key) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
	}

	private static String getStringOr(JsonObject obj, String key, String def) {
		String v = getString(obj, key);
		return v != null ? v : def;
	}

	private static int getIntOr(JsonObject obj, String key, int def) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : def;
	}

	private static String escapeJson(String s) {
		if (s == null) return "null";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private static String jsonBody(String key, String value) {
		return "{\"" + key + "\":\"" + escapeJson(value) + "\"}";
	}

	private static String openJarBody(JsonObject args) {
		JsonObject body = new JsonObject();
		body.addProperty("path", getString(args, "path"));
		if (args.has("supportingPaths")) body.add("supportingPaths", args.getAsJsonArray("supportingPaths"));
		return GSON.toJson(body);
	}

	// ==================== Main ====================

	public static void main(String[] args) {
		new RecafMcpServer().start();
	}
}
