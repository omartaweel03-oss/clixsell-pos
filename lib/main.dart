import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'screens/app_shell.dart';
import 'services/product_api_client.dart';
import 'state/product_store.dart';
import 'theme/app_theme.dart';

void main() {
  // Products come from the backend API (which stores them in the database).
  final store = ProductStore(api: ProductApiClient())..load();
  runApp(ClixSellApp(store: store));
}

/// Root widget of ClixSell POS.
///
/// Owns the [ProductStore] (backed by the API) and the current language. Switching to
/// Arabic changes the locale to `ar`, which makes Flutter lay out the whole
/// app right-to-left automatically.
class ClixSellApp extends StatefulWidget {
  const ClixSellApp({super.key, required this.store, this.initialLocale});

  final ProductStore store;
  final Locale? initialLocale;

  @override
  State<ClixSellApp> createState() => _ClixSellAppState();
}

class _ClixSellAppState extends State<ClixSellApp> {
  late Locale _locale = widget.initialLocale ?? const Locale('en');

  void _toggleLanguage() {
    setState(() {
      _locale = _locale.languageCode == 'ar'
          ? const Locale('en')
          : const Locale('ar');
    });
  }

  @override
  Widget build(BuildContext context) {
    return ProductStoreScope(
      store: widget.store,
      child: MaterialApp(
        title: 'ClixSell POS',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light(),
        locale: _locale,
        supportedLocales: const [Locale('en'), Locale('ar')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: AppShell(onToggleLanguage: _toggleLanguage),
      ),
    );
  }
}
