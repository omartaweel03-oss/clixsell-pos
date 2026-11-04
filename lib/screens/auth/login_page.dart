import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../services/api_http.dart';
import '../../state/auth_session.dart';
import '../../theme/app_theme.dart';

/// One Login screen for System Admins, Store Admins and Cashiers.
///
/// The user types only a username and password. The backend decides the
/// role and the store, and the app then opens the right screens.
class LoginPage extends StatefulWidget {
  const LoginPage({super.key, required this.onToggleLanguage});

  final VoidCallback onToggleLanguage;

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _obscure = true;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final s = AppStrings.of(context);
    if (_busy || !_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await AuthSessionScope.read(
        context,
      ).login(_username.text, _password.text);
      // On success the app replaces this screen; nothing else to do here.
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = switch (e.code) {
          'ACCOUNT_LOCKED' => s.loginLocked,
          'ACCOUNT_DISABLED' => s.loginDisabled,
          'NETWORK_ERROR' => s.networkError,
          _ => e.isUnauthorized ? s.loginInvalid : s.requestFailed(e.message),
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final session = AuthSessionScope.of(context);
    final message = _error ?? (session.sessionExpired ? s.sessionEnded : null);
    final isError = _error != null;

    final form = Form(
      key: _formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            s.loginTitle,
            style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: AppSpacing.xs + 2),
          Text(
            s.loginSubtitle,
            style: const TextStyle(color: AppColors.textSecondary, height: 1.4),
          ),
          const SizedBox(height: AppSpacing.xl),
          if (message != null) ...[
            Container(
              key: const Key('loginMessage'),
              padding: const EdgeInsets.all(AppSpacing.md),
              decoration: BoxDecoration(
                color: isError ? AppColors.dangerSoft : AppColors.warningSoft,
                borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
                border: Border.all(
                  color: (isError ? AppColors.danger : AppColors.warning)
                      .withValues(alpha: 0.4),
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    isError
                        ? Icons.error_outline_rounded
                        : Icons.info_outline_rounded,
                    color: isError ? AppColors.danger : AppColors.warning,
                    size: 20,
                  ),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: Text(message)),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
          ],
          TextFormField(
            key: const Key('loginUsername'),
            controller: _username,
            autofocus: true,
            textInputAction: TextInputAction.next,
            autofillHints: const [AutofillHints.username],
            decoration: InputDecoration(
              labelText: s.usernameLabel,
              prefixIcon: const Icon(Icons.person_outline_rounded),
            ),
            validator: (v) => (v ?? '').trim().isEmpty ? s.vRequired : null,
          ),
          const SizedBox(height: AppSpacing.lg),
          TextFormField(
            key: const Key('loginPassword'),
            controller: _password,
            obscureText: _obscure,
            textInputAction: TextInputAction.done,
            autofillHints: const [AutofillHints.password],
            onFieldSubmitted: (_) => _submit(),
            decoration: InputDecoration(
              labelText: s.passwordLabel,
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
            validator: (v) => (v ?? '').isEmpty ? s.vRequired : null,
          ),
          const SizedBox(height: AppSpacing.xl),
          FilledButton(
            key: const Key('loginButton'),
            onPressed: _busy ? null : _submit,
            child: _busy
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : Text(s.loginButton),
          ),
        ],
      ),
    );

    final brand = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 56,
          height: 56,
          decoration: BoxDecoration(
            color: AppColors.primary,
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd + 2),
          ),
          child: const Icon(
            Icons.point_of_sale_rounded,
            color: Colors.white,
            size: 30,
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        Text(
          s.appName,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 34,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: AppSpacing.xs),
        Text(
          s.loginTagline,
          style: const TextStyle(
            color: AppColors.sidebarText,
            fontSize: 15,
            height: 1.4,
          ),
        ),
      ],
    );

    final languageButton = TextButton.icon(
      onPressed: widget.onToggleLanguage,
      icon: const Icon(Icons.translate_rounded, size: 18),
      label: Text(s.languageToggle),
    );

    return Scaffold(
      body: LayoutBuilder(
        builder: (context, c) {
          final card = Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(AppSpacing.xl),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(AppSpacing.xxl),
                    child: AutofillGroup(child: form),
                  ),
                ),
              ),
            ),
          );

          if (c.maxWidth >= 900) {
            return Row(
              children: [
                Container(
                  width: c.maxWidth * 0.4,
                  color: AppColors.sidebar,
                  padding: const EdgeInsets.all(48),
                  alignment: AlignmentDirectional.centerStart,
                  child: brand,
                ),
                Expanded(
                  child: Stack(
                    children: [
                      card,
                      PositionedDirectional(
                        top: 16,
                        end: 16,
                        child: languageButton,
                      ),
                    ],
                  ),
                ),
              ],
            );
          }
          return SafeArea(
            child: Column(
              children: [
                Container(
                  width: double.infinity,
                  color: AppColors.sidebar,
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 28),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: brand),
                      IconButton(
                        tooltip: s.languageToggleTooltip,
                        onPressed: widget.onToggleLanguage,
                        icon: const Icon(
                          Icons.translate_rounded,
                          color: Colors.white,
                        ),
                      ),
                    ],
                  ),
                ),
                Expanded(child: card),
              ],
            ),
          );
        },
      ),
    );
  }
}
