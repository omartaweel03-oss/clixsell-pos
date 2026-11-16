import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/app_user.dart';
import '../../models/backup_info.dart';
import '../../models/store_info.dart';
import '../../services/api_http.dart';
import '../../services/settings_api_client.dart';
import '../../theme/app_theme.dart';
import '../../widgets/app_sidebar.dart' show roleLabel;

String _apiError(ApiException e, AppStrings s) => e.isNetworkError
    ? s.networkError
    : e.isForbidden
    ? s.notAllowed
    : s.requestFailed(e.message);

Widget _errorText(String text) => Padding(
  padding: const EdgeInsets.only(bottom: AppSpacing.md),
  child: Text(
    text,
    key: const Key('settingsDialogError'),
    style: const TextStyle(color: AppColors.danger),
  ),
);

Widget _note(String text, {Key? key}) => Container(
  key: key,
  padding: const EdgeInsets.all(AppSpacing.md),
  decoration: BoxDecoration(
    color: AppColors.infoSoft,
    borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
  ),
  child: Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Icon(Icons.info_outline_rounded, size: 18, color: AppColors.info),
      const SizedBox(width: AppSpacing.sm),
      Expanded(
        child: Text(text, style: const TextStyle(fontSize: 13, height: 1.4)),
      ),
    ],
  ),
);

// ── Taxes / General (Store Admin, own store) ────────────────────────────────

/// Taxes ([general] false: tax rate, maximum discount) or General ([general]
/// true: store name read-only, address, phone) of the Store Admin's own store.
/// Both save through PUT /api/store/settings with the other values unchanged.
/// Pops the saved [StoreInfo].
class StoreSettingsDialog extends StatefulWidget {
  const StoreSettingsDialog({
    super.key,
    required this.api,
    required this.general,
  });

  final SettingsApiClient api;
  final bool general;

  @override
  State<StoreSettingsDialog> createState() => _StoreSettingsDialogState();
}

