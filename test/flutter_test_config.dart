import 'dart:async';

import 'package:clixsell_pos/widgets/app_sidebar.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Runs before every test file: an empty, in-memory store for the saved
/// language (Settings → Language), so no test depends on another.
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  SharedPreferences.setMockInitialValues({});
  // flutter_test reports Android as the platform; the AI camera page (Android
  // only) is off unless a test turns it on, so other tests see the normal
  // desktop pages and never open the real camera plugin.
  debugAiCameraAvailable = false;
  await testMain();
}
