import 'dart:async';

import 'package:shared_preferences/shared_preferences.dart';

/// Runs before every test file: an empty, in-memory store for the saved
/// language (Settings → Language), so no test depends on another.
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  SharedPreferences.setMockInitialValues({});
  await testMain();
}
