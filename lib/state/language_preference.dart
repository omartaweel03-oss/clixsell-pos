import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Remembers the chosen language on this device (Settings → Language and the
/// sidebar button), so it is still chosen after a refresh or restart. Only the
/// language is saved here: no business data.
class LanguagePreference {
  static const _key = 'clixsell.language';

  /// The saved language, or null when none was chosen yet.
  static Future<Locale?> load() async {
    try {
      final code = (await SharedPreferences.getInstance()).getString(_key);
      return code == 'ar' || code == 'en' ? Locale(code!) : null;
    } catch (_) {
      return null; // storage not available: the default language is used
    }
  }

  static Future<void> save(Locale locale) async {
    try {
      await (await SharedPreferences.getInstance()).setString(
        _key,
        locale.languageCode,
      );
    } catch (_) {
      // Not saved: the language still changes for this visit.
    }
  }
}
