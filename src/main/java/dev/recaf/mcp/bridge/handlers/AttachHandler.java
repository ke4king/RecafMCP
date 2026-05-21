package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.tools.attach.VirtualMachineDescriptor;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.attach.AttachManager;
import software.coley.recaf.services.attach.JmxBeanServerConnection;
import software.coley.recaf.services.attach.NamedMBeanInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;

import javax.management.MBeanAttributeInfo;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

/**
 * Dynamic attach endpoints.
 */
public class AttachHandler {
	private static final Logger logger = Logging.get(AttachHandler.class);

	private final WorkspaceManager workspaceManager;
	private final AttachManager attachManager;

	public AttachHandler(WorkspaceManager workspaceManager, AttachManager attachManager) {
		this.workspaceManager = workspaceManager;
		this.attachManager = attachManager;
	}

	public void handleListVms(HttpExchange exchange) throws IOException {
		if (!attachManager.canAttach()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS,
					"AttachManager is unavailable in current runtime",
					"Run Recaf on a full JDK with attach permissions."));
			return;
		}
		attachManager.scan();
		List<VirtualMachineDescriptor> descriptors = attachManager.getVirtualMachineDescriptors();
		JsonArray array = new JsonArray();
		for (VirtualMachineDescriptor descriptor : descriptors) {
			JsonObject item = new JsonObject();
			item.addProperty("id", descriptor.id());
			item.addProperty("displayName", descriptor.displayName());
			item.addProperty("pid", attachManager.getVirtualMachinePid(descriptor));
			item.addProperty("mainClass", attachManager.getVirtualMachineMainClass(descriptor));
			Properties props = attachManager.getVirtualMachineProperties(descriptor);
			if (props != null) {
				item.addProperty("javaVersion", props.getProperty("java.version", ""));
				item.addProperty("javaVendor", props.getProperty("java.vendor", ""));
			}
			array.add(item);
		}
		JsonObject data = new JsonObject();
		data.addProperty("summary", "VM scan complete");
		data.addProperty("modifiedCount", 0);
		data.add("findings", new JsonArray());
		data.add("failures", new JsonArray());
		data.addProperty("elapsedMs", 0);
		data.add("vms", array);
		data.addProperty("count", array.size());
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	public void handleAttachVm(HttpExchange exchange) throws IOException {
		if (!attachManager.canAttach()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS,
					"AttachManager is unavailable in current runtime",
					"Run Recaf on a full JDK with attach permissions."));
			return;
		}
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		int pid = JsonUtil.getInt(req, "pid", -1);
		if (pid < 0) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("pid"));
			return;
		}

		attachManager.scan();
		VirtualMachineDescriptor descriptor = findByPid(pid);
		if (descriptor == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS, "VM not found for pid " + pid, "Use list_vms to inspect available processes."));
			return;
		}

		try {
			var remoteResource = attachManager.createRemoteResource(descriptor);
			var workspace = workspaceManager.getCurrent();
			if (workspace == null) {
				BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
				return;
			}
			workspace.addSupportingResource(remoteResource);

			JsonObject data = new JsonObject();
			data.addProperty("summary", "VM attached as supporting resource");
			data.addProperty("modifiedCount", 0);
			data.add("findings", new JsonArray());
			data.add("failures", new JsonArray());
			data.addProperty("elapsedMs", 0);
			data.addProperty("pid", pid);
			data.addProperty("displayName", descriptor.displayName());
			data.addProperty("supportingResources", workspace.getSupportingResources().size());
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("attach vm failed for pid={}", pid, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Attach VM", e));
		}
	}

	public void handleMbeans(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		int pid = JsonUtil.getInt(req, "pid", -1);
		if (pid < 0) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("pid"));
			return;
		}
		attachManager.scan();
		VirtualMachineDescriptor descriptor = findByPid(pid);
		if (descriptor == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS, "VM not found for pid " + pid, "Use list_vms to inspect available processes."));
			return;
		}
		try {
			JmxBeanServerConnection conn = attachManager.getJmxServerConnection(descriptor);
			JsonArray beans = new JsonArray();
			beans.add(beanToJson(conn, conn.getRuntimeBeanInfo()));
			beans.add(beanToJson(conn, conn.getOperatingSystemBeanInfo()));
			beans.add(beanToJson(conn, conn.getClassloadingBeanInfo()));
			beans.add(beanToJson(conn, conn.getCompilationBeanInfo()));
			beans.add(beanToJson(conn, conn.getThreadBeanInfo()));

			JsonObject data = new JsonObject();
			data.addProperty("summary", "MBeans fetched");
			data.addProperty("modifiedCount", 0);
			data.add("findings", new JsonArray());
			data.add("failures", new JsonArray());
			data.addProperty("elapsedMs", 0);
			data.addProperty("pid", pid);
			data.add("beans", beans);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("mbeans failed for pid={}", pid, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("VM mbeans", e));
		}
	}

	private JsonObject beanToJson(JmxBeanServerConnection conn, NamedMBeanInfo info) throws Exception {
		JsonObject out = new JsonObject();
		out.addProperty("objectName", info.getObjectName().toString());
		out.addProperty("className", info.getClassName());
		JsonArray attrs = new JsonArray();
		for (MBeanAttributeInfo attrInfo : info.getAttributes()) {
			JsonObject attr = new JsonObject();
			attr.addProperty("name", attrInfo.getName());
			attr.addProperty("type", attrInfo.getType());
			attr.addProperty("readable", attrInfo.isReadable());
			attr.addProperty("writable", attrInfo.isWritable());
			if (attrInfo.isReadable()) {
				try {
					Object value = info.getAttributeValue(conn, attrInfo);
					attr.addProperty("value", String.valueOf(value));
				} catch (Exception ignored) {
					attr.addProperty("value", "<unavailable>");
				}
			}
			attrs.add(attr);
		}
		out.add("attributes", attrs);
		return out;
	}

	private VirtualMachineDescriptor findByPid(int pid) {
		for (VirtualMachineDescriptor descriptor : attachManager.getVirtualMachineDescriptors()) {
			if (attachManager.getVirtualMachinePid(descriptor) == pid) return descriptor;
		}
		return null;
	}
}
