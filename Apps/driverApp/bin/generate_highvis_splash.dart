import 'dart:convert';
import 'dart:io';

void main() async {
  final apiKey = 'AQ.Ab8RN6LKw6kldibK3sFDvexuu7mrlVgEVLwI5JLqe8AUBLrMJw';
  final url = 'https://stitch.googleapis.com/mcp';
  final projectId = '5275567949322848145';

  final client = HttpClient();
  try {
    print('Generating Splash Screen...');
    final payload = {
      'jsonrpc': '2.0',
      'id': 5,
      'method': 'tools/call',
      'params': {
        'name': 'generate_screen_from_text',
        'arguments': {
          'projectId': projectId,
          'deviceType': 'MOBILE',
          'prompt': 'A high-visibility splash screen for ASMONE High-Vis Driver app. Black background, large neon yellow #DFFF00 icon in center. Technical typography saying ASMONE. 24/7 Industrial feel.'
        }
      }
    };

    final request = await client.postUrl(Uri.parse(url));
    request.headers.add('X-Goog-Api-Key', apiKey);
    request.headers.add('Content-Type', 'application/json');
    request.write(jsonEncode(payload));
    final response = await request.close();
    final body = await response.transform(utf8.decoder).join();
    
    print('Response: $body');
  } finally {
    client.close();
  }
}
