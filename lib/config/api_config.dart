/// Where the Flutter app finds the ClixSell backend API.
///
/// Default: the Spring Boot backend running on this computer.
/// To use another address, run for example:
///   flutter run -d chrome --dart-define=API_BASE_URL=http://192.168.1.20:8080/api
class ApiConfig {
  ApiConfig._();

  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080/api',
  );

  /// How long to wait for the server before giving up.
  static const Duration timeout = Duration(seconds: 15);
}
