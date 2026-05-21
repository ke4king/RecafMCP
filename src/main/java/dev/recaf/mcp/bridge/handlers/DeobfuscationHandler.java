package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import dev.recaf.mcp.util.OperationResponse;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.CycleClassRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DuplicateAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DuplicateCatchMergingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.EnumNameRestorationTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.FrameRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.GotoInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalSignatureRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalVarargsRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.KotlinNameRestorationTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongExceptionRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaquePredicateFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.RedundantTryCatchRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.SourceNameRestorationTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.UnknownAttributeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableTableNormalizingTransformer;
import software.coley.recaf.services.deobfuscation.transform.specific.DashOpaqueSeedFoldingTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

/**
 * Handles deobfuscation operations:
 * <ul>
 *   <li>{@code eval_method} — execute a static method in a sandboxed ClassLoader (e.g. string decryption)</li>
 *   <li>{@code simplify_method} — multi-pass bytecode simplification (constant folding, dead branch elimination, etc.)</li>
 * </ul>
 */
public class DeobfuscationHandler {
	private static final Logger logger = Logging.get(DeobfuscationHandler.class);
	private static final int DEFAULT_EVAL_TIMEOUT = 5000;
	private static final int DEFAULT_SIMPLIFY_PASSES = 3;

	private final WorkspaceManager workspaceManager;
	private final TransformationManager transformationManager;
	private final TransformationApplierService transformationApplierService;

	public DeobfuscationHandler(WorkspaceManager workspaceManager,
								TransformationManager transformationManager,
								TransformationApplierService transformationApplierService) {
		this.workspaceManager = workspaceManager;
		this.transformationManager = transformationManager;
		this.transformationApplierService = transformationApplierService;
	}

	// ==================== eval_method ====================

