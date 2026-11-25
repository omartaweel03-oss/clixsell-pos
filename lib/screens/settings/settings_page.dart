import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/app_user.dart';
import '../../models/store_info.dart';
import '../../services/settings_api_client.dart';
import '../../theme/app_theme.dart';
import 'settings_dialogs.dart';

/// Settings screen – report Figure 3.9 (page 38), UR-M12 / SR25.
///
/// Title, description and today's date, then the eight cards of the figure:
/// Printer, Taxes, Users, Profile / Language, General, AI Mobile, Backup.
/// Only the System Admin and the Store Admin open this screen (the backend
/// refuses Cashiers every settings API anyway).
///
/// | Card      | Store Admin                         | System Admin                  |
/// |-----------|-------------------------------------|-------------------------------|
/// | Printer   | not available yet                   | not available yet             |
/// | Taxes     | own store's tax and max. discount   | per store → Stores page       |
/// | Users     | Cashiers page (existing)            | Stores page (Store Admins)    |
/// | Profile   | own details + change own password   | same                          |
/// | Language  | English / العربية, remembered        | same                          |
/// | General   | own store's address and phone       | per store → Stores page       |
/// | AI Mobile | not available yet (Figure 3.10)     | not available yet             |
/// | Backup    | System Admin only                   | create + list backups         |
class SettingsPage extends StatelessWidget {
  const SettingsPage({
    super.key,
    required this.user,
    required this.api,
    required this.onOpenUsers,
    required this.onOpenStores,
    required this.onSetLanguage,
    required this.onStoreSettingsSaved,
  });

  final AppUser user;
  final SettingsApiClient api;

  /// Opens the existing user management (Cashiers, or Stores for the System Admin).
  final VoidCallback onOpenUsers;

  /// Opens the existing Stores page (System Admin: per-store tax and details).
  final VoidCallback onOpenStores;
  final ValueChanged<Locale> onSetLanguage;

  /// Called after the Store Admin saved Taxes or General (POS uses it at once).
  final ValueChanged<StoreInfo> onStoreSettingsSaved;

  bool get _isSystemAdmin => user.role == UserRole.systemAdmin;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);

    void toast(String text) => ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(text)));

    Future<void> taxes() async {
      final saved = await showDialog<StoreInfo>(
        context: context,
        builder: (_) => StoreSettingsDialog(api: api, general: false),
      );
      if (saved != null) {
        onStoreSettingsSaved(saved);
        toast(s.taxesSaved);
      }
    }

    Future<void> general() async {
      final saved = await showDialog<StoreInfo>(
        context: context,
        builder: (_) => StoreSettingsDialog(api: api, general: true),
      );
      if (saved != null) {
        onStoreSettingsSaved(saved);
        toast(s.generalSaved);
      }
    }

    Future<bool> profile() async {
      final changed = await showDialog<bool>(
        context: context,
        builder: (_) => ProfileDialog(user: user, api: api),
      );
      if (changed == true) toast(s.passwordChanged);
      return changed == true;
    }

    Future<void> language() async {
      final chosen = await showDialog<Locale>(
        context: context,
        builder: (_) =>
            LanguageDialog(current: Localizations.localeOf(context)),
      );
      if (chosen != null) onSetLanguage(chosen);
    }

    Future<void> backup() => showDialog<void>(
      context: context,
      builder: (_) => BackupDialog(api: api),
    );

    final cards = <_CardData>[
      _CardData(
        'printer',
        Icons.print_outlined,
        s.setPrinter,
        s.setPrinterDesc,
        note: s.notAvailableYet,
        hint: s.printerNotAvailable,
      ),
      _CardData(
        'taxes',
        Icons.sell_outlined,
        s.setTaxes,
        s.setTaxesDesc,
        onTap: _isSystemAdmin ? onOpenStores : taxes,
        caption: _isSystemAdmin ? s.perStoreInStores : null,
      ),
      _CardData(
        'users',
        Icons.people_outline_rounded,
        s.setUsers,
        s.setUsersDesc,
        onTap: onOpenUsers,
      ),
      _CardData(
        'profile',
        Icons.person_outline_rounded,
        s.setProfile,
        s.setProfileDesc,
        onTap: profile,
      ),
      _CardData(
        'language',
        Icons.language_rounded,
        s.setLanguage,
        s.setLanguageDesc,
        onTap: language,
      ),
      _CardData(
        'general',
        Icons.settings_outlined,
        s.setGeneral,
        s.setGeneralDesc,
        onTap: _isSystemAdmin ? onOpenStores : general,
        caption: _isSystemAdmin ? s.perStoreInStores : null,
      ),
      _CardData(
        'aiMobile',
        Icons.phonelink_setup_outlined,
        s.setAiMobile,
        s.setAiMobileDesc,
        note: s.notAvailableYet,
        hint: s.aiMobileNotAvailable,
      ),
      _CardData(
        'backup',
        Icons.copy_all_outlined,
        s.setBackup,
        s.setBackupDesc,
        onTap: _isSystemAdmin ? backup : null,
        note: _isSystemAdmin ? null : s.systemAdminOnly,
        hint: _isSystemAdmin ? null : s.backupSystemAdminOnly,
      ),
    ];

    return LayoutBuilder(
      builder: (context, c) {
        final pad = c.maxWidth < 600 ? AppSpacing.md : AppSpacing.xl;
        return SingleChildScrollView(
          key: const Key('settingsPage'),
          padding: EdgeInsets.all(pad),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _Header(title: s.navSettings, description: s.settingsDescription),
              if (_isSystemAdmin)
                _DemoPasswordWarning(api: api, onChangePassword: profile),
              const SizedBox(height: AppSpacing.xxl),
              _CardGrid(cards: cards),
            ],
          ),
        );
      },
    );
  }
}

