import 'dart:convert';
import 'dart:io';

void main() async {
  final apiKey = 'AQ.Ab8RN6LKw6kldibK3sFDvexuu7mrlVgEVLwI5JLqe8AUBLrMJw';
  final url = 'https://stitch.googleapis.com/mcp';
  final projectId = '5275567949322848145';

  final client = HttpClient();
  try {
    print('Creating design system...');
    final payload = {
      'jsonrpc': '2.0',
      'id': 4,
      'method': 'tools/call',
      'params': {
        'name': 'create_design_system',
        'arguments': {
          'projectId': projectId,
          'designSystem': {
            'displayName': 'ASMONE High-Vis',
            'theme': {
              'colorMode': 'DARK',
              'headlineFont': 'SPACE_GROTESK',
              'bodyFont': 'INTER',
              'roundness': 'ROUND_FOUR',
              'customColor': '#DFFF00',
              'designMd': '# ASMONE High-Vis Driver\n\n- **Theme**: Tactical Industrial / High-Visibility.\n- **Contrast**: Absolute Black (#000000) backgrounds with Neon Yellow (#DFFF00) accents.\n- **Typography**: Space Grotesk for technical headers, Inter for readability.\n- **Shapes**: Sharp 4px corners (Round 4) for a rugged ERP feel.\n- **Vibe**: 24/7 Operational HUD.'
            }
          }
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