class _StoreSettingsDialogState extends State<StoreSettingsDialog> {
  final _formKey = GlobalKey<FormState>();
  final _tax = TextEditingController();
  final _maxDiscount = TextEditingController();
  final _address = TextEditingController();
  final _phone = TextEditingController();
  final Map<String, String> _serverErrors = {};
  StoreInfo? _store;
  String? _error;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _tax.dispose();
    _maxDiscount.dispose();
    _address.dispose();
    _phone.dispose();
    super.dispose();
  }

  static String _num(double v) =>
      v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  Future<void> _load() async {
    try {
      final store = await widget.api.myStore();
      if (!mounted) return;
      setState(() {
        _store = store;
        _tax.text = _num(store.taxRate);
        _maxDiscount.text = _num(store.maxDiscountPercent);
        _address.text = store.address ?? '';
        _phone.text = store.phone ?? '';
      });
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _error = _apiError(e, AppStrings.of(context)));
      }
    }
  }

  String? _percent(String key, String? v, AppStrings s) {
    final server = _serverErrors[key];
    if (server != null) return server;
    final d = double.tryParse((v ?? '').trim());
    return d == null || d < 0 || d > 100 ? s.vPercent : null;
  }

  Future<void> _save() async {
    final s = AppStrings.of(context);
    _serverErrors.clear();
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final saved = await widget.api.updateStoreSettings(
        address: _address.text.trim(),
        phone: _phone.text.trim(),
        taxRate: double.parse(_tax.text.trim()),
        maxDiscountPercent: double.parse(_maxDiscount.text.trim()),
      );
      if (mounted) Navigator.of(context).pop(saved);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _serverErrors.addAll(e.fieldErrors);
        if (_serverErrors.isEmpty) _error = _apiError(e, s);
      });
      _formKey.currentState!.validate();
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final store = _store;
    final Widget body;
    if (store == null) {
      body = _error != null
          ? _errorText(_error!)
          : const Padding(
              padding: EdgeInsets.all(AppSpacing.xl),
              child: Center(child: CircularProgressIndicator()),
            );
    } else if (widget.general) {
      body = Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_error != null) _errorText(_error!),
          TextFormField(
            key: const Key('settingsStoreName'),
            initialValue: store.name,
            enabled: false,
            decoration: InputDecoration(
              labelText: s.storeNameLabel,
              helperText: s.storeNameBySystemAdmin,
              prefixIcon: const Icon(Icons.storefront_outlined),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          TextFormField(
            key: const Key('settingsAddress'),
            controller: _address,
            decoration: InputDecoration(
              labelText: s.addressLabel,
              prefixIcon: const Icon(Icons.place_outlined),
            ),
            validator: (v) =>
                _serverErrors['address'] ??
                ((v ?? '').trim().length > 120 ? s.addressTooLong : null),
          ),
          const SizedBox(height: AppSpacing.lg),
          TextFormField(
            key: const Key('settingsPhone'),
            controller: _phone,
            keyboardType: TextInputType.phone,
            decoration: InputDecoration(
              labelText: s.phoneLabel,
              prefixIcon: const Icon(Icons.phone_outlined),
            ),
            validator: (v) {
              final server = _serverErrors['phone'];
              if (server != null) return server;
              final p = (v ?? '').trim();
              return p.isNotEmpty && !RegExp(r'^[0-9+()\- ]{1,30}$').hasMatch(p)
                  ? s.vPhone
                  : null;
            },
          ),
        ],
      );
    } else {
      body = Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_error != null) _errorText(_error!),
          TextFormField(
            key: const Key('settingsTaxRate'),
            controller: _tax,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(
              labelText: s.taxRatePercent,
              suffixText: '%',
              prefixIcon: const Icon(Icons.percent_rounded),
            ),
            validator: (v) => _percent('taxRate', v, s),
          ),
          const SizedBox(height: AppSpacing.lg),
          TextFormField(
            key: const Key('settingsMaxDiscount'),
            controller: _maxDiscount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(
              labelText: s.maxDiscountPercent,
              suffixText: '%',
              prefixIcon: const Icon(Icons.discount_outlined),
            ),
            validator: (v) => _percent('maxDiscountPercent', v, s),
          ),
          const SizedBox(height: AppSpacing.lg),
          _note(s.taxesNote),
        ],
      );
    }

    return AlertDialog(
      key: Key(widget.general ? 'generalDialog' : 'taxesDialog'),
      title: Text(widget.general ? s.generalTitle : s.taxesTitle),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420, minWidth: 300),
        child: SingleChildScrollView(
          child: Form(key: _formKey, child: body),
        ),
      ),
      actions: [
        OutlinedButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: Text(s.cancel),
        ),
        FilledButton(
          key: const Key('settingsSaveButton'),
          onPressed: _saving || store == null ? null : _save,
          child: Text(s.saveSettings),
        ),
      ],
    );
  }
}

// ── Profile (System Admin, Store Admin) ─────────────────────────────────────

/// The logged-in admin's details and their own password change
/// (PUT /api/auth/me/password). Pops true when the password was changed.
class ProfileDialog extends StatefulWidget {
  const ProfileDialog({super.key, required this.user, required this.api});

  final AppUser user;
  final SettingsApiClient api;

  @override
  State<ProfileDialog> createState() => _ProfileDialogState();
}

class _ProfileDialogState extends State<ProfileDialog> {
  final _formKey = GlobalKey<FormState>();
  final _current = TextEditingController();
  final _next = TextEditingController();
  final _confirm = TextEditingController();
  final Map<String, String> _serverErrors = {};
  String? _error;
  bool _saving = false;

