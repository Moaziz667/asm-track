import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'api_client.dart';

// Web-only import via conditional
import 'pdf_service_web.dart' if (dart.library.io) 'pdf_service_io.dart';

class PdfService {
  PdfService(this._client);

  final ApiClient _client;

  /// Downloads a PDF from [path] (relative API path) and opens it.
  /// On mobile: saves to temp dir and opens with system viewer.
  /// On web: triggers browser download.
  Future<bool> downloadAndOpen(String path, {String fileName = 'document.pdf'}) async {
    try {
      final response = await _client.dio.get<Uint8List>(
        path,
        options: Options(responseType: ResponseType.bytes),
      );
      final bytes = response.data;
      if (bytes == null || bytes.isEmpty) return false;

      return openPdfBytes(bytes, fileName);
    } catch (e) {
      debugPrint('[PDF] Download error: $e');
      return false;
    }
  }
}
