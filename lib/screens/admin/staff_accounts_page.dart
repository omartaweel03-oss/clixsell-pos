import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/app_user.dart';
import '../../services/admin_api_clients.dart';
import '../../services/api_http.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/page_header.dart';

/// Lists and manages staff accounts through a [StaffAccountsApi]:
/// * the Cashiers of the Store Admin's own store, or
/// * the Store Admins of one store (System Admin).
///
/// Accounts can be created, deactivated / activated and given a new password.
/// Accounts are never deleted, so their history is kept.
class StaffAccountsPage extends StatefulWidget {
  const StaffAccountsPage({
    super.key,
    required this.api,
    required this.title,
    required this.description,
    required this.addLabel,
    this.onBack,
  });

  final StaffAccountsApi api;
  final String title;
  final String description;
  final String addLabel;
  final VoidCallback? onBack;

  @override
  State<StaffAccountsPage> createState() => _StaffAccountsPageState();
}

class _StaffAccountsPageState extends State<StaffAccountsPage> {
  List<AppUser> _accounts = const [];
  bool _loading = true;
  ApiException? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await widget.api.list();
      if (!mounted) return;
      setState(() {
        _accounts = list;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  void _toast(String message, {bool error = false}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          backgroundColor: error ? AppColors.danger : null,
        ),
      );
  }

  String _describe(ApiException e, AppStrings s) => e.isNetworkError
      ? s.networkError
      : e.isForbidden
      ? s.notAllowed
      : s.requestFailed(e.message);

  Future<void> _add() async {
    final s = AppStrings.of(context);
    final created = await showDialog<AppUser>(
      context: context,
      builder: (_) =>
          _NewAccountDialog(api: widget.api, title: widget.addLabel),
    );
    if (created == null || !mounted) return;
    setState(() => _accounts = [..._accounts, created]);
    _toast(s.accountCreated(created.username));
  }

  Future<void> _toggle(AppUser user) async {
    final s = AppStrings.of(context);
    if (user.isActive) {
      final ok = await confirmAction(
        context,
        title: s.deactivateUserTitle,
        body: s.deactivateUserBody(user.username),
        confirmLabel: s.deactivate,
        cancelLabel: s.cancel,
        icon: Icons.person_off_outlined,
        color: AppColors.danger,
      );
      if (!ok || !mounted) return;
    }
    try {
      final updated = await widget.api.setActive(user.userId, !user.isActive);
      if (!mounted) return;
      setState(
        () => _accounts = [
          for (final a in _accounts) a.userId == updated.userId ? updated : a,
        ],
      );
      _toast(
        updated.isActive
            ? s.accountActivated(updated.username)
            : s.accountDeactivated(updated.username),
      );
    } on ApiException catch (e) {
      if (mounted) _toast(_describe(e, s), error: true);
    }
  }

