import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Presses Complete Sale the way a cashier does. For a cash sale the cash
/// dialog opens (store upgrade, feature 3): the cashier taps "Exact amount"
/// (the first quick amount, always the total) and Complete payment.
Future<void> completeSaleLikeACashier(WidgetTester tester) async {
  final button = find.byKey(const Key('completeSaleButton'));
  await tester.ensureVisible(button);
  await tester.tap(button);
  await tester.pumpAndSettle();
  if (find.byKey(const Key('cashDialog')).evaluate().isEmpty) return;
  final exact = find.byWidgetPredicate(
    (w) =>
        w.key is ValueKey<String> &&
        (w.key! as ValueKey<String>).value.startsWith('cashQuick-'),
  );
  await tester.tap(exact.first);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('cashConfirm')));
  await tester.pumpAndSettle();
}
