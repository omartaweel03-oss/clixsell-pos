import 'dart:convert';

import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/state/language_preference.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support/fake_product_backend.dart';

/// Settings screen – report Figure 3.9 (UR-M12 / SR25).
/// Shini: Milk ₪10, stock 20. Users: sysadmin, shini.admin, shini.cashier.
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(
    name: 'Milk',
    barcode: '11111111',
    price: 10,
    quantity: 20,
    minStock: 2,
  );

Future<void> _open(
  WidgetTester tester,
  FakeProductBackend backend, {
  String user = 'shini.admin',
  String password = 'Admin-Pass-1',
  Size size = const Size(1440, 1000),
  Locale? locale,
  bool go = true,
}) async {
  tester.view.physicalSize = size;
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
  if (go) await _nav(tester, 'settings');
}

Future<void> _nav(WidgetTester tester, String page) async {
  await tester.ensureVisible(find.byKey(Key('nav-$page')));
  await tester.tap(find.byKey(Key('nav-$page')));
  await tester.pumpAndSettle();
}

Future<void> _tap(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

Finder _in(String key, String text) =>
    find.descendant(of: find.byKey(Key(key)), matching: find.text(text));

const _cards = [
  'printer',
  'taxes',
  'users',
  'profile',
  'language',
  'general',
  'aiMobile',
  'backup',
];

Future<int> _status(
  FakeProductBackend b,
  String method,
  String path,
  String user,
  String password,
  Map<String, dynamic> body,
) async {
  final login = await b.client.post(
    Uri.parse('${FakeProductBackend.baseUrl}/auth/login'),
    headers: {'Content-Type': 'application/json'},
    body: jsonEncode({'username': user, 'password': password}),
  );
  final token = (jsonDecode(login.body) as Map<String, dynamic>)['token'];
  final r = await b.client.put(
    Uri.parse('${FakeProductBackend.baseUrl}$path'),
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer $token',
    },
    body: jsonEncode(body),
  );
  return r.statusCode;
}