	/**
	 * POST /deobf/eval-method
	 * Execute a static method from the workspace in a sandboxed ClassLoader.
	 * Primary use case: calling obfuscator string decryption functions.
	 */
	public void handleEvalMethod(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String methodName = JsonUtil.getString(req, "methodName", null);
		String methodDesc = JsonUtil.getString(req, "methodDesc", null);

		if (className == null || methodName == null || methodDesc == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className", "methodName", "methodDesc"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		int timeout = JsonUtil.getInt(req, "timeout", DEFAULT_EVAL_TIMEOUT);

		// Parse arguments
		Object[] args;
		Class<?>[] argTypes;
		try {
			Type[] asmArgTypes = Type.getArgumentTypes(methodDesc);
			argTypes = new Class<?>[asmArgTypes.length];
			args = new Object[asmArgTypes.length];

			JsonArray argsArray = req.has("arguments") && req.get("arguments").isJsonArray()
					? req.getAsJsonArray("arguments") : new JsonArray();

			for (int i = 0; i < asmArgTypes.length; i++) {
				argTypes[i] = asmTypeToClass(asmArgTypes[i]);
				if (i < argsArray.size()) {
					JsonElement el = argsArray.get(i);
					if (el.isJsonObject()) {
						JsonObject argObj = el.getAsJsonObject();
						args[i] = parseArgValue(argObj);
					} else {
						args[i] = defaultForType(argTypes[i]);
					}
				} else {
					args[i] = defaultForType(argTypes[i]);
				}
			}
		} catch (Exception e) {
			BridgeServer.sendJson(exchange, 400,
					ErrorMapper.errorResponse(ErrorMapper.INVALID_PARAMS,
							"Failed to parse arguments: " + e.getMessage(),
							"Arguments should be a JSON array of {type, value} objects. Supported types: string, int, long, float, double, boolean, byte, short, char, null."));
			return;
		}

		// Verify class exists in workspace
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		// Execute in sandboxed ClassLoader with timeout
		ExecutorService executor = Executors.newSingleThreadExecutor();
		long startTime = System.currentTimeMillis();
		try {
			JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
			WorkspaceClassLoader loader = new WorkspaceClassLoader(bundle);

			Class<?> clazz = loader.loadClass(normalizedName.replace('/', '.'));
			Method method = clazz.getDeclaredMethod(methodName, argTypes);
			method.setAccessible(true);

			// Verify static
			if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
				BridgeServer.sendJson(exchange, 400,
						ErrorMapper.errorResponse(ErrorMapper.EVAL_FAILED,
								"Method " + methodName + " is not static",
								"eval_method only supports static methods. Use get_class_info to check method signatures."));
				return;
			}

			Future<Object> future = executor.submit(() -> method.invoke(null, args));
			Object result;
			try {
				result = future.get(timeout, TimeUnit.MILLISECONDS);
			} catch (TimeoutException te) {
				future.cancel(true);
				long elapsed = System.currentTimeMillis() - startTime;
				BridgeServer.sendJson(exchange, 200,
						ErrorMapper.errorResponse(ErrorMapper.EVAL_TIMEOUT,
								"Method execution timed out after " + elapsed + "ms (limit: " + timeout + "ms)",
								"Increase the 'timeout' parameter or check if the method has an infinite loop."));
				return;
			}

			long elapsed = System.currentTimeMillis() - startTime;

			JsonObject data = new JsonObject();
			data.addProperty("result", result != null ? result.toString() : "null");
			data.addProperty("resultType", result != null ? result.getClass().getName() : "null");
			data.addProperty("executionTimeMs", elapsed);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] eval_method {}.{}{} = {} ({}ms)", normalizedName, methodName, methodDesc, result, elapsed);

		} catch (Exception e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			logger.error("eval_method failed for {}.{}{}", className, methodName, methodDesc, e);
			BridgeServer.sendJson(exchange, 500,
					ErrorMapper.errorResponse(ErrorMapper.EVAL_FAILED,
							"Eval failed: " + cause.getClass().getSimpleName() + " — " + cause.getMessage(),
							"The target method threw an exception or a required class is missing from the workspace. Check that all dependencies are available."));
		} finally {
			executor.shutdownNow();
		}
	}

	// ==================== simplify_method ====================

	/**
	 * POST /deobf/simplify-method
	 * Multi-pass bytecode simplification: constant folding, dead branch elimination,
	 * goto chain combining, NOP cleanup, unreachable code removal.
	 */
	public void handleSimplifyMethod(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String mode = JsonUtil.getString(req, "mode", "preview");
		String preset = JsonUtil.getString(req, "preset", "generic_all");
		int maxPasses = JsonUtil.getInt(req, "passes", DEFAULT_SIMPLIFY_PASSES);
		try {
			List<Class<? extends JvmClassTransformer>> transformers = resolveTransformers(preset, req);
			TransformationApplier applier = transformationApplierService.newApplier(workspace);
			applier.setMaxPasses(maxPasses);

			String classFilter = className == null ? null : className.replace('.', '/');
			TransformationFeedback feedback = new FilteringFeedback(classFilter);
			var result = applier.transformJvm(transformers, feedback);
			if ("apply".equalsIgnoreCase(mode)) {
				result.apply();
			}

			JsonObject data = new JsonObject();
			data.addProperty("summary", "apply".equalsIgnoreCase(mode) ? "Transformers applied" : "Transformers previewed");
			data.addProperty("modifiedCount", result.getTransformedClasses().size());
			data.add("findings", new JsonArray());
			JsonArray failures = new JsonArray();
			result.getTransformerFailures().forEach((pathNode, byTransformer) ->
					byTransformer.forEach((type, err) -> failures.add(OperationResponse.failure(
							type.getSimpleName(), pathNode.getValue().getName(), err.getMessage()))));
			data.add("failures", failures);
			data.addProperty("elapsedMs", 0);
			data.addProperty("mode", mode);
			data.addProperty("passes", maxPasses);
			data.addProperty("transformerCount", transformers.size());
			data.add("transformers", JsonUtil.gson().toJsonTree(transformers.stream().map(Class::getName).toList()));
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] simplify_method completed mode={} transformers={}", mode, transformers.size());

		} catch (Exception e) {
			logger.error("simplify_method failed for {}", className, e);
			BridgeServer.sendJson(exchange, 500,
					ErrorMapper.errorResponse(ErrorMapper.SIMPLIFY_FAILED,
							"Simplification failed: " + e.getMessage(),
							"The method bytecode may be malformed. Try decompiling the class first to check."));
		}
	}

	public void handleListTransformers(HttpExchange exchange) throws IOException {
		JsonArray array = new JsonArray();
		for (Class<? extends JvmClassTransformer> transformer : transformationManager.getJvmClassTransformers()) {
			JsonObject item = new JsonObject();
			item.addProperty("name", transformer.getName());
			item.addProperty("simpleName", transformer.getSimpleName());
			item.addProperty("category", categorize(transformer));
			array.add(item);
		}
		JsonObject data = new JsonObject();
		data.addProperty("summary", "Transformer list");
		data.addProperty("modifiedCount", 0);
		data.add("findings", new JsonArray());
		data.add("failures", new JsonArray());
		data.addProperty("elapsedMs", 0);
		data.add("transformers", array);
		data.addProperty("count", array.size());
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	public void handleDetectAntiDecompile(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String classFilter = className == null ? null : className.replace('.', '/');
		int maxPasses = JsonUtil.getInt(req, "passes", 1);
		try {
			List<Class<? extends JvmClassTransformer>> antiCrash = presetAntiCrash();
			TransformationApplier applier = transformationApplierService.newApplier(workspace);
			applier.setMaxPasses(maxPasses);
			var result = applier.transformJvm(antiCrash, new FilteringFeedback(classFilter));
			JsonArray findings = new JsonArray();
			result.getModifiedClassesPerTransformer().forEach((transformer, classes) -> {
				if (!classes.isEmpty()) {
					findings.add(OperationResponse.finding(
							"anti-decompile",
							"high",
							null,
							null,
							"Transformer " + transformer.getSimpleName() + " would modify " + classes.size() + " classes"));
				}
			});
			JsonArray failures = new JsonArray();
			result.getTransformerFailures().forEach((pathNode, byTransformer) ->
					byTransformer.forEach((type, err) -> failures.add(OperationResponse.failure(
							type.getSimpleName(), pathNode.getValue().getName(), err.getMessage()))));
			JsonObject data = new JsonObject();
			data.addProperty("summary", "Anti-decompile detection complete");
			data.addProperty("modifiedCount", result.getTransformedClasses().size());
			data.add("findings", findings);
			data.add("failures", failures);
			data.addProperty("elapsedMs", 0);
			data.addProperty("transformerCount", antiCrash.size());
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("detect anti-decompile failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Detect anti-decompile", e));
		}
	}

	private List<Class<? extends JvmClassTransformer>> resolveTransformers(String preset, JsonObject req) {
		JsonArray explicit = JsonUtil.getArray(req, "transformers");
		if (!explicit.isEmpty()) {
			List<Class<? extends JvmClassTransformer>> out = new ArrayList<>();
			var available = transformationManager.getJvmClassTransformers();
			for (var el : explicit) {
				String name = el.getAsString();
				available.stream()
						.filter(type -> type.getName().equals(name) || type.getSimpleName().equals(name))
						.findFirst()
						.ifPresent(out::add);
			}
			if (!out.isEmpty()) return out;
		}
		return switch (preset.toLowerCase(Locale.ROOT)) {
			case "generic_anticrasher", "anticrasher" -> presetAntiCrash();
			case "generic_optimize", "optimize" -> presetOptimize();
			case "generic_restoration", "restoration" -> presetRestoration();
			case "specific" -> List.of(DashOpaqueSeedFoldingTransformer.class);
			default -> presetGenericAll();
		};
	}

	private List<Class<? extends JvmClassTransformer>> presetAntiCrash() {
		return List.of(
				CycleClassRemovingTransformer.class,
				DuplicateAnnotationRemovingTransformer.class,
				LongAnnotationRemovingTransformer.class,
				LongExceptionRemovingTransformer.class,
				FrameRemovingTransformer.class,
				IllegalAnnotationRemovingTransformer.class,
				IllegalSignatureRemovingTransformer.class,
				IllegalVarargsRemovingTransformer.class,
				UnknownAttributeRemovingTransformer.class
		);
	}

	private List<Class<? extends JvmClassTransformer>> presetOptimize() {
		return List.of(
				CallResultInliningTransformer.class,
				DeadCodeRemovingTransformer.class,
				DuplicateCatchMergingTransformer.class,
				GotoInliningTransformer.class,
				OpaqueConstantFoldingTransformer.class,
				OpaquePredicateFoldingTransformer.class,
				RedundantTryCatchRemovingTransformer.class,
				StaticValueInliningTransformer.class,
				VariableFoldingTransformer.class,
				VariableTableNormalizingTransformer.class
		);
	}

	private List<Class<? extends JvmClassTransformer>> presetRestoration() {
		return List.of(
				EnumNameRestorationTransformer.class,
				KotlinNameRestorationTransformer.class,
				SourceNameRestorationTransformer.class
		);
	}

	private List<Class<? extends JvmClassTransformer>> presetGenericAll() {
		List<Class<? extends JvmClassTransformer>> all = new ArrayList<>();
		all.addAll(presetAntiCrash());
		all.addAll(presetOptimize());
		all.addAll(presetRestoration());
		all.add(DashOpaqueSeedFoldingTransformer.class);
		return all;
	}

	private String categorize(Class<? extends JvmClassTransformer> transformer) {
		if (presetAntiCrash().contains(transformer)) return "generic/anticrasher";
		if (presetOptimize().contains(transformer)) return "generic/optimize";
		if (presetRestoration().contains(transformer)) return "generic/restoration";
		if (DashOpaqueSeedFoldingTransformer.class.equals(transformer)) return "specific";
		return "third-party";
	}

	private static class FilteringFeedback implements TransformationFeedback {
		private final String classFilter;

		private FilteringFeedback(String classFilter) {
			this.classFilter = classFilter;
		}

		@Override
		public boolean shouldTransform(Workspace workspace, software.coley.recaf.workspace.model.resource.WorkspaceResource resource,
									   software.coley.recaf.workspace.model.bundle.ClassBundle<?> bundle, software.coley.recaf.info.ClassInfo cls,
									   software.coley.recaf.services.transform.ClassTransformer transformer, int pass) {
			return classFilter == null || cls.getName().equals(classFilter);
		}
	}

	// ==================== Simplification Transforms ====================

	/**
	 * Constant folding: if an arithmetic instruction's operands are both constants,
	 * replace with the computed result.
	 */
	private int foldConstants(String owner, MethodNode method) {
		int changes = 0;
		if (method.instructions == null || method.instructions.size() == 0) return 0;

		AbstractInsnNode[] insns = method.instructions.toArray();
		for (AbstractInsnNode insn : insns) {
			int opcode = insn.getOpcode();
			if (!isIntArithmetic(opcode)) continue;

			// Need two constant predecessors on the stack
			AbstractInsnNode prev1 = findPreviousReal(insn);
			if (prev1 == null) continue;
			AbstractInsnNode prev2 = findPreviousReal(prev1);
			if (prev2 == null) continue;

			Integer val1 = getConstantIntValue(prev2); // first pushed = deeper on stack
			Integer val2 = getConstantIntValue(prev1); // second pushed = top of stack
			if (val1 == null || val2 == null) continue;

			Integer result = computeIntArithmetic(opcode, val1, val2);
			if (result == null) continue;

			// Replace: remove prev2, prev1, insn; insert constant
			AbstractInsnNode replacement = createIntConstant(result);
			method.instructions.insert(insn, replacement);
			method.instructions.remove(prev2);
			method.instructions.remove(prev1);
			method.instructions.remove(insn);
			changes++;
		}
		return changes;
	}

	/**
	 * Dead branch elimination: if a conditional jump's operand is a constant,
	 * replace with GOTO (always true) or remove (always false).
	 */
	private int eliminateDeadBranches(String owner, MethodNode method) {
		int changes = 0;
		if (method.instructions == null || method.instructions.size() == 0) return 0;

		AbstractInsnNode[] insns = method.instructions.toArray();
		for (AbstractInsnNode insn : insns) {
			if (!(insn instanceof JumpInsnNode jump)) continue;
			int opcode = jump.getOpcode();

			// Handle IF_ICMPxx (two int operands)
			if (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE) {
				AbstractInsnNode prev1 = findPreviousReal(insn);
				if (prev1 == null) continue;
				AbstractInsnNode prev2 = findPreviousReal(prev1);
				if (prev2 == null) continue;

				Integer val1 = getConstantIntValue(prev2);
				Integer val2 = getConstantIntValue(prev1);
				if (val1 == null || val2 == null) continue;

				boolean branchTaken = evaluateIntComparison(opcode, val1, val2);
				method.instructions.remove(prev2);
				method.instructions.remove(prev1);
				if (branchTaken) {
					// Replace conditional with GOTO
					method.instructions.set(insn, new JumpInsnNode(Opcodes.GOTO, jump.label));
				} else {
					// Branch never taken — remove the jump
					method.instructions.set(insn, new InsnNode(Opcodes.NOP));
				}
				changes++;
				continue;
			}

			// Handle IFxx (single int operand compared to 0)
			if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE) {
				AbstractInsnNode prev = findPreviousReal(insn);
				if (prev == null) continue;

				Integer val = getConstantIntValue(prev);
				if (val == null) continue;

				boolean branchTaken = evaluateIntZeroComparison(opcode, val);
				method.instructions.remove(prev);
				if (branchTaken) {
					method.instructions.set(insn, new JumpInsnNode(Opcodes.GOTO, jump.label));
				} else {
					method.instructions.set(insn, new InsnNode(Opcodes.NOP));
				}
				changes++;
			}
		}
		return changes;
	}

	/**
	 * Goto chain combining: if a GOTO targets another GOTO, redirect to the final target.
	 */
	private int combineGotoChains(MethodNode method) {
		int changes = 0;
		if (method.instructions == null) return 0;

		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (!(insn instanceof JumpInsnNode jump)) continue;
			if (jump.getOpcode() != Opcodes.GOTO) continue;

			LabelNode target = jump.label;
			Set<LabelNode> visited = new HashSet<>();
			visited.add(target);

			// Follow the chain
			LabelNode finalTarget = target;
			AbstractInsnNode next = findNextReal(target);
			while (next instanceof JumpInsnNode nextJump && nextJump.getOpcode() == Opcodes.GOTO) {
				if (!visited.add(nextJump.label)) break; // cycle detection
				finalTarget = nextJump.label;
				next = findNextReal(finalTarget);
			}

			if (finalTarget != target) {
				jump.label = finalTarget;
				changes++;
			}
		}
		return changes;
	}

	/**
	 * Remove all NOP instructions.
	 */
	private int removeNops(MethodNode method) {
		int changes = 0;
		if (method.instructions == null) return 0;

		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn.getOpcode() == Opcodes.NOP) {
				method.instructions.remove(insn);
				changes++;
			}
		}
		return changes;
	}

	/**
	 * Remove unreachable code: instructions where the analyzer frame is null
	 * (excluding labels, frames, and line numbers which are pseudo-instructions).
	 */
	private int removeUnreachableCode(String owner, MethodNode method) {
		int changes = 0;
		if (method.instructions == null || method.instructions.size() == 0) return 0;

		try {
			Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
			Frame<BasicValue>[] frames = analyzer.analyze(owner, method);

			AbstractInsnNode[] insns = method.instructions.toArray();
			for (int i = 0; i < insns.length; i++) {
				AbstractInsnNode insn = insns[i];
				// Skip pseudo-instructions
				if (insn.getType() == AbstractInsnNode.LABEL ||
						insn.getType() == AbstractInsnNode.FRAME ||
						insn.getType() == AbstractInsnNode.LINE) {
					continue;
				}
				if (frames[i] == null) {
					method.instructions.remove(insn);
					changes++;
				}
			}
		} catch (AnalyzerException e) {
			// Analysis failed — skip this transform, don't crash
			logger.debug("Unreachable code analysis failed for {}.{}{}: {}",
					owner, method.name, method.desc, e.getMessage());
		}
		return changes;
	}

	// ==================== ASM Helpers ====================

	private boolean isIntArithmetic(int opcode) {
		return opcode == Opcodes.IADD || opcode == Opcodes.ISUB ||
				opcode == Opcodes.IMUL || opcode == Opcodes.IDIV ||
				opcode == Opcodes.IREM || opcode == Opcodes.IAND ||
				opcode == Opcodes.IOR || opcode == Opcodes.IXOR ||
				opcode == Opcodes.ISHL || opcode == Opcodes.ISHR ||
				opcode == Opcodes.IUSHR;
	}

	private Integer computeIntArithmetic(int opcode, int val1, int val2) {
		return switch (opcode) {
			case Opcodes.IADD -> val1 + val2;
			case Opcodes.ISUB -> val1 - val2;
			case Opcodes.IMUL -> val1 * val2;
			case Opcodes.IDIV -> val2 != 0 ? val1 / val2 : null;
			case Opcodes.IREM -> val2 != 0 ? val1 % val2 : null;
			case Opcodes.IAND -> val1 & val2;
			case Opcodes.IOR -> val1 | val2;
			case Opcodes.IXOR -> val1 ^ val2;
			case Opcodes.ISHL -> val1 << val2;
			case Opcodes.ISHR -> val1 >> val2;
			case Opcodes.IUSHR -> val1 >>> val2;
			default -> null;
		};
	}

	private boolean evaluateIntComparison(int opcode, int val1, int val2) {
		return switch (opcode) {
			case Opcodes.IF_ICMPEQ -> val1 == val2;
			case Opcodes.IF_ICMPNE -> val1 != val2;
			case Opcodes.IF_ICMPLT -> val1 < val2;
			case Opcodes.IF_ICMPGE -> val1 >= val2;
			case Opcodes.IF_ICMPGT -> val1 > val2;
			case Opcodes.IF_ICMPLE -> val1 <= val2;
			default -> false;
		};
	}

	private boolean evaluateIntZeroComparison(int opcode, int val) {
		return switch (opcode) {
			case Opcodes.IFEQ -> val == 0;
			case Opcodes.IFNE -> val != 0;
			case Opcodes.IFLT -> val < 0;
			case Opcodes.IFGE -> val >= 0;
			case Opcodes.IFGT -> val > 0;
			case Opcodes.IFLE -> val <= 0;
			default -> false;
		};
	}

	/**
	 * Get the constant int value from an instruction, or null if not a constant.
	 */
	private Integer getConstantIntValue(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
			return opcode - Opcodes.ICONST_0;
		}
		if (insn instanceof IntInsnNode intInsn) {
			if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
				return intInsn.operand;
			}
		}
		if (insn instanceof LdcInsnNode ldc) {
			if (ldc.cst instanceof Integer i) return i;
		}
		return null;
	}

	/**
	 * Create an instruction that pushes the given int constant.
	 */
	private AbstractInsnNode createIntConstant(int value) {
		if (value >= -1 && value <= 5) {
			return new InsnNode(Opcodes.ICONST_0 + value);
		} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			return new IntInsnNode(Opcodes.BIPUSH, value);
		} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			return new IntInsnNode(Opcodes.SIPUSH, value);
		} else {
			return new LdcInsnNode(value);
		}
	}

	/**
	 * Find the previous "real" instruction (skip labels, frames, line numbers).
	 */
	private AbstractInsnNode findPreviousReal(AbstractInsnNode insn) {
		AbstractInsnNode prev = insn.getPrevious();
		while (prev != null && (prev.getType() == AbstractInsnNode.LABEL ||
				prev.getType() == AbstractInsnNode.FRAME ||
				prev.getType() == AbstractInsnNode.LINE)) {
			prev = prev.getPrevious();
		}
		return prev;
	}

	/**
	 * Find the next "real" instruction after a label (skip labels, frames, line numbers).
	 */
	private AbstractInsnNode findNextReal(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && (next.getType() == AbstractInsnNode.LABEL ||
				next.getType() == AbstractInsnNode.FRAME ||
				next.getType() == AbstractInsnNode.LINE)) {
			next = next.getNext();
		}
		return next;
	}

	// ==================== Eval Argument Parsing ====================

	private Object parseArgValue(JsonObject argObj) {
		String type = argObj.has("type") ? argObj.get("type").getAsString() : "null";
		if ("null".equals(type) || !argObj.has("value") || argObj.get("value").isJsonNull()) {
			return null;
		}
		return switch (type.toLowerCase()) {
			case "string" -> argObj.get("value").getAsString();
			case "int", "integer" -> argObj.get("value").getAsInt();
			case "long" -> argObj.get("value").getAsLong();
			case "float" -> argObj.get("value").getAsFloat();
			case "double" -> argObj.get("value").getAsDouble();
			case "boolean" -> argObj.get("value").getAsBoolean();
			case "byte" -> argObj.get("value").getAsByte();
			case "short" -> argObj.get("value").getAsShort();
			case "char" -> {
				String s = argObj.get("value").getAsString();
				yield s.isEmpty() ? '\0' : s.charAt(0);
			}
			default -> null;
		};
	}

	private Class<?> asmTypeToClass(Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN -> boolean.class;
			case Type.BYTE -> byte.class;
			case Type.CHAR -> char.class;
			case Type.SHORT -> short.class;
			case Type.INT -> int.class;
			case Type.LONG -> long.class;
			case Type.FLOAT -> float.class;
			case Type.DOUBLE -> double.class;
			case Type.OBJECT -> {
				String cn = type.getClassName();
				yield switch (cn) {
					case "java.lang.String" -> String.class;
					case "java.lang.Object" -> Object.class;
					case "java.lang.Integer" -> Integer.class;
					case "java.lang.Long" -> Long.class;
					case "java.lang.Float" -> Float.class;
					case "java.lang.Double" -> Double.class;
					case "java.lang.Boolean" -> Boolean.class;
					case "java.lang.Byte" -> Byte.class;
					case "java.lang.Short" -> Short.class;
					case "java.lang.Character" -> Character.class;
					default -> Object.class;
				};
			}
			case Type.ARRAY -> Object.class; // simplified — arrays passed as Object
			default -> Object.class;
		};
	}

	private Object defaultForType(Class<?> type) {
		if (type == boolean.class) return false;
		if (type == byte.class) return (byte) 0;
		if (type == char.class) return '\0';
		if (type == short.class) return (short) 0;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0.0f;
		if (type == double.class) return 0.0;
		return null;
	}

	// ==================== Sandboxed ClassLoader ====================

	/**
	 * ClassLoader that loads classes from the workspace's JvmClassBundle.
	 * Parent is the platform class loader (java.* access only, no plugin/Recaf classes).
	 */
	private static class WorkspaceClassLoader extends ClassLoader {
		private final JvmClassBundle bundle;

		WorkspaceClassLoader(JvmClassBundle bundle) {
			super(ClassLoader.getPlatformClassLoader());
			this.bundle = bundle;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			String internalName = name.replace('.', '/');
			JvmClassInfo classInfo = bundle.get(internalName);
			if (classInfo == null) {
				throw new ClassNotFoundException("Class not found in workspace: " + name);
			}
			byte[] bytecode = classInfo.getBytecode();
			return defineClass(name, bytecode, 0, bytecode.length);
		}
	}
}
