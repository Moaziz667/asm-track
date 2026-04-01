class AppConfig {
  const AppConfig({required this.apiBaseUrl});

  factory AppConfig.fromEnvironment() {
    const baseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:80');
    return AppConfig(apiBaseUrl: baseUrl);
  }

  final String apiBaseUrl;
}
