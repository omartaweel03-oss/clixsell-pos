import '../models/app_user.dart';
import 'api_http.dart';

/// Result of a successful login.
class LoginResult {
  const LoginResult(this.token, this.user);

  final String token;
  final AppUser user;
}

/// POST /api/auth/login, POST /api/auth/logout.
class AuthApiClient {
  AuthApiClient(this._http);

  final ApiHttp _http;

  Future<LoginResult> login(String username, String password) async {
    final body =
        await _http.post('/auth/login', {
              'username': username.trim(),
              'password': password,
            })
            as Map<String, dynamic>;
    return LoginResult(
      body['token'] as String,
      AppUser.fromJson(body['user'] as Map<String, dynamic>),
    );
  }

  /// Ends the current session on the server.
  Future<void> logout() => _http.post('/auth/logout');
}
