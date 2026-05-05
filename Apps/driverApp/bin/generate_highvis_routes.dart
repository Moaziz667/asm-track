import 'dart:convert';
import 'dart:io';

void main() async {
  final apiKey = 'AQ.Ab8RN6LKw6kldibK3sFDvexuu7mrlVgEVLwI5JLqe8AUBLrMJw';
  final url = 'https://stitch.googleapis.com/mcp';
  final projectId = '5275567949322848145';

  final client = HttpClient();
  try {
    print('Generating Routes Screen...');
    final payload = {
      'jsonrpc': '2.0',
      'id': 7,
      'method': 'tools/call',
      'params': {
        'name': 'generate_screen_from_text',
        'arguments': {
          'projectId': projectId,
          'deviceType': 'MOBILE',
          'prompt': 'A high-visibility routes dashboard for drivers. Absolute black background. Dark map with neon yellow #DFFF00 route line. Stop list with high-contrast cards. Technical HUD style. Status badges in neon yellow. Use Space Grotesk for headers.'
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
