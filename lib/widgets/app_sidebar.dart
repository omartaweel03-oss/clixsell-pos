import 'package:flutter/material.dart';

import '../l10n/app_strings.dart';
import '../l10n/shift_strings.dart';
import '../l10n/cheque_strings.dart';
import '../l10n/purchase_order_strings.dart';
import '../l10n/purchase_strings.dart';
import '../l10n/guardian_strings.dart';
import '../models/app_user.dart';
import '../theme/app_theme.dart';

/// The pages a user can open.
enum SidebarDestination {
  stores,
  pos,
  products,
  addProduct,
  editProduct,
  productDetails,
  cashiers,
  reports,
  salesAnalytics,
  stockAlerts,
  suppliers,
  suspendedInvoices,
  settings,
  guardian,
  // Purchase Orders (Phase 4)
  purchaseOrders,
  purchaseRecommendations,
  purchaseOrderDetails,
  purchaseOrderForm,
  // Cash drawers and shifts (store upgrade): Store Admin management page
  shifts,
  // Cheques (store upgrade, feature 9): Store Admin
  cheques,
}

/// Which pages each role may open (the backend enforces the same rules).
extension RoleDestinations on UserRole {
  List<SidebarDestination> get destinations => switch (this) {
    UserRole.systemAdmin => const [
      SidebarDestination.stores,
      SidebarDestination.settings,
    ],
    UserRole.storeAdmin => const [
      // Home stays Products; when Guardian Mode is ON the shell opens
      // Guardian Mode first instead (Figure 3.1, decision D9).
      SidebarDestination.products,
      // Guardian Mode (Figure 3.1): Store Admin only (UR-M16 / UR-M17).
      SidebarDestination.guardian,
      SidebarDestination.addProduct,
      SidebarDestination.editProduct,
      // Product Details (Figure 3.4) is a Store Admin screen (UR-M5).
      SidebarDestination.productDetails,
      SidebarDestination.stockAlerts,
      // Purchasing (UR-M13–UR-M15): Store Admin only; the backend refuses others.
      SidebarDestination.purchaseOrders,
      SidebarDestination.purchaseRecommendations,
      SidebarDestination.purchaseOrderDetails,
      SidebarDestination.purchaseOrderForm,
      SidebarDestination.suppliers,
      SidebarDestination.pos,
      SidebarDestination.suspendedInvoices,
      SidebarDestination.shifts,
      SidebarDestination.cheques,
      SidebarDestination.reports,
      SidebarDestination.salesAnalytics,
      SidebarDestination.cashiers,
      SidebarDestination.settings,
    ],
    // The cashier's home is the POS screen (report Figure 3.2). No Settings:
    // settings are for managers (UR-M12), and the backend refuses cashiers.
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
    this.viewingProductName,
    this.alertCount = 0,
    this.purchaseOverdue = 0,
    this.purchaseOrderCaption,
    this.purchaseFormCaption,
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

  /// Product open in Product Details (shown under the menu item).
  final String? viewingProductName;

  /// Low + out-of-stock products, shown as a badge on "Stock Alerts".
  final int alertCount;

  /// Overdue open purchase orders, shown as a badge on "Purchase Orders".
  final int purchaseOverdue;

  /// Order open in the details / form (shown under the menu item).
  final String? purchaseOrderCaption;
  final String? purchaseFormCaption;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);

    Widget section(String label) => compact
        ? const SizedBox(height: AppSpacing.sm)
        : Padding(
            padding: const EdgeInsetsDirectional.fromSTEB(24, 10, 24, 6),
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
      // Guardian Mode (Figure 3.1) is the manager's first item.
      if (user.role == UserRole.storeAdmin) ...[
        const SizedBox(height: AppSpacing.sm),
        item(
          SidebarDestination.guardian,
          Icons.shield_outlined,
          Icons.shield_rounded,
          GuardianStrings.of(context).navGuardian,
        ),
      ],
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
        if (user.role == UserRole.storeAdmin)
          item(
            SidebarDestination.shifts,
            Icons.account_balance_wallet_outlined,
            Icons.account_balance_wallet_rounded,
            ShiftStrings.of(context).navShifts,
          ),
        if (user.role == UserRole.storeAdmin)
          item(
            SidebarDestination.cheques,
            Icons.receipt_long_outlined,
            Icons.receipt_long_rounded,
            ChequeStrings.of(context).navCheques,
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
        if (viewingProductName != null)
          item(
            SidebarDestination.productDetails,
            Icons.info_outline_rounded,
            Icons.info_rounded,
            s.navProductDetails,
            caption: viewingProductName,
          ),
        // Stock Alerts (Figure 3.5): Manager feature, with the live alert count.
        item(
          SidebarDestination.stockAlerts,
          Icons.notifications_none_rounded,
          Icons.notifications_active_rounded,
          s.navStockAlerts,
          badge: alertCount,
        ),
        // Purchasing (P4-D7): orders, reorder suggestions and suppliers.
        section(PurchaseOrderStrings.of(context).navSection),
        item(
          SidebarDestination.purchaseOrders,
          Icons.receipt_long_outlined,
          Icons.receipt_long_rounded,
          PurchaseOrderStrings.of(context).navOrders,
          badge: purchaseOverdue,
        ),
        if (purchaseOrderCaption != null)
          item(
            SidebarDestination.purchaseOrderDetails,
            Icons.description_outlined,
            Icons.description_rounded,
            PurchaseOrderStrings.of(context).navOrders,
            caption: purchaseOrderCaption,
          ),
        if (purchaseFormCaption != null)
          item(
            SidebarDestination.purchaseOrderForm,
            Icons.edit_note_outlined,
            Icons.edit_note_rounded,
            purchaseFormCaption!,
          ),
        item(
          SidebarDestination.purchaseRecommendations,
          Icons.auto_graph_outlined,
          Icons.auto_graph_rounded,
          PurchaseOrderStrings.of(context).navRecommendations,
        ),
        item(
          SidebarDestination.suppliers,
          Icons.local_shipping_outlined,
          Icons.local_shipping_rounded,
          PurchaseStrings.of(context).navSuppliers,
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
      // Settings (report Figure 3.9): last item, System Admin and Store Admin.
      if (user.role != UserRole.cashier) ...[
        section(s.navSectionSystem),
        item(
          SidebarDestination.settings,
          Icons.settings_outlined,
          Icons.settings_rounded,
          s.navSettings,
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
            // All items are built (a short list), so every page stays reachable
            // and findable even when the window is too short to show them all.
            Expanded(
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: items,
                ),
              ),
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
      // Compact enough that every Store Admin item (with Guardian Mode) fits
      // on a 768 px high screen without scrolling.
      padding: EdgeInsets.symmetric(horizontal: compact ? 0 : 14, vertical: 10),
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
      padding: EdgeInsets.symmetric(horizontal: compact ? 14 : 16, vertical: 2),
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
