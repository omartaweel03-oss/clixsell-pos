import 'package:flutter/material.dart';

import '../l10n/app_strings.dart';
import '../models/app_user.dart';
import '../theme/app_theme.dart';

/// The pages a user can open.
enum SidebarDestination {
  stores,
  pos,
  products,
  addProduct,
  editProduct,
  cashiers,
  reports,
  salesAnalytics,
  stockAlerts,
  suspendedInvoices,
}

/// Which pages each role may open (the backend enforces the same rules).
extension RoleDestinations on UserRole {
  List<SidebarDestination> get destinations => switch (this) {
    UserRole.systemAdmin => const [SidebarDestination.stores],
    UserRole.storeAdmin => const [
      SidebarDestination.products,
      SidebarDestination.addProduct,
      SidebarDestination.editProduct,
      SidebarDestination.stockAlerts,
      SidebarDestination.pos,
      SidebarDestination.suspendedInvoices,
      SidebarDestination.reports,
      SidebarDestination.salesAnalytics,
      SidebarDestination.cashiers,
    ],
    // The cashier's home is the POS screen (report Figure 3.2).
    UserRole.cashier => const [
      SidebarDestination.pos,
      SidebarDestination.suspendedInvoices,
      SidebarDestination.products,
    ],
  };

  SidebarDestination get home => destinations.first;
}

String roleLabel(UserRole role, AppStrings s) => switch (role) {
  UserRole.systemAdmin => s.roleSystemAdmin,
  UserRole.storeAdmin => s.roleStoreAdmin,
  UserRole.cashier => s.roleCashier,
};

/// Dark navy navigation sidebar.
///
/// Shows only the pages the logged-in user's role may open, so there are no
/// dead buttons, plus who is logged in (name, role, store), the language
/// switch and Log out.
class AppSidebar extends StatelessWidget {
  const AppSidebar({
    super.key,
    required this.user,
    required this.selected,
    required this.onSelect,
    required this.onToggleLanguage,
    required this.onLogout,
    this.compact = false,
    this.editingProductName,
    this.alertCount = 0,
  });

  final AppUser user;
  final SidebarDestination selected;
  final ValueChanged<SidebarDestination> onSelect;
  final VoidCallback onToggleLanguage;
  final VoidCallback onLogout;

  /// Icon-only rail for medium-width screens.
  final bool compact;

  /// When not null an "Edit Product" entry is shown for the product being edited.
  final String? editingProductName;