  Future<void> _resetPassword(AppUser user) async {
    final s = AppStrings.of(context);
    final done = await showDialog<bool>(
      context: context,
      builder: (_) => _ResetPasswordDialog(api: widget.api, user: user),
    );
    if (done == true && mounted) _toast(s.passwordWasReset(user.username));
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final horizontal = constraints.maxWidth < 600
            ? AppSpacing.lg
            : AppSpacing.xxl;
        Widget body;
        if (_loading) {
          body = const Padding(
            padding: EdgeInsets.symmetric(vertical: 56),
            child: Center(child: CircularProgressIndicator()),
          );
        } else if (_error != null) {
          body = MessageState(
            icon: Icons.cloud_off_rounded,
            title: s.loadFailedTitle,
            body: _describe(_error!, s),
            actionLabel: s.retry,
            actionIcon: Icons.refresh_rounded,
            onAction: _load,
          );
        } else if (_accounts.isEmpty) {
          body = MessageState(
            icon: Icons.group_add_outlined,
            title: s.noStaffTitle,
            body: s.noStaffBody,
            actionLabel: widget.addLabel,
            onAction: _add,
          );
        } else {
          body = _AccountsList(
            accounts: _accounts,
            onToggle: _toggle,
            onResetPassword: _resetPassword,
          );
        }

        return SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            horizontal,
            AppSpacing.xl,
            horizontal,
            AppSpacing.xxl,
          ),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1200),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  PageHeader(
                    title: widget.title,
                    description: widget.description,
                    onBack: widget.onBack,
                    backTooltip: s.back,
                    action: FilledButton.icon(
                      key: const Key('addAccountButton'),
                      onPressed: _add,
                      icon: const Icon(
                        Icons.person_add_alt_1_rounded,
                        size: 20,
                      ),
                      label: Text(widget.addLabel),
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(AppSpacing.lg + 4),
                      child: body,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _AccountsList extends StatelessWidget {
  const _AccountsList({
    required this.accounts,
    required this.onToggle,
    required this.onResetPassword,
  });

  final List<AppUser> accounts;
  final ValueChanged<AppUser> onToggle;
  final ValueChanged<AppUser> onResetPassword;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    const header = TextStyle(
      fontSize: 12.5,
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
    );

    Widget actions(AppUser u) => Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        OutlinedButton.icon(
          key: Key('reset-${u.userId}'),
          onPressed: () => onResetPassword(u),
          style: OutlinedButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          ),
          icon: const Icon(Icons.key_rounded, size: 16),
          label: Text(s.resetPassword),
        ),
        OutlinedButton.icon(
          key: Key('toggle-${u.userId}'),
          onPressed: () => onToggle(u),
          style: OutlinedButton.styleFrom(
            foregroundColor: u.isActive ? AppColors.danger : AppColors.success,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          ),
          icon: Icon(
            u.isActive ? Icons.person_off_outlined : Icons.person_outline,
            size: 16,
          ),
          label: Text(u.isActive ? s.deactivate : s.activate),
        ),
      ],
    );

    Widget pill(AppUser u) => ActiveStatusPill(
      active: u.isActive,
      activeLabel: s.statusActive,
      inactiveLabel: s.statusInactive,
    );

    return LayoutBuilder(
      builder: (context, c) {
        if (c.maxWidth < 760) {
          return Column(
            children: [
              for (var i = 0; i < accounts.length; i++) ...[
                if (i > 0) const Divider(height: AppSpacing.xl),
                Row(
                  children: [
                    const CircleAvatar(
                      backgroundColor: AppColors.primarySoft,
                      child: Icon(
                        Icons.person_rounded,
                        color: AppColors.primary,
                      ),
                    ),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            accounts[i].fullName,
                            style: const TextStyle(fontWeight: FontWeight.w700),
                          ),
                          Text(
                            accounts[i].username,
                            style: const TextStyle(
                              color: AppColors.textSecondary,
                            ),
                          ),
                        ],
                      ),
                    ),
                    pill(accounts[i]),
                  ],
                ),
                const SizedBox(height: AppSpacing.md),
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: actions(accounts[i]),
                ),
              ],
            ],
          );
        }
        return Column(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
              decoration: BoxDecoration(
                color: AppColors.background,
                borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
              ),
              child: Row(
                children: [
                  Expanded(flex: 3, child: Text(s.colFullName, style: header)),
                  Expanded(flex: 3, child: Text(s.colUsername, style: header)),
                  Expanded(flex: 2, child: Text(s.colStatus, style: header)),
                  Expanded(flex: 2, child: Text(s.colCreated, style: header)),
                  Expanded(flex: 5, child: Text(s.colActions, style: header)),
                ],
              ),
            ),
            for (final u in accounts)
              Container(
                key: ValueKey('account-${u.userId}'),
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                decoration: const BoxDecoration(
                  border: Border(bottom: BorderSide(color: AppColors.divider)),
                ),
                child: Row(
                  children: [
                    Expanded(
                      flex: 3,
                      child: Text(
                        u.fullName,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    Expanded(
                      flex: 3,
                      child: Text(
                        u.username,
                        style: const TextStyle(color: AppColors.textSecondary),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    Expanded(
                      flex: 2,
                      child: Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: pill(u),
                      ),
                    ),
                    Expanded(
                      flex: 2,
                      child: Text(
                        u.createdAt == null
                            ? '—'
                            : Formatters.date(u.createdAt!).split(' ').first,
                        style: const TextStyle(color: AppColors.textSecondary),
                      ),
                    ),
                    Expanded(flex: 5, child: actions(u)),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

String? _usernameProblem(String? v, AppStrings s) {
  final u = (v ?? '').trim().toLowerCase();
  if (u.isEmpty) return s.vRequired;
  if (!RegExp(r'^[a-z0-9._-]{3,40}$').hasMatch(u)) return s.vUsernameFormat;
  return null;
}

String? _passwordProblem(String? v, AppStrings s) {
  if ((v ?? '').isEmpty) return s.vRequired;
  if (v!.length < 8) return s.vPasswordShort;
  return null;
}

class _NewAccountDialog extends StatefulWidget {
  const _NewAccountDialog({required this.api, required this.title});

  final StaffAccountsApi api;
  final String title;

  @override
  State<_NewAccountDialog> createState() => _NewAccountDialogState();
}

class _NewAccountDialogState extends State<_NewAccountDialog> {
  final _formKey = GlobalKey<FormState>();
  final _fullName = TextEditingController();
  final _username = TextEditingController();
  final _password = TextEditingController();
  final Map<String, String> _serverErrors = {};
  bool _obscure = true;
  bool _saving = false;
  String? _generalError;

  @override
  void dispose() {
    _fullName.dispose();
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final s = AppStrings.of(context);
    _serverErrors.clear();
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _generalError = null;
    });
    try {
      final user = await widget.api.create(
        username: _username.text,
        fullName: _fullName.text,
        password: _password.text,
      );
      if (mounted) Navigator.of(context).pop(user);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        if (e.code == 'DUPLICATE_USERNAME') {
          _serverErrors['username'] = s.usernameTaken;
        } else {
          _serverErrors.addAll(e.fieldErrors);
        }
        if (_serverErrors.isEmpty) {
          _generalError = e.isNetworkError
              ? s.networkError
              : s.requestFailed(e.message);
        }
      });
      _formKey.currentState!.validate();
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    return AlertDialog(
      title: Text(widget.title),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420, minWidth: 320),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_generalError != null) ...[
                Text(
                  _generalError!,
                  style: const TextStyle(color: AppColors.danger),
                ),
                const SizedBox(height: AppSpacing.md),
              ],
              TextFormField(
                key: const Key('accountFullName'),
                controller: _fullName,
                autofocus: true,
                decoration: InputDecoration(
                  labelText: '${s.fullNameLabel} *',
                  prefixIcon: const Icon(Icons.badge_outlined),
                ),
                validator: (v) =>
                    _serverErrors['fullName'] ??
                    ((v ?? '').trim().length < 2 ? s.vFullNameShort : null),
              ),
              const SizedBox(height: AppSpacing.lg),
              TextFormField(
                key: const Key('accountUsername'),
                controller: _username,
                decoration: InputDecoration(
                  labelText: '${s.usernameLabel} *',
                  hintText: s.usernameHint,
                  helperText: s.usernameHelper,
                  helperMaxLines: 2,
                  prefixIcon: const Icon(Icons.alternate_email_rounded),
                ),
                validator: (v) =>
                    _serverErrors['username'] ?? _usernameProblem(v, s),
              ),
              const SizedBox(height: AppSpacing.lg),
              TextFormField(
                key: const Key('accountPassword'),
                controller: _password,
                obscureText: _obscure,
                onFieldSubmitted: (_) => _save(),
                decoration: InputDecoration(
                  labelText: '${s.passwordLabel} *',
                  helperText: s.passwordHint,
                  prefixIcon: const Icon(Icons.lock_outline_rounded),
                  suffixIcon: IconButton(
                    tooltip: _obscure ? s.showPassword : s.hidePassword,
                    icon: Icon(
                      _obscure
                          ? Icons.visibility_outlined
                          : Icons.visibility_off_outlined,
                    ),
                    onPressed: () => setState(() => _obscure = !_obscure),
                  ),
                ),
                validator: (v) =>
                    _serverErrors['password'] ?? _passwordProblem(v, s),
              ),
            ],
          ),
        ),
      ),
      actions: [
        OutlinedButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: Text(s.cancel),
        ),
        FilledButton(
          key: const Key('accountSaveButton'),
          onPressed: _saving ? null : _save,
          child: Text(s.create),
        ),
      ],
    );
  }
}

