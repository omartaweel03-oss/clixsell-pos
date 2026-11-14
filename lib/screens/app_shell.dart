import 'package:flutter/material.dart';

import '../l10n/app_strings.dart';
import '../models/app_user.dart';
import '../models/product.dart';
import '../services/report_api_client.dart';
import '../services/admin_api_clients.dart';
import '../services/api_http.dart';
import '../state/product_store.dart';
import '../theme/app_theme.dart';
import '../widgets/app_sidebar.dart';
import 'admin/staff_accounts_page.dart';
import 'admin/stores_page.dart';
import 'pos/pos_page.dart';
import 'reports/reports_page.dart';
import 'reports/sales_analytics_page.dart';
import '../services/sales_api_client.dart';
import 'inventory/inventory_page.dart';
import 'inventory/product_form_page.dart';
import 'inventory/stock_alerts_page.dart';

/// Main layout after login: navy sidebar + content area.
///
/// The pages depend on the user's role:
/// * System Admin → Stores (and each store's Store Admins)
/// * Store Admin  → Products, Add / Edit Product, Cashiers
/// * Cashier      → Products (view only)
///
/// Adapts to the screen size:
/// * ≥ 1100 px: full sidebar
/// * ≥ 720 px: compact icon sidebar
/// * < 720 px: top app bar with a drawer
class AppShell extends StatefulWidget {
  const AppShell({
    super.key,
    required this.user,
    required this.http,
    required this.onToggleLanguage,
    required this.onLogout,
  });

  final AppUser user;
  final ApiHttp http;
  final VoidCallback onToggleLanguage;
  final VoidCallback onLogout;

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();

  late SidebarDestination _destination = widget.user.role.home;
  String? _editingProductId;

  /// Where the product form returns to (inventory, or Stock Alerts).
  SidebarDestination _formReturn = SidebarDestination.products;
  GlobalKey<ProductFormPageState> _formKey = GlobalKey<ProductFormPageState>();

  bool get _onForm =>
      _destination == SidebarDestination.addProduct ||
      _destination == SidebarDestination.editProduct;

  /// Asks before leaving a product form with unsaved changes.
  Future<bool> _canLeaveForm() async {
    if (!_onForm) return true;
    return await _formKey.currentState?.confirmLeave() ?? true;
  }

  /// Central navigation method used by the sidebar and page buttons.
  Future<void> _navigate(
    SidebarDestination destination, {
    Product? product,
  }) async {
    // Close the drawer on small screens.
    if (_scaffoldKey.currentState?.isDrawerOpen ?? false) {
      Navigator.of(context).pop();
    }
    // A role can only open its own pages.
    if (!widget.user.role.destinations.contains(destination)) return;

    final sameEdit =
        destination == SidebarDestination.editProduct &&
        _destination == SidebarDestination.editProduct &&
        (product == null || product.id == _editingProductId);
    if (destination == _destination &&
        (destination != SidebarDestination.editProduct || sameEdit)) {
      return; // already there
    }

    if (!await _canLeaveForm() || !mounted) return;

    setState(() {
      if (destination == SidebarDestination.addProduct ||
          destination == SidebarDestination.editProduct) {
        if (!_onForm) {
          _formReturn = _destination == SidebarDestination.stockAlerts
              ? SidebarDestination.stockAlerts
              : SidebarDestination.products;
        }
      }
      _destination = destination;
      if (destination == SidebarDestination.editProduct) {
        _editingProductId = product?.id ?? _editingProductId;
      } else {
        _editingProductId = null;
      }
      _formKey = GlobalKey<ProductFormPageState>();
    });
  }

  Future<void> _logout() async {
    if (_scaffoldKey.currentState?.isDrawerOpen ?? false) {
      Navigator.of(context).pop();
    }
    if (!await _canLeaveForm() || !mounted) return;
    widget.onLogout();
  }

