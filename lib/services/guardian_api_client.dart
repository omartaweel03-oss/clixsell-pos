import '../models/guardian.dart';
import 'api_http.dart';

/// Guardian Mode API (Store Admin only; the backend always uses the
/// logged-in user's own store and refuses Cashiers and the System Admin):
///
/// | Method     | HTTP                                                   |
/// |------------|--------------------------------------------------------|
/// | status     | GET /api/guardian/status                               |
/// | setEnabled | PUT /api/guardian/status {"enabled": true / false}     |
/// | dashboard  | GET /api/guardian/dashboard?year=&month=               |
/// | alerts     | GET /api/guardian/alerts?year=&month=[&severity=]      |
/// | activity   | GET /api/guardian/activity?year=&month=[&type=]&page=  |
class GuardianApiClient {
  GuardianApiClient(this._http);

  final ApiHttp _http;

  Future<GuardianStatus> status() async => GuardianStatus.fromJson(
    await _http.get('/guardian/status') as Map<String, dynamic>,
  );

  Future<GuardianStatus> setEnabled(bool enabled) async =>
      GuardianStatus.fromJson(
        await _http.put('/guardian/status', {'enabled': enabled})
            as Map<String, dynamic>,
      );

  Future<GuardianDashboard> dashboard(int year, int month) async =>
      GuardianDashboard.fromJson(
        await _http.get('/guardian/dashboard?year=$year&month=$month')
            as Map<String, dynamic>,
      );

  Future<List<GuardianAlert>> alerts(
    int year,
    int month, {
    String? severity,
  }) async {
    final q = severity == null ? '' : '&severity=$severity';
    final j =
        await _http.get('/guardian/alerts?year=$year&month=$month$q')
            as Map<String, dynamic>;
    return [
      for (final a in j['alerts'] as List<dynamic>? ?? const [])
        GuardianAlert.fromJson(a as Map<String, dynamic>),
    ];
  }

  Future<ActivityPage> activity(
    int year,
    int month, {
    String? type,
    int page = 0,
  }) async {
    final q = type == null ? '' : '&type=$type';
    return ActivityPage.fromJson(
      await _http.get('/guardian/activity?year=$year&month=$month$q&page=$page')
          as Map<String, dynamic>,
    );
  }
}
