class AppConfig {
  const AppConfig({required this.apiBaseUrl, required this.discoveryUrl});

  factory AppConfig.fromEnvironment() {
    // PROD: pass API_BASE_URL at build time:
    //   flutter build apk --dart-define=API_BASE_URL=https://api.yourdomain.com
    //   flutter build ios --dart-define=API_BASE_URL=https://api.yourdomain.com
    // Without this the app will connect to localhost and fail on a real device
    const baseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://10.86.194.125');
    const discovery = String.fromEnvironment('DISCOVERY_URL', defaultValue: 'http://10.86.194.125:8080/clients.json');
    return AppConfig(apiBaseUrl: baseUrl, discoveryUrl: discovery);
  }

  factory AppConfig.fromStorage(String apiBaseUrl) {
    const discovery = String.fromEnvironment('DISCOVERY_URL', defaultValue: 'http://10.86.194.125:8080/clients.json');
    return AppConfig(apiBaseUrl: apiBaseUrl, discoveryUrl: discovery);
  }

  final String apiBaseUrl;
  final String discoveryUrl;
}
