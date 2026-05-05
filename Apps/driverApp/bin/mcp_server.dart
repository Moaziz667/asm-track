import 'package:mcp_dart/mcp_dart.dart';

void main() async {
  // 1. Initialize the server with basic implementation details and capabilities
  final McpServer server = McpServer(
    const Implementation(
      name: 'asm-driver-mcp',
      version: '1.0.0',
    ),
    options: const McpServerOptions(
      capabilities: ServerCapabilities(
        resources: ServerCapabilitiesResources(),
        tools: ServerCapabilitiesTools(),
        prompts: ServerCapabilitiesPrompts(),
      ),
    ),
  );

  // 2. Register tools to help the AI understand the Driver App
  server.registerTool(
    'get_app_info',
    description: 'Get metadata about the ASM Driver Flutter application',
    callback: (args, extra) async {
      return CallToolResult(
        content: [
          TextContent(
            text: 'App Name: asmDrive\nPackage: driver_app\nVersion: 1.0.0+1\nSDK: ^3.8.1',
          ),
        ],
      );
    },
  );

  server.registerTool(
    'list_features',
    description: 'List the functional features of the driver app',
    callback: (args, extra) async {
      return CallToolResult(
        content: [
          TextContent(
            text: 'Available Features: auth, deliveries, home, pod, profile, routes',
          ),
        ],
      );
    },
  );

  // 3. Start the server using Stdio transport
  // This allows the server to communicate with an MCP client (like Claude Desktop)
  await server.connect(StdioServerTransport());
}