/// System Admin only: a clear warning while the signed-in System Admin still
/// uses a publicly documented demo password (GET /api/admin/security-status).
/// Nothing is shown when the account is safe, in the demo profile, or when the
/// status cannot be read. The button opens the existing password change; the
/// warning disappears once the password was changed. Nothing changes by itself.
class _DemoPasswordWarning extends StatefulWidget {
  const _DemoPasswordWarning({
    required this.api,
    required this.onChangePassword,
  });

  final SettingsApiClient api;

  /// Opens Settings → Profile; true when the password was changed.
  final Future<bool> Function() onChangePassword;

  @override
  State<_DemoPasswordWarning> createState() => _DemoPasswordWarningState();
}

class _DemoPasswordWarningState extends State<_DemoPasswordWarning> {
  bool _show = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final inUse = await widget.api.demoPasswordInUse();
      if (mounted) setState(() => _show = inUse);
    } catch (_) {
      // No warning when the status cannot be read (e.g. an older backend).
      if (mounted) setState(() => _show = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_show) return const SizedBox.shrink();
    final s = AppStrings.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: AppSpacing.xl),
      child: Container(
        key: const Key('demoPasswordWarning'),
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: AppColors.dangerSoft,
          border: Border.all(color: AppColors.danger),
          borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(
              Icons.gpp_maybe_rounded,
              color: AppColors.danger,
              size: 28,
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Semantics(
                    header: true,
                    child: Text(
                      s.demoPasswordWarningTitle,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: AppColors.danger,
                      ),
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Text(s.demoPasswordWarningBody),
                  const SizedBox(height: AppSpacing.md),
                  FilledButton.icon(
                    key: const Key('demoPasswordChange'),
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.danger,
                    ),
                    onPressed: () async {
                      if (await widget.onChangePassword()) await _load();
                    },
                    icon: const Icon(Icons.lock_reset_rounded, size: 18),
                    label: Text(s.demoPasswordWarningAction),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.title, required this.description});

  final String title;
  final String description;

  @override
  Widget build(BuildContext context) {
    final titleBlock = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          title,
          style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: AppSpacing.xs),
        Text(
          description,
          style: const TextStyle(
            fontSize: 14.5,
            color: AppColors.textSecondary,
          ),
        ),
      ],
    );
    final date = Container(
      key: const Key('settingsDate'),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        border: Border.all(color: AppColors.border),
      ),
      child: Text(
        MaterialLocalizations.of(context).formatFullDate(DateTime.now()),
        style: const TextStyle(fontWeight: FontWeight.w600),
      ),
    );
    return Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.start,
      spacing: AppSpacing.lg,
      runSpacing: AppSpacing.md,
      children: [titleBlock, date],
    );
  }
}