class _ResetPasswordDialog extends StatefulWidget {
  const _ResetPasswordDialog({required this.api, required this.user});

  final StaffAccountsApi api;
  final AppUser user;

  @override
  State<_ResetPasswordDialog> createState() => _ResetPasswordDialogState();
}

class _ResetPasswordDialogState extends State<_ResetPasswordDialog> {
  final _formKey = GlobalKey<FormState>();
  final _password = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _password.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final s = AppStrings.of(context);
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.api.resetPassword(widget.user.userId, _password.text);
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error =
            e.fieldErrors['password'] ??
            (e.isNetworkError ? s.networkError : s.requestFailed(e.message));
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    return AlertDialog(
      title: Text(s.resetPasswordFor(widget.user.username)),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 400, minWidth: 300),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Text(_error!, style: const TextStyle(color: AppColors.danger)),
                const SizedBox(height: AppSpacing.md),
              ],
              TextFormField(
                key: const Key('resetPasswordField'),
                controller: _password,
                autofocus: true,
                obscureText: true,
                onFieldSubmitted: (_) => _save(),
                decoration: InputDecoration(
                  labelText: s.newPasswordLabel,
                  helperText: s.passwordHint,
                  prefixIcon: const Icon(Icons.key_rounded),
                ),
                validator: (v) => _passwordProblem(v, s),
              ),
            ],
          ),
        ),
      ),
      actions: [
        OutlinedButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(false),
          child: Text(s.cancel),
        ),
        FilledButton(
          key: const Key('resetPasswordSave'),
          onPressed: _saving ? null : _save,
          child: Text(s.saveChanges),
        ),
      ],
    );
  }
}
