import 'package:flutter/widgets.dart';

/// POS checkout texts (store upgrade: cash received / change, fixed footer).
class CheckoutStrings {
  const CheckoutStrings._(this.isArabic);

  final bool isArabic;

  static CheckoutStrings of(BuildContext context) =>
      CheckoutStrings._(Localizations.localeOf(context).languageCode == 'ar');

  String _t(String en, String ar) => isArabic ? ar : en;

  String get cashTitle => _t('Cash payment', 'الدفع نقداً');
  String get totalDue => _t('Total due', 'المطلوب');
  String get amountReceived => _t('Amount received', 'المبلغ المستلم');
  String get amountHint =>
      _t('How much did the customer give you?', 'كم أعطاك الزبون؟');
  String get exactAmount => _t('Exact amount', 'المبلغ بالضبط');
  String get changeDue => _t('Change due', 'الباقي للزبون');
  String get remaining => _t('Still to pay', 'المتبقي');
  String get invalidAmount => _t(
    'Enter an amount with at most 2 decimals.',
    'أدخل مبلغاً بخانتين عشريتين على الأكثر.',
  );
  String get notEnough => _t(
    'Not enough: the sale cannot be completed yet.',
    'المبلغ غير كافٍ: لا يمكن إتمام البيع بعد.',
  );
  String get completePayment => _t('Complete payment', 'إتمام الدفع');
  String get cancel => _t('Cancel', 'إلغاء');
  String get payWith => _t('Pay with', 'الدفع بـ');
  String get received => _t('Cash received', 'النقد المستلم');
  String get change => _t('Change given', 'الباقي المُعاد');
}
