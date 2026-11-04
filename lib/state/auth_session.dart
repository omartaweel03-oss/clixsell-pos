import 'package:flutter/widgets.dart';

import '../models/app_user.dart';
import '../services/api_http.dart';
import '../services/auth_api_client.dart';

/// Who is logged in.
///
/// The token is kept only in memory: refreshing the browser or restarting
/// the app means logging in again (decision: no persistent login).
class AuthSession extends ChangeNotifier {
  AuthSession({required this.http}) : _auth = AuthApiClient(http) {
    http.onUnauthorized = _sessionEnded;
  }

  final ApiHttp http;
  final AuthApiClient _auth;

  AppUser? _user;
  bool _sessionExpired = false;

  AppUser? get user => _user;
  bool get isLoggedIn => _user != null;

  /// True when the server ended the session (expired or deactivated), so
  /// the Login screen can explain why the user is back there.
  bool get sessionExpired => _sessionExpired;

  /// Throws [ApiException] when the login is refused.
  Future<void> login(String username, String password) async {
    final result = await _auth.login(username, password);
    http.token = result.token;
    _user = result.user;
    _sessionExpired = false;
    notifyListeners();
  }

  Future<void> logout() async {
    try {
      await _auth.logout();
    } on ApiException {
      // Logged out locally even if the server cannot be reached.
    }
    _clear();
    notifyListeners();
  }

  void _sessionEnded() {
    if (_user == null) return;
    _clear();
    _sessionExpired = true;
    notifyListeners();
  }

  void _clear() {
    http.token = null;
    _user = null;
  }
}

/// Makes the [AuthSession] available to the widget tree.
class AuthSessionScope extends InheritedNotifier<AuthSession> {
  const AuthSessionScope({
    super.key,
    required AuthSession session,
    required super.child,
  }) : super(notifier: session);

  static AuthSession of(BuildContext context) {
    final scope = context
        .dependOnInheritedWidgetOfExactType<AuthSessionScope>();
    assert(scope != null, 'AuthSessionScope not found in widget tree');
    return scope!.notifier!;
  }

  static AuthSession read(BuildContext context) {
    final scope = context.getInheritedWidgetOfExactType<AuthSessionScope>();
    assert(scope != null, 'AuthSessionScope not found in widget tree');
    return scope!.notifier!;
  }
}
