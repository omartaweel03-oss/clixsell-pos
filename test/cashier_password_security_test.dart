import 'package:clixsell_pos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Cashier account security, Store Admin "Cashiers" screen: Cashiers that
/// still use a published demo password are marked (only for the Store Admin
/// of the same store), the existing "Reset password" fixes them, a demo
/// password is refused as the new one, and nothing changes in the demo
/// profile (the fake backend's default, as in the E2E runs).
FakeProductBackend _realInstallation() {
  final b = FakeProductBackend()..demoProfile = false;
  // a second cashier with a demo password, and a safe one, in Shini (store 1)
  b.addUser('shini.lina', 'Cashier-Pass-1', 'CASHIER', 1, fullName: 'Lina');
  b.addUser('shini.omar', 'Omar-Till-Pass-3', 'CASHIER', 1, fullName: 'Omar');
  // another store with its own demo-password cashier
  final neshan = b.addStore('Neshan Market', address: 'Nablus');
  b.addUser('neshan.admin', 'Neshan-Owner-Pass-1', 'STORE_ADMIN', neshan);
  b.addUser('neshan.cashier', 'Cashier-Pass-1', 'CASHIER', neshan);
  return b;
}

Future<void> _login(
  WidgetTester tester,
  FakeProductBackend backend,
  String user,
  String password, {
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
}

Future<void> _openCashiers(WidgetTester tester) async {
  await tester.ensureVisible(find.byKey(const Key('nav-cashiers')));
  await tester.tap(find.byKey(const Key('nav-cashiers')));
  await tester.pumpAndSettle();
}

int _id(FakeProductBackend b, String username) =>
    b.userByName(username)!['userId'] as int;

Future<void> _reset(WidgetTester tester, int userId, String password) async {
  await tester.ensureVisible(find.byKey(Key('reset-$userId')));
  await tester.tap(find.byKey(Key('reset-$userId')));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('resetPasswordField')), password);
  await tester.tap(find.byKey(const Key('resetPasswordSave')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'English: the affected cashiers are marked; a demo reset is refused; '
    'a secure reset clears the warning',
    (tester) async {
      final b = _realInstallation();
      await _login(tester, b, 'shini.admin', 'Admin-Pass-1');
      await _openCashiers(tester);

      expect(find.byKey(const Key('cashierDemoWarning')), findsOneWidget);
      expect(
        find.text('2 cashiers use a publicly known demo password'),
        findsOneWidget,
      );
      final cashier = _id(b, 'shini.cashier');
      final lina = _id(b, 'shini.lina');
      final omar = _id(b, 'shini.omar');
      expect(find.byKey(Key('demoPassword-$cashier')), findsOneWidget);
      expect(find.byKey(Key('demoPassword-$lina')), findsOneWidget);
      expect(find.byKey(Key('demoPassword-$omar')), findsNothing);
      expect(find.text('Demo password'), findsNWidgets(2));
      // the other store's cashier is never shown or marked
      expect(find.text('neshan.cashier'), findsNothing);
      // nothing was changed by showing the warning
      expect(b.passwordOf('shini.cashier'), 'Cashier-Pass-1');

      await _reset(tester, cashier, 'cashier-pass-1');
      expect(
        find.text(
          'This is a publicly known demo password. Choose your own password.',
        ),
        findsOneWidget,
      );
      expect(b.passwordOf('shini.cashier'), 'Cashier-Pass-1');
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();

      await _reset(tester, cashier, 'Till-Cashier-Pass-4');
      expect(b.passwordOf('shini.cashier'), 'Till-Cashier-Pass-4');
      expect(find.byKey(Key('demoPassword-$cashier')), findsNothing);
      expect(
        find.text('1 cashier uses a publicly known demo password'),
        findsOneWidget,
      );

      await _reset(tester, lina, 'Lina-Till-Pass-5');
      expect(find.byKey(const Key('cashierDemoWarning')), findsNothing);
      expect(find.text('Demo password'), findsNothing);
    },
  );

  testWidgets('Arabic: the warning and the marker are in Arabic', (
    tester,
  ) async {
    final b = _realInstallation();
    await _login(
      tester,
      b,
      'shini.admin',
      'Admin-Pass-1',
      locale: const Locale('ar'),
    );
    await _openCashiers(tester);
    expect(
      find.text(
        'عدد الكاشيرات الذين يستخدمون كلمة مرور تجريبية معروفة للجميع: 2',
      ),
      findsOneWidget,
    );
    expect(find.textContaining('إعادة تعيين كلمة المرور'), findsWidgets);
    expect(find.text('كلمة مرور تجريبية'), findsNWidgets(2));
    // no symbol outside the bundled fonts in the new texts
    final texts = tester
        .widgetList<Text>(
          find.descendant(
            of: find.byKey(const Key('cashierDemoWarning')),
            matching: find.byType(Text),
          ),
        )
        .map((t) => t.data ?? '');
    for (final t in texts) {
      expect(t.runes.where((r) => r >= 0x2190 && r <= 0x2BFF), isEmpty);
    }
  });

  testWidgets('a new cashier with a demo password is refused, a real one '
      'is created', (tester) async {
    final b = _realInstallation();
    await _login(tester, b, 'shini.admin', 'Admin-Pass-1');
    await _openCashiers(tester);
    await tester.tap(find.byKey(const Key('addAccountButton')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('accountFullName')), 'Sami');
    await tester.enterText(
      find.byKey(const Key('accountUsername')),
      'shini.sami',
    );
    await tester.enterText(
      find.byKey(const Key('accountPassword')),
      'Cashier-Pass-1',
    );
    await tester.tap(find.byKey(const Key('accountSaveButton')));
    await tester.pumpAndSettle();
    expect(
      find.text(
        'This is a publicly known demo password. Choose your own password.',
      ),
      findsOneWidget,
    );
    expect(b.userByName('shini.sami'), isNull);
    await tester.enterText(
      find.byKey(const Key('accountPassword')),
      'Sami-Till-Pass-6',
    );
    await tester.tap(find.byKey(const Key('accountSaveButton')));
    await tester.pumpAndSettle();
    expect(b.passwordOf('shini.sami'), 'Sami-Till-Pass-6');
  });

  testWidgets('no warning when every cashier is secure', (tester) async {
    final b = FakeProductBackend()..demoProfile = false;
    b.addUser('shini.omar', 'Omar-Till-Pass-3', 'CASHIER', 1);
    await _login(tester, b, 'shini.admin', 'Admin-Pass-1');
    await _openCashiers(tester);
    await _reset(tester, _id(b, 'shini.cashier'), 'Till-Cashier-Pass-4');
    expect(find.byKey(const Key('cashierDemoWarning')), findsNothing);
    expect(find.text('Demo password'), findsNothing);
  });

  testWidgets('demo profile: the intended demo cashiers are not flagged', (
    tester,
  ) async {
    final b = FakeProductBackend(); // demoProfile = true
    await _login(tester, b, 'shini.admin', 'Admin-Pass-1');
    await _openCashiers(tester);
    expect(b.requests, contains('GET /api/store/cashiers/security-status'));
    expect(find.byKey(const Key('cashierDemoWarning')), findsNothing);
    expect(find.text('Demo password'), findsNothing);
  });

  testWidgets('the System Admin Store Admins screen never asks', (
    tester,
  ) async {
    final b = _realInstallation();
    await _login(tester, b, 'sysadmin', 'Admin-Pass-1');
    await tester.tap(find.byKey(const Key('storeAdmins-1')));
    await tester.pumpAndSettle();
    expect(find.text('shini.admin'), findsOneWidget);
    expect(find.byKey(const Key('cashierDemoWarning')), findsNothing);
    expect(b.requests.where((r) => r.contains('cashiers')), isEmpty);
  });

  testWidgets('a cashier cannot reach the Cashiers screen or the status', (
    tester,
  ) async {
    final c = _realInstallation();
    await _login(tester, c, 'shini.cashier', 'Cashier-Pass-1');
    expect(find.byKey(const Key('nav-cashiers')), findsNothing);
    expect(c.requests.where((r) => r.contains('security-status')), isEmpty);
  });

  testWidgets('each Store Admin sees only their own store', (tester) async {
    final b = _realInstallation();
    await _login(tester, b, 'neshan.admin', 'Neshan-Owner-Pass-1');
    await _openCashiers(tester);
    expect(
      find.text('1 cashier uses a publicly known demo password'),
      findsOneWidget,
    );
    expect(
      find.byKey(Key('demoPassword-${_id(b, 'neshan.cashier')}')),
      findsOneWidget,
    );
    expect(find.text('shini.cashier'), findsNothing);
    expect(find.text('shini.lina'), findsNothing);
  });
}