  void _onSaved(Product product, {required bool wasEditing}) {
    final s = AppStrings.of(context);
    setState(() {
      _destination = _formReturn;
      _editingProductId = null;
    });
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Row(
            children: [
              const Icon(
                Icons.check_circle_rounded,
                color: AppColors.success,
                size: 20,
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  wasEditing
                      ? s.productUpdated(product.name)
                      : s.productAdded(product.name),
                ),
              ),
            ],
          ),
        ),
      );
  }

  void _backToList() {
    setState(() {
      _destination = _formReturn;
      _editingProductId = null;
    });
  }

  Widget _buildContent(ProductStore? store) {
    final s = AppStrings.of(context);
    switch (_destination) {
      case SidebarDestination.stores:
        return StoresPage(key: const ValueKey('stores'), http: widget.http);
      case SidebarDestination.pos:
        return PosPage(
          key: const ValueKey('pos'),
          api: SalesApiClient(widget.http),
        );
      case SidebarDestination.reports:
        return ReportsPage(
          key: const ValueKey('reports'),
          api: ReportApiClient(widget.http),
        );
      case SidebarDestination.stockAlerts:
        return StockAlertsPage(
          key: const ValueKey('stockAlerts'),
          api: ReportApiClient(widget.http),
          onUpdateStock: (id) {
            final product = store?.findById(id);
            if (product != null) {
              _navigate(SidebarDestination.editProduct, product: product);
            }
          },
        );
      case SidebarDestination.salesAnalytics:
        return SalesAnalyticsPage(
          key: const ValueKey('salesAnalytics'),
          api: ReportApiClient(widget.http),
        );
      case SidebarDestination.cashiers:
        return StaffAccountsPage(
          key: const ValueKey('cashiers'),
          api: cashiersApi(widget.http),
          title: s.cashiersTitle,
          description: s.cashiersDescription,
          addLabel: s.addCashier,
        );
      case SidebarDestination.products:
        return InventoryPage(
          key: const ValueKey('inventory'),
          readOnly: widget.user.role == UserRole.cashier,
          onAddProduct: () => _navigate(SidebarDestination.addProduct),
          onEditProduct: (p) =>
              _navigate(SidebarDestination.editProduct, product: p),
        );
      case SidebarDestination.addProduct:
        return ProductFormPage(
          key: _formKey,
          onSaved: (p) => _onSaved(p, wasEditing: false),
          onCancel: _backToList,
        );
      case SidebarDestination.editProduct:
        final product = _editingProductId == null
            ? null
            : store?.findById(_editingProductId!);
        if (product == null) {
          return _MissingProduct(onBack: _backToList);
        }
        return ProductFormPage(
          key: _formKey,
          initialProduct: product,
          onSaved: (p) => _onSaved(p, wasEditing: true),
          onCancel: _backToList,
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final store = widget.user.role.worksInStore
        ? ProductStoreScope.of(context)
        : null;
    final editingName = _editingProductId == null
        ? null
        : store?.findById(_editingProductId!)?.name;

    final content = AnimatedSwitcher(
      duration: const Duration(milliseconds: 220),
      switchInCurve: Curves.easeOut,
      // Expand children to the full content area so short pages (the form)
      // stay top-aligned instead of being centred by the default Stack.
      layoutBuilder: (current, previous) =>
          Stack(fit: StackFit.expand, children: [...previous, ?current]),
      child: KeyedSubtree(
        key: ValueKey('$_destination-$_editingProductId'),
        child: _buildContent(store),
      ),
    );

    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;

        Widget sidebar({required bool compact}) => AppSidebar(
          user: widget.user,
          selected: _destination,
          compact: compact,
          editingProductName: editingName,
          alertCount: store == null
              ? 0
              : store.stats.lowStock + store.stats.outOfStock,
          onSelect: (d) => _navigate(d),
          onToggleLanguage: widget.onToggleLanguage,
          onLogout: _logout,
        );

        if (width >= 720) {
          return Scaffold(
            key: _scaffoldKey,
            body: Row(
              children: [
                sidebar(compact: width < 1100),
                Expanded(child: content),
              ],
            ),
          );
        }

        // Small screens: AppBar + Drawer.
        final title = switch (_destination) {
          SidebarDestination.stores => s.storesTitle,
          SidebarDestination.products => s.inventoryTitle,
          SidebarDestination.addProduct => s.navAddProduct,
          SidebarDestination.editProduct => s.navEditProduct,
          SidebarDestination.cashiers => s.cashiersTitle,
          SidebarDestination.pos => s.navPos,
          SidebarDestination.reports => s.navReports,
          SidebarDestination.salesAnalytics => s.navSalesAnalytics,
          SidebarDestination.stockAlerts => s.navStockAlerts,
        };
        return Scaffold(
          key: _scaffoldKey,
          appBar: AppBar(
            backgroundColor: AppColors.sidebar,
            foregroundColor: Colors.white,
            title: Text(
              title,
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
          ),
          drawer: Drawer(
            width: 260,
            backgroundColor: AppColors.sidebar,
            child: sidebar(compact: false),
          ),
          body: content,
        );
      },
    );
  }
}

class _MissingProduct extends StatelessWidget {
  const _MissingProduct({required this.onBack});

  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(
            Icons.search_off_rounded,
            size: 48,
            color: AppColors.textMuted,
          ),
          const SizedBox(height: AppSpacing.md),
          Text(s.productNotFound),
          const SizedBox(height: AppSpacing.lg),
          FilledButton(onPressed: onBack, child: Text(s.backToInventory)),
        ],
      ),
    );
  }
}