  @override
  void dispose() {
    _current.dispose();
    _next.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final s = AppStrings.of(context);
    _serverErrors.clear();
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.api.changeOwnPassword(
        currentPassword: _current.text,
        newPassword: _next.text,
      );
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _serverErrors.addAll(e.fieldErrors);
        if (_serverErrors.isEmpty) _error = _apiError(e, s);
      });
      _formKey.currentState!.validate();
    }
  }

  Widget _row(String label, String value, Key key) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 120,
          child: Text(
            label,
            style: const TextStyle(color: AppColors.textSecondary),
          ),
        ),
        Expanded(
          child: Text(
            value,
            key: key,
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
        ),
      ],
    ),
  );

  Widget _password(
    TextEditingController c,
    String label,
    Key key,
    String? Function(String) check,
  ) => TextFormField(
    key: key,
    controller: c,
    obscureText: true,
    autofillHints: const [AutofillHints.password],
    decoration: InputDecoration(
      labelText: label,
      prefixIcon: const Icon(Icons.lock_outline_rounded),
    ),
    validator: (v) => check(v ?? ''),
  );

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final u = widget.user;
    return AlertDialog(
      key: const Key('profileDialog'),
      title: Text(s.profileTitle),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 440, minWidth: 300),
        child: SingleChildScrollView(
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _row(s.fullNameLabel, u.fullName, const Key('profileName')),
                _row(s.usernameLabel, u.username, const Key('profileUsername')),
                _row(
                  s.roleFieldLabel,
                  roleLabel(u.role, s),
                  const Key('profileRole'),
                ),
                if (u.storeName != null)
                  _row(s.storeLabel, u.storeName!, const Key('profileStore')),
                const Divider(height: AppSpacing.xl),
                Text(
                  s.changePassword,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 15,
                  ),
                ),
                const SizedBox(height: AppSpacing.md),
                if (_error != null) _errorText(_error!),
                _password(
                  _current,
                  s.currentPassword,
                  const Key('currentPassword'),
                  (v) =>
                      _serverErrors['currentPassword'] ??
                      (v.isEmpty ? s.fieldRequired : null),
                ),
                const SizedBox(height: AppSpacing.md),
                _password(_next, s.newPassword, const Key('newPassword'), (v) {
                  final server = _serverErrors['newPassword'];
                  if (server != null) return server;
                  if (v.isEmpty) return s.fieldRequired;
                  if (v.length < 8) return s.passwordMin8;
                  if (v == _current.text) return s.passwordSameAsCurrent;
                  return null;
                }),
                const SizedBox(height: AppSpacing.md),
                _password(
                  _confirm,
                  s.confirmNewPassword,
                  const Key('confirmPassword'),
                  (v) => v != _next.text ? s.passwordsDoNotMatch : null,
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        OutlinedButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: Text(s.cancel),
        ),
        FilledButton(
          key: const Key('changePasswordButton'),
          onPressed: _saving ? null : _save,
          child: Text(s.changePassword),
        ),
      ],
    );
  }
}

// ── Language ────────────────────────────────────────────────────────────────

/// English / العربية. Pops the chosen locale (remembered by the app).
class LanguageDialog extends StatelessWidget {
  const LanguageDialog({super.key, required this.current});

  final Locale current;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    Widget option(String code, String label) {
      final selected = current.languageCode == code;
      return ListTile(
        key: Key('language-$code'),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
          side: BorderSide(
            color: selected ? AppColors.primary : AppColors.border,
          ),
        ),
        tileColor: selected ? AppColors.primarySoft : null,
        leading: const Icon(Icons.translate_rounded),
        title: Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
        trailing: selected
            ? const Icon(Icons.check_circle_rounded, color: AppColors.primary)
            : null,
        onTap: () => Navigator.of(context).pop(Locale(code)),
      );
    }

    return AlertDialog(
      key: const Key('languageDialog'),
      title: Text(s.languageTitle),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 360, minWidth: 280),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            option('en', 'English'),
            const SizedBox(height: AppSpacing.sm),
            option('ar', 'العربية'),
            const SizedBox(height: AppSpacing.lg),
            Text(
              s.languageSavedNote,
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 13,
              ),
            ),
          ],
        ),
      ),
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(s.close),
        ),
      ],
    );
  }
}

// ── Backup (System Admin) ───────────────────────────────────────────────────

/// Creates a checked backup on the server and lists the backups. No restore
/// here: restoring is manual (shown in the dialog and the documentation).
class BackupDialog extends StatefulWidget {
  const BackupDialog({super.key, required this.api});

  final SettingsApiClient api;

  @override
  State<BackupDialog> createState() => _BackupDialogState();
}