void main() {
  testWidgets(
    'Store Admin: Settings is the last menu item; the 8 cards of Figure 3.9',
    (tester) async {
      await _open(tester, _backend());
      expect(find.byKey(const Key('settingsPage')), findsOneWidget);
      expect(find.text('Manage system settings'), findsOneWidget);
      expect(find.byKey(const Key('settingsDate')), findsOneWidget);

      // Figure order: Printer, Taxes, Users, Profile / Language, General, AI Mobile, Backup.
      final centers = [
        for (final id in _cards)
          tester.getCenter(find.byKey(Key('settings-$id'))),
      ];
      for (var i = 1; i < 4; i++) {
        expect(centers[i].dx, greaterThan(centers[i - 1].dx));
        expect(centers[i].dy, centers[0].dy);
        expect(centers[i + 4].dx, greaterThan(centers[i + 3].dx));
      }
      expect(centers[4].dy, greaterThan(centers[0].dy));
      // Titles line up in each row (cards with a "Not available yet" label too).
      double titleY(String id, String t) =>
          tester.getCenter(_in('settings-$id', t)).dy;
      expect(titleY('printer', 'Printer'), titleY('taxes', 'Taxes'));
      expect(titleY('aiMobile', 'AI Mobile'), titleY('general', 'General'));
      expect(titleY('backup', 'Backup'), titleY('language', 'Language'));
      for (final t in [
        'Printer',
        'Taxes',
        'Users',
        'Profile',
        'Language',
        'General',
        'AI Mobile',
        'Backup',
      ]) {
        expect(_in('settingsCards', t), findsOneWidget);
      }

      // Not available features: clearly labelled, nothing opens.
      expect(_in('settings-printer', 'Not available yet'), findsOneWidget);
      expect(_in('settings-aiMobile', 'Not available yet'), findsOneWidget);
      expect(_in('settings-backup', 'System Admin only'), findsOneWidget);
      for (final id in ['printer', 'aiMobile', 'backup']) {
        await _tap(tester, 'settings-$id');
        expect(find.byType(AlertDialog), findsNothing);
      }
      // Settings is the last item of the sidebar.
      final settingsY = tester
          .getCenter(find.byKey(const Key('nav-settings')))
          .dy;
      for (final other in ['pos', 'products', 'reports', 'cashiers']) {
        expect(
          tester.getCenter(find.byKey(Key('nav-$other'))).dy,
          lessThan(settingsY),
        );
      }
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Cashier: no Settings in the menu, and the backend refuses its settings APIs',
    (tester) async {
      final backend = _backend();
      await _open(
        tester,
        backend,
        user: 'shini.cashier',
        password: 'Cashier-Pass-1',
        go: false,
      );
      expect(find.byKey(const Key('nav-settings')), findsNothing);
      expect(find.byKey(const Key('settingsPage')), findsNothing);
      // The general language button stays.
      expect(find.byKey(const Key('languageButton')), findsOneWidget);

      expect(
        await _status(
          backend,
          'PUT',
          '/auth/me/password',
          'shini.cashier',
          'Cashier-Pass-1',
          {
            'currentPassword': 'Cashier-Pass-1',
            'newPassword': 'Cashier-New-Pass-2',
          },
        ),
        403,
      );
      expect(backend.passwordOf('shini.cashier'), 'Cashier-Pass-1');
      expect(
        await _status(
          backend,
          'PUT',
          '/store/settings',
          'shini.cashier',
          'Cashier-Pass-1',
          {'taxRate': 0, 'maxDiscountPercent': 0},
        ),
        403,
      );
      expect(backend.stores.first['taxRate'], isNull); // unchanged (16 %)
    },
  );

  testWidgets('Store Admin: Users opens the existing Cashiers page', (
    tester,
  ) async {
    await _open(tester, _backend());
    await _tap(tester, 'settings-users');
    expect(find.text('Cashiers'), findsWidgets);
    expect(find.text('Ahmad Saleh'), findsOneWidget);
  });

  testWidgets(
    'Taxes: validation, save to the own store, the POS uses the new rate',
    (tester) async {
      final backend = _backend();
      final neshan = backend.addStore('Neshan Market');
      await _open(tester, backend);
      await _tap(tester, 'settings-taxes');
      expect(find.byKey(const Key('taxesDialog')), findsOneWidget);
      expect(find.text('16'), findsOneWidget);
      expect(find.text('10'), findsOneWidget);
      expect(
        find.textContaining('Completed invoices keep their own tax'),
        findsOneWidget,
      );

      await tester.enterText(find.byKey(const Key('settingsTaxRate')), '120');
      await _tap(tester, 'settingsSaveButton');
      expect(find.text('Enter 0 – 100'), findsOneWidget);
      expect(backend.requests, isNot(contains('PUT /api/store/settings')));

      await tester.enterText(find.byKey(const Key('settingsTaxRate')), '10');
      await tester.enterText(
        find.byKey(const Key('settingsMaxDiscount')),
        '20',
      );
      await _tap(tester, 'settingsSaveButton');
      expect(find.byKey(const Key('taxesDialog')), findsNothing);
      expect(find.text('Tax and discount settings saved'), findsOneWidget);
      final shini = backend.stores.first;
      expect(shini['taxRate'], 10.0);
      expect(shini['maxDiscountPercent'], 20.0);
      expect(shini['address'], 'Ramallah'); // other values unchanged
      expect(
        backend.stores.firstWhere((s) => s['storeId'] == neshan)['taxRate'],
        isNull,
      );

      await _nav(tester, 'pos');
      await _tap(tester, 'posProduct-1');
      expect(find.text('Tax (10%)'), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(const Key('summaryTotal'))).data,
        '₪ 11.00',
      );
    },
  );

  testWidgets('a server error is shown in the Taxes dialog', (tester) async {
    final backend = _backend();
    await _open(tester, backend);
    await _tap(tester, 'settings-taxes');
    backend.endAllSessions(); // logged out meanwhile
    await tester.enterText(find.byKey(const Key('settingsTaxRate')), '12');
    await _tap(tester, 'settingsSaveButton');
    // 401 → back to the login screen; nothing saved.
    expect(find.byKey(const Key('loginButton')), findsOneWidget);
    expect(find.byKey(const Key('taxesDialog')), findsNothing);
    expect(backend.stores.first['taxRate'], isNull);
  });

  testWidgets('Taxes and General send only their own values (no lost update)', (
    tester,
  ) async {
    final backend = _backend();
    await _open(tester, backend);
    // General is opened; meanwhile the tax is changed in another tab.
    await _tap(tester, 'settings-general');
    backend.stores.first['taxRate'] = 9.0;
    await tester.enterText(
      find.byKey(const Key('settingsPhone')),
      '+970 2 295 1234',
    );
    await _tap(tester, 'settingsSaveButton');
    expect(
      backend.lastStoreSettingsBody!.keys,
      unorderedEquals(['address', 'phone']),
    );
    expect(backend.stores.first['taxRate'], 9.0); // not undone
    expect(backend.stores.first['phone'], '+970 2 295 1234');

    // Taxes is opened; meanwhile the phone is changed elsewhere.
    await _tap(tester, 'settings-taxes');
    backend.stores.first['phone'] = '0599 000 000';
    await tester.enterText(find.byKey(const Key('settingsTaxRate')), '11');
    await _tap(tester, 'settingsSaveButton');
    expect(
      backend.lastStoreSettingsBody!.keys,
      unorderedEquals(['taxRate', 'maxDiscountPercent']),
    );
    expect(backend.stores.first['taxRate'], 11.0);
    expect(backend.stores.first['phone'], '0599 000 000'); // not undone
  });

  testWidgets('General: store name read-only, address and phone saved', (
    tester,
  ) async {
    final backend = _backend();
    await _open(tester, backend);
    await _tap(tester, 'settings-general');
    final name = tester.widget<TextField>(
      find.descendant(
        of: find.byKey(const Key('settingsStoreName')),
        matching: find.byType(TextField),
      ),
    );
    expect(name.enabled, isFalse);
    expect(
      find.text('The store name is changed by the System Admin.'),
      findsOneWidget,
    );

    await tester.enterText(find.byKey(const Key('settingsPhone')), 'call me');
    await _tap(tester, 'settingsSaveButton');
    expect(find.byKey(const Key('generalDialog')), findsOneWidget);
    expect(backend.stores.first['phone'], isNull);

    await tester.enterText(
      find.byKey(const Key('settingsAddress')),
      'Al-Irsal St, Ramallah',
    );
    await tester.enterText(
      find.byKey(const Key('settingsPhone')),
      '+970 2 295 1234',
    );
    await _tap(tester, 'settingsSaveButton');
    expect(find.text('Store information saved'), findsOneWidget);
    expect(backend.stores.first['address'], 'Al-Irsal St, Ramallah');
    expect(backend.stores.first['phone'], '+970 2 295 1234');
    expect(backend.stores.first['name'], 'Shini Supermarket');
    expect(
      backend.stores.first['taxRate'],
      isNull,
    ); // not sent: unchanged (16 %)
  });

  testWidgets(
    'Profile: details, password checks, wrong current password keeps the login',
    (tester) async {
      final backend = _backend();
      backend.apiClient(as: 'shini.admin'); // a second session elsewhere
      await _open(tester, backend);
      expect(backend.sessionsOf('shini.admin'), 2);
      await _tap(tester, 'settings-profile');
      expect(_in('profileDialog', 'Shini Manager'), findsOneWidget);
      expect(_in('profileDialog', 'shini.admin'), findsOneWidget);
      expect(_in('profileDialog', 'Store Admin'), findsOneWidget);
      expect(_in('profileDialog', 'Shini Supermarket'), findsOneWidget);

      // Local checks.
      await _tap(tester, 'changePasswordButton');
      expect(find.text('Required'), findsNWidgets(2));
      await tester.enterText(
        find.byKey(const Key('currentPassword')),
        'Admin-Pass-1',
      );
      await tester.enterText(find.byKey(const Key('newPassword')), 'short');
      await tester.enterText(find.byKey(const Key('confirmPassword')), 'other');
      await _tap(tester, 'changePasswordButton');
      expect(find.text('At least 8 characters'), findsOneWidget);
      expect(find.text('The passwords do not match'), findsOneWidget);

      // Wrong current password: message from the server, still logged in.
      await tester.enterText(
        find.byKey(const Key('currentPassword')),
        'Wrong-Pass-9',
      );
      await tester.enterText(
        find.byKey(const Key('newPassword')),
        'New-Admin-Pass-2',
      );
      await tester.enterText(
        find.byKey(const Key('confirmPassword')),
        'New-Admin-Pass-2',
      );
      await _tap(tester, 'changePasswordButton');
      expect(find.text('Current password is incorrect'), findsOneWidget);
      expect(find.byKey(const Key('profileDialog')), findsOneWidget);
      expect(backend.passwordOf('shini.admin'), 'Admin-Pass-1');

      await tester.enterText(
        find.byKey(const Key('currentPassword')),
        'Admin-Pass-1',
      );
      await _tap(tester, 'changePasswordButton');
      expect(find.byKey(const Key('profileDialog')), findsNothing);
      expect(
        find.text('Password changed. Your other sessions were logged out.'),
        findsOneWidget,
      );
      expect(backend.passwordOf('shini.admin'), 'New-Admin-Pass-2');
      expect(backend.sessionsOf('shini.admin'), 1); // only this one
      expect(find.byKey(const Key('settingsPage')), findsOneWidget);
    },
  );

  testWidgets('Language: choose Arabic, remembered after a restart', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    await _open(tester, _backend());
    await _tap(tester, 'settings-language');
    await _tap(tester, 'language-ar');
    expect(find.text('إدارة إعدادات النظام'), findsOneWidget);
    expect(
      Directionality.of(tester.element(find.byKey(const Key('settingsPage')))),
      TextDirection.rtl,
    );
    expect(await LanguagePreference.load(), const Locale('ar'));

    // The sidebar button also changes and remembers it.
    await _tap(tester, 'languageButton');
    expect(find.text('Manage system settings'), findsOneWidget);
    expect(await LanguagePreference.load(), const Locale('en'));
    await _tap(tester, 'languageButton');
    expect(await LanguagePreference.load(), const Locale('ar'));
  });

  testWidgets('Arabic: right-to-left, Printer on the right as in Figure 3.9', (
    tester,
  ) async {
    await _open(tester, _backend(), locale: const Locale('ar'));
    expect(find.text('الإعدادات'), findsWidgets);
    for (final t in [
      'الطابعة',
      'الضرائب',
      'المستخدمين',
      'الملف الشخصي',
      'اللغة',
      'عام',
      'AI Mobile',
      'النسخ الاحتياطي',
    ]) {
      expect(_in('settingsCards', t), findsOneWidget);
    }
    final printer = tester.getCenter(find.byKey(const Key('settings-printer')));
    final profile = tester.getCenter(find.byKey(const Key('settings-profile')));
    expect(printer.dx, greaterThan(profile.dx));
    expect(_in('settings-printer', 'غير متوفر حالياً'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'System Admin: Settings with Users/Taxes/General → Stores, and Backup',
    (tester) async {
      await _open(tester, _backend(), user: 'sysadmin');
      expect(find.byKey(const Key('settingsPage')), findsOneWidget);
      expect(_in('settings-taxes', 'Per store – in Stores'), findsOneWidget);
      expect(_in('settings-general', 'Per store – in Stores'), findsOneWidget);
      expect(find.byKey(const Key('settings-backup-note')), findsNothing);

      await _tap(tester, 'settings-taxes');
      expect(find.text('Shini Supermarket'), findsWidgets); // Stores page
      await _nav(tester, 'settings');
      await _tap(tester, 'settings-users');
      expect(find.text('Shini Supermarket'), findsWidgets);
      await _nav(tester, 'settings');

      await _tap(tester, 'settings-profile');
      expect(_in('profileDialog', 'System Admin'), findsOneWidget);
      expect(find.byKey(const Key('profileStore')), findsNothing);
    },
  );

  testWidgets('Backup: create, checked, listed; restore steps shown', (
    tester,
  ) async {
    final backend = _backend();
    await _open(tester, backend, user: 'sysadmin');
    await _tap(tester, 'settings-backup');
    expect(find.byKey(const Key('noBackups')), findsOneWidget);
    expect(find.text('/srv/clixsell/backend/backups'), findsOneWidget);
    expect(find.byKey(const Key('restoreSteps')), findsOneWidget);

    await _tap(tester, 'createBackupButton');
    expect(find.byKey(const Key('backupCreated')), findsOneWidget);
    expect(
      find.textContaining('created and checked (integrity: ok)'),
      findsOneWidget,
    );
    expect(
      find.textContaining('It holds 1 stores, 1 products'),
      findsOneWidget,
    );
    expect(find.text('clixsell-backup-20261116-100001.db'), findsOneWidget);
    expect(find.text('240 KB'), findsOneWidget);
    expect(backend.backups, hasLength(1));
    expect(tester.takeException(), isNull);
  });

  testWidgets('the POS reads a changed tax again when it opens', (
    tester,
  ) async {
    final backend = _backend();
    await _open(
      tester,
      backend,
      user: 'shini.cashier',
      password: 'Cashier-Pass-1',
      go: false,
    );
    await _tap(tester, 'posProduct-1');
    expect(find.text('Tax (16%)'), findsOneWidget);
    backend.stores.first['taxRate'] =
        5.0; // changed by the Store Admin meanwhile
    await _nav(tester, 'suspendedInvoices');
    await _nav(tester, 'pos');
    expect(find.text('Tax (5%)'), findsOneWidget);
    expect(
      tester.widget<Text>(find.byKey(const Key('summaryTotal'))).data,
      '₪ 10.50',
    );
  });

  for (final size in const [
    Size(1440, 1000),
    Size(1024, 768),
    Size(390, 844),
  ]) {
    for (final user in const ['shini.admin', 'sysadmin']) {
      testWidgets('no overflow: $user at $size', (tester) async {
        await _open(tester, _backend(), user: user, size: size);
        expect(tester.takeException(), isNull);
        for (final id in _cards) {
          expect(find.byKey(Key('settings-$id')), findsOneWidget);
        }
        for (final id in ['profile', 'language']) {
          await _tap(tester, 'settings-$id');
          expect(tester.takeException(), isNull);
          await tester.tapAt(const Offset(5, 5)); // close
          await tester.pumpAndSettle();
        }
      });
    }
  }
}