class _CardData {
  _CardData(
    this.id,
    this.icon,
    this.title,
    this.description, {
    this.onTap,
    this.note,
    this.hint,
    this.caption,
  });

  final String id;
  final IconData icon;
  final String title;
  final String description;

  /// null = the card cannot be opened (see [note]).
  final VoidCallback? onTap;

  /// Shown on a card that cannot be opened ("Not available yet", …).
  final String? note;

  /// Why the card cannot be opened (tooltip).
  final String? hint;

  /// Small extra line on an open card (e.g. "Per store – in Stores").
  final String? caption;
}

/// Four cards per row as in Figure 3.9; fewer when the space is narrow.
class _CardGrid extends StatelessWidget {
  const _CardGrid({required this.cards});

  final List<_CardData> cards;

  static const double _gap = AppSpacing.lg;
  static const double _maxCard = 250;
  static const double _minCard = 170;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, c) {
        var columns = ((c.maxWidth + _gap) / (_minCard + _gap)).floor();
        columns = columns.clamp(1, 4);
        final width = ((c.maxWidth - _gap * (columns - 1)) / columns).clamp(
          0.0,
          _maxCard,
        );
        return Align(
          alignment: AlignmentDirectional.topStart,
          child: Wrap(
            key: const Key('settingsCards'),
            spacing: _gap,
            runSpacing: _gap,
            children: [
              for (final d in cards)
                SizedBox(
                  width: width,
                  height: 176,
                  child: _SettingsCard(data: d),
                ),
            ],
          ),
        );
      },
    );
  }
}

class _SettingsCard extends StatelessWidget {
  const _SettingsCard({required this.data});

  final _CardData data;

  @override
  Widget build(BuildContext context) {
    final enabled = data.onTap != null;
    final fg = enabled ? AppColors.textPrimary : AppColors.textMuted;
    final card = Material(
      color: enabled ? AppColors.surface : AppColors.background,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        side: const BorderSide(color: AppColors.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: Key('settings-${data.id}'),
        onTap: data.onTap,
        hoverColor: AppColors.primarySoft,
        child: Padding(
          // Same top position on every card, so icons and titles line up in
          // a row as in Figure 3.9 (a "Not available yet" label goes below).
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.md,
            28,
            AppSpacing.md,
            AppSpacing.md,
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.start,
            children: [
              Icon(data.icon, size: 34, color: fg),
              const SizedBox(height: AppSpacing.sm),
              Text(
                data.title,
                textAlign: TextAlign.center,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 15.5,
                  fontWeight: FontWeight.w700,
                  color: fg,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                data.description,
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 12.5,
                  color: AppColors.textSecondary,
                  height: 1.35,
                ),
              ),
              if (data.note != null || data.caption != null) ...[
                const SizedBox(height: AppSpacing.sm),
                _Pill(
                  key: Key('settings-${data.id}-note'),
                  text: (data.note ?? data.caption)!,
                  muted: data.note != null,
                ),
              ],
            ],
          ),
        ),
      ),
    );
    final hint = data.hint;
    return hint == null ? card : Tooltip(message: hint, child: card);
  }
}

class _Pill extends StatelessWidget {
  const _Pill({super.key, required this.text, required this.muted});

  final String text;
  final bool muted;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
    decoration: BoxDecoration(
      color: muted ? AppColors.warningSoft : AppColors.primarySoft,
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      text,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(
        fontSize: 11.5,
        fontWeight: FontWeight.w600,
        color: muted ? AppColors.warning : AppColors.primary,
      ),
    ),
  );
}