class _BackupDialogState extends State<BackupDialog> {
  BackupList? _list;
  BackupInfo? _created;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final list = await widget.api.listBackups();
      if (mounted) setState(() => _list = list);
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _error = _apiError(e, AppStrings.of(context)));
      }
    }
  }

  Future<void> _create() async {
    setState(() {
      _busy = true;
      _error = null;
      _created = null;
    });
    try {
      final created = await widget.api.createBackup();
      final list = await widget.api.listBackups();
      if (!mounted) return;
      setState(() {
        _created = created;
        _list = list;
        _busy = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = _apiError(e, AppStrings.of(context));
      });
    }
  }

  static String _size(int bytes) => bytes >= 1024 * 1024
      ? '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB'
      : '${(bytes / 1024).toStringAsFixed(0)} KB';

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final l = MaterialLocalizations.of(context);
    final list = _list;
    final created = _created;
    String date(DateTime d) =>
        '${l.formatMediumDate(d)} ${l.formatTimeOfDay(TimeOfDay.fromDateTime(d))}';

    return AlertDialog(
      key: const Key('backupDialog'),
      title: Text(s.backupTitle),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560, minWidth: 300),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) _errorText(_error!),
              if (created != null) ...[
                Container(
                  key: const Key('backupCreated'),
                  padding: const EdgeInsets.all(AppSpacing.md),
                  decoration: BoxDecoration(
                    color: AppColors.successSoft,
                    borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
                  ),
                  child: Text(
                    '${s.backupCreated(created.fileName)}\n'
                    '${s.backupRows(created.rowCounts['stores'] ?? 0, created.rowCounts['products'] ?? 0, created.rowCounts['sales'] ?? 0)}',
                    style: const TextStyle(
                      color: AppColors.success,
                      height: 1.4,
                    ),
                  ),
                ),
                const SizedBox(height: AppSpacing.md),
              ],
              FilledButton.icon(
                key: const Key('createBackupButton'),
                onPressed: _busy || list == null ? null : _create,
                icon: _busy
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.backup_outlined),
                label: Text(s.createBackupNow),
              ),
              const SizedBox(height: AppSpacing.lg),
              if (list == null && _error == null)
                const Center(child: CircularProgressIndicator())
              else if (list != null) ...[
                Text(
                  s.backupFolder,
                  style: const TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 12.5,
                  ),
                ),
                SelectableText(
                  list.folder,
                  key: const Key('backupFolder'),
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                  ),
                ),
                const SizedBox(height: AppSpacing.md),
                if (list.backups.isEmpty)
                  Text(s.noBackupsYet, key: const Key('noBackups'))
                else
                  Table(
                    key: const Key('backupTable'),
                    columnWidths: const {
                      0: FlexColumnWidth(3),
                      1: FlexColumnWidth(2.2),
                      2: FlexColumnWidth(1),
                    },
                    children: [
                      TableRow(
                        decoration: const BoxDecoration(
                          color: AppColors.background,
                        ),
                        children: [
                          for (final h in [
                            s.colBackupFile,
                            s.colBackupDate,
                            s.colBackupSize,
                          ])
                            Padding(
                              padding: const EdgeInsets.all(6),
                              child: Text(
                                h,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w700,
                                  color: AppColors.textSecondary,
                                  fontSize: 12.5,
                                ),
                              ),
                            ),
                        ],
                      ),
                      for (final b in list.backups)
                        TableRow(
                          key: ValueKey('backupRow-${b.fileName}'),
                          decoration: const BoxDecoration(
                            border: Border(
                              bottom: BorderSide(color: AppColors.divider),
                            ),
                          ),
                          children: [
                            for (final t in [
                              b.fileName,
                              date(b.createdAt),
                              _size(b.sizeBytes),
                            ])
                              Padding(
                                padding: const EdgeInsets.all(6),
                                child: Text(
                                  t,
                                  style: const TextStyle(fontSize: 12.5),
                                ),
                              ),
                          ],
                        ),
                    ],
                  ),
              ],
              const SizedBox(height: AppSpacing.lg),
              _note(
                '${s.restoreTitle}: ${s.restoreSteps}',
                key: const Key('restoreSteps'),
              ),
            ],
          ),
        ),
      ),
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(s.close),
        ),
      ],
    );
  }
}