  /// Low + out-of-stock products, shown as a badge on "Stock Alerts".
  final int alertCount;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);

    Widget section(String label) => compact
        ? const SizedBox(height: AppSpacing.md)
        : Padding(
            padding: const EdgeInsetsDirectional.fromSTEB(24, 12, 24, 8),
            child: Text(
              label,
              style: const TextStyle(
                color: AppColors.sidebarMuted,
                fontSize: 11,
                fontWeight: FontWeight.w700,
                letterSpacing: 1.1,
              ),
            ),
          );

    _NavItem item(
      SidebarDestination d,
      IconData icon,
      IconData selectedIcon,
      String label, {
      String? caption,
      int badge = 0,
    }) => _NavItem(
      key: Key('nav-${d.name}'),
      icon: icon,
      selectedIcon: selectedIcon,
      label: label,
      caption: caption,
      badge: badge,
      compact: compact,
      selected: selected == d,
      onTap: () => onSelect(d),
    );

    final items = <Widget>[
      if (user.role == UserRole.systemAdmin) ...[
        section(s.navSectionAdmin),
        item(
          SidebarDestination.stores,
          Icons.storefront_outlined,
          Icons.storefront_rounded,
          s.navStores,
        ),
      ],
      if (user.role.worksInStore) ...[
        section(s.navSectionSales),
        item(
          SidebarDestination.pos,
          Icons.point_of_sale_outlined,
          Icons.point_of_sale_rounded,
          s.navPos,
        ),
        // Suspended Invoices (Figure 3.6): Cashier and Store Admin.
        item(
          SidebarDestination.suspendedInvoices,
          Icons.pause_circle_outline_rounded,
          Icons.pause_circle_filled_rounded,
          s.navSuspended,
        ),
        section(s.navSectionInventory),
        item(
          SidebarDestination.products,
          Icons.inventory_2_outlined,
          Icons.inventory_2_rounded,
          s.navInventory,
          caption: user.role == UserRole.cashier ? s.viewOnly : null,
        ),
      ],
      if (user.role == UserRole.storeAdmin) ...[
        item(
          SidebarDestination.addProduct,
          Icons.add_box_outlined,
          Icons.add_box_rounded,
          s.navAddProduct,
        ),
        if (editingProductName != null)
          item(
            SidebarDestination.editProduct,
            Icons.edit_outlined,
            Icons.edit_rounded,
            s.navEditProduct,
            caption: editingProductName,
          ),
        // Stock Alerts (Figure 3.5): Manager feature, with the live alert count.
        item(
          SidebarDestination.stockAlerts,
          Icons.notifications_none_rounded,
          Icons.notifications_active_rounded,
          s.navStockAlerts,
          badge: alertCount,
        ),
        // Reports and Sales Analytics are Manager features (report Figures 3.7 / 3.8).
        section(s.navSectionReports),
        item(
          SidebarDestination.reports,
          Icons.assessment_outlined,
          Icons.assessment_rounded,
          s.navReports,
        ),
        item(
          SidebarDestination.salesAnalytics,
          Icons.insights_outlined,
          Icons.insights_rounded,
          s.navSalesAnalytics,
        ),
        section(s.navSectionStaff),
        item(
          SidebarDestination.cashiers,
          Icons.badge_outlined,
          Icons.badge_rounded,
          s.navCashiers,
        ),
      ],
    ];

    return Container(
      width: compact ? 84 : 248,
      color: AppColors.sidebar,
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _Brand(compact: compact),
            const SizedBox(height: AppSpacing.md),
            Expanded(
              child: ListView(padding: EdgeInsets.zero, children: items),
            ),
            _UserCard(user: user, compact: compact),
            Padding(
              padding: EdgeInsets.fromLTRB(
                compact ? AppSpacing.md : AppSpacing.lg,
                AppSpacing.sm,
                compact ? AppSpacing.md : AppSpacing.lg,
                compact ? AppSpacing.md : AppSpacing.lg,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _SidebarButton(
                    key: const Key('languageButton'),
                    compact: compact,
                    icon: Icons.translate_rounded,
                    label: s.languageToggle,
                    tooltip: s.languageToggleTooltip,
                    onPressed: onToggleLanguage,
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  _SidebarButton(
                    key: const Key('logoutButton'),
                    compact: compact,
                    icon: Icons.logout_rounded,
                    label: s.logout,
                    tooltip: s.logout,
                    onPressed: onLogout,
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

class _UserCard extends StatelessWidget {
  const _UserCard({required this.user, required this.compact});

  final AppUser user;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final initials = user.fullName.trim().isEmpty
        ? '?'
        : user.fullName
              .trim()
              .split(RegExp(r'\s+'))
              .take(2)
              .map((w) => w[0])
              .join()
              .toUpperCase();
    final avatar = CircleAvatar(
      radius: 18,
      backgroundColor: AppColors.primary,
      child: Text(
        initials,
        style: const TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.w700,
          fontSize: 13,
        ),
      ),
    );
    final details = [
      roleLabel(user.role, s),
      if (user.storeName != null) user.storeName!,
    ].join(' · ');

    if (compact) {
      return Tooltip(
        message: '${user.fullName}\n$details',
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Center(child: avatar),
        ),
      );
    }
    return Container(
      key: const Key('currentUserCard'),
      margin: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      ),
      child: Row(
        children: [
          avatar,
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  user.fullName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(
                  details,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.sidebarMuted,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SidebarButton extends StatelessWidget {
  const _SidebarButton({
    super.key,
    required this.compact,
    required this.icon,
    required this.label,
    required this.tooltip,
    required this.onPressed,
  });

  final bool compact;
  final IconData icon;
  final String label;
  final String tooltip;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: OutlinedButton(
        onPressed: onPressed,
        style: OutlinedButton.styleFrom(
          foregroundColor: Colors.white,
          side: BorderSide(color: Colors.white.withValues(alpha: 0.25)),
          padding: EdgeInsets.symmetric(
            horizontal: compact ? 0 : 16,
            vertical: 12,
          ),
        ),
        child: compact
            ? Icon(icon, size: 20)
            : Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(icon, size: 18),
                  const SizedBox(width: AppSpacing.sm),
                  Flexible(child: Text(label, overflow: TextOverflow.ellipsis)),
                ],
              ),
      ),
    );
  }
}

class _Brand extends StatelessWidget {
  const _Brand({required this.compact});

  final bool compact;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final logo = Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: AppColors.primary,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      ),
      child: const Icon(Icons.point_of_sale_rounded, color: Colors.white),
    );

    if (compact) {
      return Padding(
        padding: const EdgeInsets.only(top: AppSpacing.xl),
        child: Center(child: logo),
      );
    }

    return Padding(
      padding: const EdgeInsetsDirectional.fromSTEB(24, 28, 24, 0),
      child: Row(
        children: [
          logo,
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  s.appName,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 22,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                Text(
                  s.appSubtitle,
                  style: const TextStyle(
                    color: AppColors.sidebarMuted,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({
    super.key,
    required this.icon,
    required this.selectedIcon,
    required this.label,
    required this.selected,
    required this.onTap,
    required this.compact,
    this.caption,
    this.badge = 0,
  });

  final int badge;
  final IconData icon;
  final IconData selectedIcon;
  final String label;
  final String? caption;
  final bool selected;
  final bool compact;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final fg = selected ? Colors.white : AppColors.sidebarText;
    final content = AnimatedContainer(
      duration: const Duration(milliseconds: 160),
      padding: EdgeInsets.symmetric(horizontal: compact ? 0 : 14, vertical: 12),
      decoration: BoxDecoration(
        color: selected ? AppColors.primary : Colors.transparent,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        boxShadow: selected
            ? [
                BoxShadow(
                  color: AppColors.primary.withValues(alpha: 0.35),
                  blurRadius: 12,
                  offset: const Offset(0, 4),
                ),
              ]
            : null,
      ),
      child: compact
          ? Badge(
              isLabelVisible: badge > 0,
              label: Text('$badge'),
              backgroundColor: AppColors.danger,
              child: Icon(selected ? selectedIcon : icon, color: fg, size: 22),
            )
          : Row(
              children: [
                Icon(selected ? selectedIcon : icon, color: fg, size: 21),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: fg,
                          fontSize: 14.5,
                          fontWeight: selected
                              ? FontWeight.w700
                              : FontWeight.w500,
                        ),
                      ),
                      if (caption != null)
                        Text(
                          caption!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: fg.withValues(alpha: 0.75),
                            fontSize: 11.5,
                          ),
                        ),
                    ],
                  ),
                ),
                if (badge > 0)
                  Container(
                    key: const Key('alertBadge'),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 2,
                    ),
                    decoration: BoxDecoration(
                      color: AppColors.danger,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      '$badge',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
              ],
            ),
    );

    final item = Padding(
      padding: EdgeInsets.symmetric(horizontal: compact ? 14 : 16, vertical: 3),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          hoverColor: Colors.white.withValues(alpha: 0.06),
          onTap: onTap,
          child: content,
        ),
      ),
    );

    return compact
        ? Tooltip(
            message: caption == null ? label : '$label · $caption',
            child: item,
          )
        : item;
  }
}
