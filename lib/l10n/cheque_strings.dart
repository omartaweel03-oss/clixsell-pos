import 'package:flutter/widgets.dart';

/// Cheque texts (feature 9).
class ChequeStrings {
  const ChequeStrings._(this.isArabic);

  final bool isArabic;

  static ChequeStrings of(BuildContext context) =>
      ChequeStrings._(Localizations.localeOf(context).languageCode == 'ar');

  static ChequeStrings forLanguage({required bool arabic}) => ChequeStrings._(arabic);

  String _t(String en, String ar) => isArabic ? ar : en;

  String get title => _t('Cheque payment', 'الدفع بشيك');
  String get number => _t('Cheque number', 'رقم الشيك');
  String get bank => _t('Bank', 'البنك');
  String get amount => _t('Amount', 'المبلغ');
  String get dueDate => _t('Due date', 'تاريخ الاستحقاق');
  String get chooseDate => _t('Choose the due date', 'اختر تاريخ الاستحقاق');
  String get notes => _t('Notes (optional)', 'ملاحظات (اختياري)');
  String get confirm => _t('Complete payment', 'إتمام الدفع');
  String get cancel => _t('Cancel', 'إلغاء');
  String get required => _t(
    'Enter the cheque number, the bank and the due date.',
    'أدخل رقم الشيك والبنك وتاريخ الاستحقاق.',
  );
  String get amountIsTotal => _t(
    'The cheque is recorded for the invoice total.',
    'يُسجَّل الشيك بقيمة إجمالي الفاتورة.',
  );

  // ── Store Admin page ──
  String get navCheques => _t('Cheques', 'الشيكات');
  String get pageSubtitle => _t(
    'Cheques received, their status, history and what is owed back',
    'الشيكات المستلمة وحالتها وسجلها والمستحق إرجاعه',
  );
  String get all => _t('All', 'الكل');
  String status(String s) => switch (s) {
    'RECEIVED' => _t('Received', 'مستلم'),
    'DEPOSITED' => _t('Deposited', 'مودع'),
    'CLEARED' => _t('Cleared', 'محصّل'),
    'BOUNCED' => _t('Bounced', 'مرتجع'),
    'CANCELLED' => _t('Cancelled', 'ملغى'),
    'PENDING' => _t('Pending', 'معلّق'),
    'PAID' => _t('Paid', 'مدفوع'),
    _ => s,
  };
  String get none => _t('No cheques.', 'لا توجد شيكات.');
  String get invoice => _t('Invoice', 'الفاتورة');
  String get history => _t('History', 'السجل');
  String get refunds => _t('Owed back (returns)', 'المستحق للزبون (مرتجعات)');
  String moveTo(String s) => _t('Mark as ${status(s).toLowerCase()}', 'تحويل إلى: ${status(s)}');
  String get note => _t('Note', 'ملاحظة');
  String get noteRequired =>
      _t('Explain why (3–300 characters).', 'اشرح السبب (3–300 حرف).');
  String get payCash => _t('Pay in cash', 'دفع نقداً');
  String get payBank => _t('Pay by bank transfer', 'دفع بتحويل بنكي');
  String get cancelRefund => _t('Cancel the refund', 'إلغاء المستحق');
  String get reason => _t('Reason', 'السبب');
  String get dueSoon => _t('Due within 7 days', 'تستحق خلال 7 أيام');
  String get pendingRefunds => _t('Refunds pending', 'مستحقات معلّقة');
  String get notClearedHint => _t(
    'A refund is paid only once the cheque has cleared; cash comes from your open shift.',
    'يُدفع المستحق فقط بعد تحصيل الشيك؛ والدفع النقدي يكون من ورديتك المفتوحة.',
  );
  String get save => _t('Save', 'حفظ');

  String? forCode(String? code) => switch (code) {
    'CHEQUE_DUPLICATE' => _t('This cheque (number and bank) is already recorded.', 'هذا الشيك (الرقم والبنك) مسجّل مسبقاً.'),
    'CHEQUE_NOT_CLEARED' => _t('The cheque has not cleared yet.', 'لم يُحصَّل الشيك بعد.'),
    'CHEQUE_RETURN_NEEDS_STORE_ADMIN' => _t(
      'This invoice was paid by cheque: its return is handled by the Store Admin.',
      'هذه الفاتورة مدفوعة بشيك: إرجاعها يتم عن طريق مدير المتجر.',
    ),
    'CHEQUE_TRANSITION_NOT_ALLOWED' => _t('This status change is not allowed.', 'هذا التغيير في الحالة غير مسموح.'),
    'CHEQUE_REFUND_DECIDED' => _t('This refund was already decided.', 'تم البت في هذا المستحق مسبقاً.'),
    _ => null,
  };
}
