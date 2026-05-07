class AppConfig {
  const AppConfig({required this.apiBaseUrl});

  factory AppConfig.fromEnvironment() {
    // PROD: pass API_BASE_URL at build time:
    //   flutter build apk --dart-define=API_BASE_URL=https://api.yourdomain.com
    //   flutter build ios --dart-define=API_BASE_URL=https://api.yourdomain.com
    // Without this the app will connect to localhost and fail on a real device
    const baseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://192.168.1.7');
    return AppConfig(apiBaseUrl: baseUrl);
  }

  final String apiBaseUrl;
}
