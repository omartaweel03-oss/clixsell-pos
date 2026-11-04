import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'models/app_user.dart';
import 'screens/app_shell.dart';
import 'screens/auth/login_page.dart';
import 'services/api_http.dart';
import 'services/product_api_client.dart';
import 'state/auth_session.dart';
import 'services/sales_api_client.dart';
import 'state/pos_cart.dart';
import 'state/product_store.dart';
import 'theme/app_theme.dart';

void main() {
  runApp(ClixSellApp(http: ApiHttp()));
}

/// Root widget of ClixSell POS.
///
/// Shows the Login screen until someone logs in, then the screens of their
/// role. Also owns the current language: switching to Arabic changes the
/// locale to `ar`, which makes Flutter lay out the whole app right-to-left.
class ClixSellApp extends StatefulWidget {
  const ClixSellApp({super.key, required this.http, this.initialLocale});

  /// Connection to the backend (tests pass one that talks to a fake server).
  final ApiHttp http;
  final Locale? initialLocale;

  @override
  State<ClixSellApp> createState() => _ClixSellAppState();
}

class _ClixSellAppState extends State<ClixSellApp> {
  late Locale _locale = widget.initialLocale ?? const Locale('en');
  late final AuthSession _session = AuthSession(http: widget.http);

  void _toggleLanguage() {
    setState(() {
      _locale = _locale.languageCode == 'ar'
          ? const Locale('en')
          : const Locale('ar');
    });
  }

  @override
  void dispose() {
    _session.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AuthSessionScope(
      session: _session,
      child: MaterialApp(
        title: 'ClixSell POS',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light(),
        locale: _locale,
        supportedLocales: const [Locale('en'), Locale('ar')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: _Home(onToggleLanguage: _toggleLanguage),
      ),
    );
  }
}

/// Login screen when logged out; the user's screens when logged in.
class _Home extends StatelessWidget {
  const _Home({required this.onToggleLanguage});

  final VoidCallback onToggleLanguage;

  @override
  Widget build(BuildContext context) {
    final session = AuthSessionScope.of(context);
    final user = session.user;
    if (user == null) {
      return LoginPage(onToggleLanguage: onToggleLanguage);
    }
    // A new key per login: nothing from a previous user's session survives.
    return _LoggedIn(
      key: ValueKey(session.http.token),
      user: user,
      session: session,
      onToggleLanguage: onToggleLanguage,
    );
  }
}

class _LoggedIn extends StatefulWidget {
  const _LoggedIn({
    super.key,
    required this.user,
    required this.session,
    required this.onToggleLanguage,
  });

  final AppUser user;
  final AuthSession session;
  final VoidCallback onToggleLanguage;

  @override
  State<_LoggedIn> createState() => _LoggedInState();
}

class _LoggedInState extends State<_LoggedIn> {
  /// Product data of the user's store (Store Admins and Cashiers only).
  ProductStore? _products;

  /// The cashier's current POS invoice; kept while switching pages.
  PosCart? _cart;

  @override
  void initState() {
    super.initState();
    if (widget.user.role.worksInStore) {
      // Products come from the backend API, which returns only this
      // user's store and stores them in the database.
      _products = ProductStore(api: ProductApiClient(widget.session.http))
        ..load();
      _cart = PosCart();
      _loadStoreSettings();
    }
  }

  /// Tax rate and maximum discount of the user's store, for the POS totals.
  Future<void> _loadStoreSettings() async {
    try {
      final store = await SalesApiClient(widget.session.http).storeSettings();
      _cart?.applyStoreSettings(
        taxRate: store.taxRate,
        maxDiscountPercent: store.maxDiscountPercent,
      );
    } on ApiException {
      // The defaults stay; the backend calculates the real totals anyway.
    }
  }

  @override
  void dispose() {
    _cart?.dispose();
    _products?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final shell = AppShell(
      user: widget.user,
      http: widget.session.http,
      onToggleLanguage: widget.onToggleLanguage,
      onLogout: widget.session.logout,
    );
    final products = _products;
    return products == null
        ? shell
        : ProductStoreScope(
            store: products,
            child: PosCartScope(cart: _cart!, child: shell),
          );
  }
}
