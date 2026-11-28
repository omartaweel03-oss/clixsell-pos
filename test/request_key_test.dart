import 'package:clixsell_pos/services/cheque_api_client.dart';
import 'package:clixsell_pos/services/shift_api_client.dart';
import 'package:flutter_test/flutter_test.dart';

/// Idempotency keys for cash movements, cash refunds and cheque refunds must match the
/// backend's pattern [A-Za-z0-9_-]{8,100} and must not repeat. They are built from random
/// bytes only, so the same code works in the web build (no 32-bit integer bounds).
void main() {
  final backendPattern = RegExp(r'^[A-Za-z0-9_-]{8,100}$');

  test(
    'cash movement / refund keys match the backend pattern and are unique',
    () {
      final keys = {
        for (var i = 0; i < 2000; i++) ShiftApiClient.newRequestKey(),
      };
      expect(keys.length, 2000);
      for (final k in keys) {
        expect(backendPattern.hasMatch(k), isTrue, reason: k);
        expect(k, startsWith('mv-'));
      }
    },
  );

  test('cheque refund keys match the backend pattern and are unique', () {
    final keys = {
      for (var i = 0; i < 2000; i++) ChequeApiClient.newRequestKey(),
    };
    expect(keys.length, 2000);
    for (final k in keys) {
      expect(backendPattern.hasMatch(k), isTrue, reason: k);
      expect(k, startsWith('chq-'));
    }
  });
}
