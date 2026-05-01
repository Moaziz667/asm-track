import 'dart:convert';
import 'dart:io';

void main() async {
  final apiKey = 'AQ.Ab8RN6LKw6kldibK3sFDvexuu7mrlVgEVLwI5JLqe8AUBLrMJw';
  final url = 'https://stitch.googleapis.com/mcp';

  final client = HttpClient();
  try {
    // 1. Create Project
    print('Creating project...');
    final createProjectPayload = {
      'jsonrpc': '2.0',
      'id': 3,
      'method': 'tools/call',
      'params': {
        'name': 'create_project',
        'arguments': {
          'title': 'ASMONE High-Vis Driver'
        }
      }
    };

    final request = await client.postUrl(Uri.parse(url));
    request.headers.add('X-Goog-Api-Key', apiKey);
    request.headers.add('Content-Type', 'application/json');
    request.write(jsonEncode(createProjectPayload));
    final response = await request.close();
    final body = await response.transform(utf8.decoder).join();
    final data = jsonDecode(body);
    
    print('Response: $body');
    
    if (data['result'] != null && data['result']['content'] != null) {
        // The project name (ID) is usually in the response
        print('Project Created Successfully');
    }
  } finally {
    client.close();
  }
}
