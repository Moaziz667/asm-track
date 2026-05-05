import 'dart:convert';
import 'dart:io';

void main() async {
  final apiKey = 'AQ.Ab8RN6LKw6kldibK3sFDvexuu7mrlVgEVLwI5JLqe8AUBLrMJw';
  final url = 'https://stitch.googleapis.com/mcp';

  final client = HttpClient();
  try {
    final request = await client.postUrl(Uri.parse(url));
    request.headers.add('X-Goog-Api-Key', apiKey);
    request.headers.add('Content-Type', 'application/json');

    final payload = {
      'jsonrpc': '2.0',
      'id': 2,
      'method': 'tools/list',
      'params': {}
    };

    request.write(jsonEncode(payload));
    final response = await request.close();
    final body = await response.transform(utf8.decoder).join();
    
    final data = jsonDecode(body);
    final tools = data['result']['tools'] as List<dynamic>;
    for (var tool in tools) {
      if (tool['name'] == 'generate_screen_from_text') {
        print('Tool: ${tool['name']}');
        print('Full Schema: ${jsonPrettyPrint(tool['inputSchema'])}');
      }
    }
  } finally {
    client.close();
  }
}

String jsonPrettyPrint(dynamic json) {
  return const JsonEncoder.withIndent('  ').convert(json);
}
