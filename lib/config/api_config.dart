import 'package:flutter/foundation.dart';

/// Where the Flutter app finds the ClixSell backend API.
///
/// * `--dart-define=API_BASE_URL=...` always wins, e.g. a physical Android
///   phone on the store's network:
///     `flutter run -d PHONE --dart-define=API_BASE_URL=http://PC-IP:8080/api`
///   (plain HTTP works only in Android debug builds; release builds need HTTPS).
/// * Otherwise, on Android: `http://10.0.2.2:8080/api` — the Android emulator's
///   documented alias for the computer it runs on (not a network address).
/// * Otherwise (desktop / web): the backend on this computer, localhost.
class ApiConfig {
  ApiConfig._();

  static const String _fromBuild = String.fromEnvironment('API_BASE_URL');

  static String get baseUrl {
    if (_fromBuild.isNotEmpty) return _fromBuild;
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
      return 'http://10.0.2.2:8080/api';
    }
    return 'http://localhost:8080/api';
  }

  /// How long to wait for the server before giving up.
  static const Duration timeout = Duration(seconds: 15);
}
