import 'dart:convert';
import 'dart:io';

void main() async {
  final apiKey = 'AQ.Ab8RN6LKw6kldibK3sFDvexuu7mrlVgEVLwI5JLqe8AUBLrMJw';
  final url = 'https://stitch.googleapis.com/mcp';

  final client = HttpClient();
  try {
    final payload = {
      'jsonrpc': '2.0',
      'id': 8,
      'method': 'tools/call',
      'params': {
        'name': 'list_projects',
        'arguments': {}
      }
    };

    final request = await client.postUrl(Uri.parse(url));
    request.headers.add('X-Goog-Api-Key', apiKey);
    request.headers.add('Content-Type', 'application/json');
    request.write(jsonEncode(payload));
    final response = await request.close();
    final body = await response.transform(utf8.decoder).join();
    
    final data = jsonDecode(body);
    final structured = data['result']['structuredContent'] as Map<String, dynamic>? ?? {};
    print('Structured keys: ${structured.keys.toList()}');
    final projects = structured['projects'] as List<dynamic>? ?? [];
    bool found = false;
    for (var project in projects) {
      if (project['title'] != null && project['title'].contains('ASMONE High-Vis')) {
        print('Project Found: ${project['title']}');
        print('ID: ${project['name']}');
        print('Theme: ${jsonEncode(project['designTheme'])}');
        found = true;
      }
    }
    if (!found) {
        print('ASMONE High-Vis project not found.');
    }
  } finally {
    client.close();
  }
}
