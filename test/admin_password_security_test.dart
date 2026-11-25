import 'package:clixsell_pos/l10n/app_strings.dart';
import 'package:clixsell_pos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Final security hardening, Settings: a System Admin that still uses a
/// published demo password sees a clear warning (English / Arabic) with a way
/// to change it; nobody else sees it; a demo password is refused as the new
/// password with a message in the UI language. In the demo profile (the fake
/// backend's default, as in the E2E runs) nothing changes.
FakeProductBackend _realInstallation() =>
    FakeProductBackend()..demoProfile = false;

Future<void> _openSettings(
  WidgetTester tester,
  FakeProductBackend backend, {
  String user = 'sysadmin',
  String password = 'Admin-Pass-1',
  Locale? locale,
}) async {
  tester.view.physicalSize = const Size(1440, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ClixSellApp(http: backend.newHttp(), initialLocale: locale),
  );
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('loginUsername')), user);
  await tester.enterText(find.byKey(const Key('loginPassword')), password);
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
  await tester.ensureVisible(find.byKey(const Key('nav-settings')));
  await tester.tap(find.byKey(const Key('nav-settings')));
  await tester.pumpAndSettle();
}

Future<void> _changePassword(
  WidgetTester tester,
  String current,
  String next,
) async {
  await tester.enterText(find.byKey(const Key('currentPassword')), current);
  await tester.enterText(find.byKey(const Key('newPassword')), next);
  await tester.enterText(find.byKey(const Key('confirmPassword')), next);
  await tester.tap(find.byKey(const Key('changePasswordButton')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'English: a legacy System Admin is warned, a demo replacement is refused, '
    'a real change removes the warning',
    (tester) async {
      final backend = _realInstallation();
      await _openSettings(tester, backend);
      final warning = find.byKey(const Key('demoPasswordWarning'));
      expect(warning, findsOneWidget);
      expect(
        find.text('Security warning: you are using the demo password'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Settings > Profile > Change password'),
        findsOneWidget,
      );
      // Above the cards, so it is seen first.
      expect(
        tester.getTopLeft(warning).dy,
        lessThan(
          tester.getTopLeft(find.byKey(const Key('settings-printer'))).dy,
        ),
      );
      expect(backend.passwordOf('sysadmin'), 'Admin-Pass-1'); // nothing changed

      await tester.tap(find.byKey(const Key('demoPasswordChange')));
      await tester.pumpAndSettle();
      await _changePassword(tester, 'Admin-Pass-1', 'Admin12345');
      expect(
        find.text(
          'This is a publicly known demo password. Choose your own password.',
        ),
        findsOneWidget,
      );
      expect(backend.passwordOf('sysadmin'), 'Admin-Pass-1');

      await _changePassword(tester, 'Admin-Pass-1', 'Owner-Chosen-Pass-42');
      expect(backend.passwordOf('sysadmin'), 'Owner-Chosen-Pass-42');
      expect(find.byKey(const Key('demoPasswordWarning')), findsNothing);
    },
  );

  testWidgets('Arabic: the warning and the refusal are in Arabic', (
    tester,
  ) async {
    final backend = _realInstallation();
    await _openSettings(tester, backend, locale: const Locale('ar'));
    expect(
      find.text('تحذير أمني: أنت تستخدم كلمة المرور التجريبية'),
      findsOneWidget,
    );
    expect(
      find.textContaining('الإعدادات > الملف الشخصي > تغيير كلمة المرور'),
      findsOneWidget,
    );
    expect(find.text('غيّر كلمة المرور الآن'), findsOneWidget);
    await tester.tap(find.byKey(const Key('demoPasswordChange')));
    await tester.pumpAndSettle();
    await _changePassword(tester, 'Admin-Pass-1', 'ADMIN-PASS-2');
    expect(
      find.text(
        'هذه كلمة مرور تجريبية منشورة ومعروفة للجميع. اختر كلمة مرور خاصة بك.',
      ),
      findsOneWidget,
    );
    expect(backend.passwordOf('sysadmin'), 'Admin-Pass-1');
  });

  testWidgets(
    'a Store Admin with a demo password gets their own warning and can fix it',
    (tester) async {
      final backend = _realInstallation(); // shini.admin / Admin-Pass-1
      await _openSettings(tester, backend, user: 'shini.admin');
      expect(find.byKey(const Key('demoPasswordWarning')), findsOneWidget);
      expect(
        find.textContaining('Your Store Admin account still uses'),
        findsOneWidget,
      );
      expect(find.textContaining('System Admin account'), findsNothing);
      expect(backend.requests, contains('GET /api/auth/me/security-status'));
      await tester.tap(find.byKey(const Key('demoPasswordChange')));
      await tester.pumpAndSettle();
      await _changePassword(tester, 'Admin-Pass-1', 'cashier-pass-1');
      expect(backend.passwordOf('shini.admin'), 'Admin-Pass-1');
      await _changePassword(tester, 'Admin-Pass-1', 'Shini-Owner-Pass-5');
      expect(backend.passwordOf('shini.admin'), 'Shini-Owner-Pass-5');
      expect(find.byKey(const Key('demoPasswordWarning')), findsNothing);
    },
  );

  testWidgets('Arabic: the Store Admin warning is in Arabic', (tester) async {
    final backend = _realInstallation();
    await _openSettings(
      tester,
      backend,
      user: 'shini.admin',
      locale: const Locale('ar'),
    );
    expect(
      find.text('تحذير أمني: أنت تستخدم كلمة المرور التجريبية'),
      findsOneWidget,
    );
    expect(find.textContaining('حساب مدير المتجر ما زال'), findsOneWidget);
  });

  testWidgets('a Cashier never asks for the security status', (tester) async {
    final backend = _realInstallation();
    tester.view.physicalSize = const Size(1440, 1000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ClixSellApp(http: backend.newHttp()));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('loginUsername')),
      'shini.cashier',
    );
    await tester.enterText(
      find.byKey(const Key('loginPassword')),
      backend.passwordOf('shini.cashier')!,
    );
    await tester.tap(find.byKey(const Key('loginButton')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('nav-settings')), findsNothing);
    expect(
      backend.requests.where((r) => r.contains('security-status')),
      isEmpty,
    );
  });

  testWidgets(
    'creating a Store Admin with a demo password is refused, a real one works',
    (tester) async {
      final backend = _realInstallation();
      tester.view.physicalSize = const Size(1440, 1000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ClixSellApp(http: backend.newHttp()));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('loginUsername')),
        'sysadmin',
      );
      await tester.enterText(
        find.byKey(const Key('loginPassword')),
        'Admin-Pass-1',
      );
      await tester.tap(find.byKey(const Key('loginButton')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('storeAdmins-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('addAccountButton')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('accountFullName')),
        'Second Owner',
      );
      await tester.enterText(
        find.byKey(const Key('accountUsername')),
        'shini.owner2',
      );
      await tester.enterText(
        find.byKey(const Key('accountPassword')),
        'Admin-Pass-1',
      );
      await tester.tap(find.byKey(const Key('accountSaveButton')));
      await tester.pumpAndSettle();
      expect(
        find.text(
          'This is a publicly known demo password. Choose your own password.',
        ),
        findsOneWidget,
      );
      expect(backend.userByName('shini.owner2'), isNull);
      await tester.enterText(
        find.byKey(const Key('accountPassword')),
        'Owner-Two-Pass-9',
      );
      await tester.tap(find.byKey(const Key('accountSaveButton')));
      await tester.pumpAndSettle();
      expect(backend.userByName('shini.owner2')!['role'], 'STORE_ADMIN');
      expect(backend.passwordOf('shini.owner2'), 'Owner-Two-Pass-9');
    },
  );

  testWidgets('demo profile: the intended demo password shows no warning', (
    tester,
  ) async {
    final backend = FakeProductBackend(); // demoProfile = true
    await _openSettings(tester, backend);
    expect(backend.requests, contains('GET /api/auth/me/security-status'));
    expect(find.byKey(const Key('demoPasswordWarning')), findsNothing);
  });

  testWidgets('a secure System Admin sees no warning', (tester) async {
    final backend = _realInstallation();
    await _openSettings(tester, backend);
    await tester.tap(find.byKey(const Key('demoPasswordChange')));
    await tester.pumpAndSettle();
    await _changePassword(tester, 'Admin-Pass-1', 'Owner-Chosen-Pass-42');
    // a fresh visit of Settings asks again: still no warning
    await tester.tap(find.byKey(const Key('nav-stores')).hitTestable().first);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('nav-settings')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('settingsPage')), findsOneWidget);
    expect(find.byKey(const Key('demoPasswordWarning')), findsNothing);
  });

  testWidgets('server refusal text is shown in the UI language', (
    tester,
  ) async {
    late AppStrings en;
    late AppStrings ar;
    for (final lang in ['en', 'ar']) {
      await tester.pumpWidget(
        MaterialApp(
          locale: Locale(lang),
          supportedLocales: const [Locale('en'), Locale('ar')],
          localizationsDelegates: GlobalMaterialLocalizations.delegates,
          home: Builder(
            builder: (context) {
              if (lang == 'en') {
                en = AppStrings.of(context);
              } else {
                ar = AppStrings.of(context);
              }
              return const SizedBox();
            },
          ),
        ),
      );
    }
    const server = AppStrings.demoPasswordServerMessage;
    expect(server, FakeProductBackend.demoPasswordMessage);
    expect(en.passwordServerError(server), en.passwordIsDemo);
    expect(ar.passwordServerError(server), ar.passwordIsDemo);
    expect(
      ar.passwordServerError('Password is too long'),
      'Password is too long',
    );
    // No arrow or symbol outside the bundled fonts (they would need the internet).
    for (final t in [en, ar]) {
      for (final text in [
        t.demoPasswordWarningTitle,
        t.demoPasswordWarningBody,
        t.demoPasswordWarningAction,
        t.passwordIsDemo,
      ]) {
        expect(text.runes.where((r) => r >= 0x2190 && r <= 0x2BFF), isEmpty);
      }
    }
  });
}
