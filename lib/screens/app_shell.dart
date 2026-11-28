import 'package:flutter/material.dart';

import '../l10n/app_strings.dart';
import '../models/app_user.dart';
import '../models/product.dart';
import '../models/purchase_order.dart';
import '../services/report_api_client.dart';
import '../services/admin_api_clients.dart';
import '../services/api_http.dart';
import '../state/pos_cart.dart';
import '../state/product_store.dart';
import '../theme/app_theme.dart';
import '../widgets/app_sidebar.dart';
import '../services/guardian_api_client.dart';
import 'admin/staff_accounts_page.dart';
import 'guardian/guardian_page.dart';
import 'admin/stores_page.dart';
import 'pos/pos_page.dart';
import 'pos/pos_resume.dart';
import 'pos/suspended_invoices_page.dart';
import 'settings/settings_page.dart';
import '../services/settings_api_client.dart';
import '../models/sale.dart';
import 'reports/reports_page.dart';
import 'reports/sales_analytics_page.dart';
import '../services/sales_api_client.dart';
import 'inventory/inventory_page.dart';
import 'inventory/product_details_page.dart';
import 'inventory/product_form_page.dart';
import 'inventory/stock_alerts_page.dart';
import 'purchasing/suppliers_page.dart';
import 'shifts/shifts_page.dart';
import 'cheques/cheques_page.dart';
import 'purchasing/purchase_order_details_page.dart';
import 'purchasing/purchase_order_form_page.dart';
import 'purchasing/purchase_orders_page.dart';
import 'purchasing/purchase_recommendations_page.dart';
import '../l10n/purchase_order_strings.dart';
import '../services/product_file_client.dart';
import '../services/purchase_api_client.dart';
import '../services/shift_api_client.dart';
import '../services/favorite_api_client.dart';
import '../services/cheque_api_client.dart';
import '../services/package_api_client.dart';
import '../services/supplier_api_client.dart';

/// Main layout after login: navy sidebar + content area.
///
/// The pages depend on the user's role:
/// * System Admin → Stores (and each store's Store Admins)
/// * Store Admin  → Products, Add / Edit Product, Cashiers
/// * Cashier      → Products (view only)
///
/// Desktop layout at every size: full sidebar from 1100 px, a compact icon
/// sidebar on narrower screens (no separate mobile layout).
class AppShell extends StatefulWidget {
  const AppShell({
    super.key,
    required this.user,
    required this.http,
    required this.onToggleLanguage,
    required this.onSetLanguage,
    required this.onLogout,
  });

  final AppUser user;
  final ApiHttp http;
  final VoidCallback onToggleLanguage;
  final ValueChanged<Locale> onSetLanguage;
  final VoidCallback onLogout;

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();

  late SidebarDestination _destination = widget.user.role.home;

  /// Store Admin: true until we know whether Guardian Mode is on (then the
  /// first page is Guardian Mode, as in Figure 3.1; otherwise Products).
  late bool _choosingHome = widget.user.role == UserRole.storeAdmin;

  @override
  void initState() {
    super.initState();
    if (_choosingHome) _chooseHome();
    // D2: the overdue badge is right from the first authenticated screen.
    _refreshPurchaseOverdue();
  }

  int _overdueRequest = 0;

  /// D2: reads the exact overdue count (GET /purchase-orders/summary) for
  /// the sidebar badge — after login, when entering the Purchasing section,
  /// and after an order action. Never on a timer. A failed read keeps the
  /// last known value; an older answer never overwrites a newer one.
  Future<void> _refreshPurchaseOverdue() async {
    if (widget.user.role != UserRole.storeAdmin) return;
    final req = ++_overdueRequest;
    try {
      final n = (await PurchaseApiClient(widget.http).summary()).overdue;
      if (!mounted || req != _overdueRequest) return;
      _setPurchaseOverdue(n);
    } catch (_) {
      // keep the last value; the next action or visit tries again
    }
  }

  void _setPurchaseOverdue(int n) {
    if (mounted && n != _purchaseOverdue) setState(() => _purchaseOverdue = n);
  }

  static const _purchasing = {
    SidebarDestination.suppliers,
    SidebarDestination.purchaseOrders,
    SidebarDestination.purchaseRecommendations,
    SidebarDestination.purchaseOrderDetails,
    SidebarDestination.purchaseOrderForm,
  };

  /// Decision D9: Guardian Mode is the Store Admin's first page when it is ON.
  /// If the status cannot be read, Products stays the first page.
  Future<void> _chooseHome() async {
    var guardianOn = false;
    try {
      guardianOn = (await GuardianApiClient(
        widget.http,
      ).status().timeout(const Duration(seconds: 5))).enabled;
    } catch (_) {
      guardianOn = false;
    }
    if (!mounted || !_choosingHome) return;
    setState(() {
      _choosingHome = false;
      if (guardianOn) _destination = SidebarDestination.guardian;
    });
  }

  /// The product open in the edit form or in Product Details.
  String? _editingProductId;

  /// Shown once by the POS when it opens (e.g. after resuming an invoice).
  String? _posMessage;

  /// Where the product form returns to (inventory, Stock Alerts, or
  /// Product Details when the form was opened from there).
  SidebarDestination _formReturn = SidebarDestination.products;
  GlobalKey<ProductFormPageState> _formKey = GlobalKey<ProductFormPageState>();

  bool get _onForm =>
      _destination == SidebarDestination.addProduct ||
      _destination == SidebarDestination.editProduct;

  // ── Purchase Orders (Phase 4) ──
  /// The order open in the details page (or being edited).
  int? _purchaseOrderId;
  String? _purchaseOrderNumber;

  /// The form: a new order (optionally from suggestions) or editing [_purchaseEditId].
  PurchaseOrderSeed? _purchaseSeed;
  int? _purchaseEditId;
  SidebarDestination _purchaseFormReturn = SidebarDestination.purchaseOrders;
  GlobalKey<PurchaseOrderFormPageState> _purchaseFormKey =
      GlobalKey<PurchaseOrderFormPageState>();
  int _purchaseOverdue = 0;

  /// Asks before leaving a product or purchase-order form with unsaved changes.
  Future<bool> _canLeaveForm() async {
    if (_destination == SidebarDestination.purchaseOrderForm) {
      return await _purchaseFormKey.currentState?.confirmLeave() ?? true;
    }
    if (!_onForm) return true;
    return await _formKey.currentState?.confirmLeave() ?? true;
  }

  Future<void> _openPurchaseOrder(int orderId) async {
    if (!await _canLeaveForm() || !mounted) return;
    setState(() {
      _choosingHome = false;
      _purchaseOrderId = orderId;
      _purchaseOrderNumber = null;
      _purchaseEditId = null;
      _purchaseSeed = null;
      _destination = SidebarDestination.purchaseOrderDetails;
    });
  }

  /// Opens the order form: new (manual or from suggestions) or editing.
  Future<void> _openPurchaseForm({PurchaseOrderSeed? seed, int? editId}) async {
    if (!await _canLeaveForm() || !mounted) return;
    setState(() {
      _choosingHome = false;
      _purchaseFormReturn = editId != null
          ? SidebarDestination.purchaseOrderDetails
          : (_destination == SidebarDestination.purchaseRecommendations
                ? SidebarDestination.purchaseRecommendations
                : SidebarDestination.purchaseOrders);
      _purchaseSeed = seed;
      _purchaseEditId = editId;
      _purchaseFormKey = GlobalKey<PurchaseOrderFormPageState>();
      _destination = SidebarDestination.purchaseOrderForm;
    });
  }

  /// Central navigation method used by the sidebar and page buttons.
  Future<void> _navigate(
    SidebarDestination destination, {
    Product? product,
  }) async {
    // A role can only open its own pages.
    if (!widget.user.role.destinations.contains(destination)) return;
    if (destination == SidebarDestination.purchaseOrderDetails &&
        _purchaseOrderId == null) {
      return;
    }
    if (destination == SidebarDestination.purchaseOrderForm &&
        _destination != SidebarDestination.purchaseOrderForm) {
      return; // the form opens only through "New" / "Edit"
    }

    bool forProduct(SidebarDestination d) =>
        d == SidebarDestination.editProduct ||
        d == SidebarDestination.productDetails;
    final sameProduct =
        forProduct(destination) &&
        destination == _destination &&
        (product == null || product.id == _editingProductId);
    if (destination == _destination &&
        (!forProduct(destination) || sameProduct)) {
      return; // already there
    }

    if (!await _canLeaveForm() || !mounted) return;

    setState(() {
      _choosingHome = false; // the user chose a page
      if (destination == SidebarDestination.addProduct ||
          destination == SidebarDestination.editProduct) {
        if (!_onForm) {
          _formReturn = switch (_destination) {
            SidebarDestination.stockAlerts => SidebarDestination.stockAlerts,
            SidebarDestination.productDetails
                when destination == SidebarDestination.editProduct =>
              SidebarDestination.productDetails,
            _ => SidebarDestination.products,
          };
        }
      }
      if (_purchasing.contains(destination) &&
          !_purchasing.contains(_destination) &&
          destination != SidebarDestination.purchaseOrders) {
        _refreshPurchaseOverdue(); // D2: returning to the Purchasing section
      }
      _destination = destination;
      if (forProduct(destination)) {
        _editingProductId = product?.id ?? _editingProductId;
      } else {
        _editingProductId = null;
      }
      _formKey = GlobalKey<ProductFormPageState>();
    });
  }

  Future<void> _logout() async {
    if (!await _canLeaveForm() || !mounted) return;
    widget.onLogout();
  }

  void _onSaved(Product product, {required bool wasEditing}) {
    final s = AppStrings.of(context);
    _backToList();
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

  /// Resume from the Suspended Invoices screen: the same rules as the POS
  /// window (loadSuspendedIntoCart), then the POS opens with the invoice.
  Future<void> _resumeInPos(Sale sale) async {
    final s = AppStrings.of(context);
    final missing = await loadSuspendedIntoCart(context, sale);
    if (!mounted) return;
    if (missing == null) {
      // "Keep my changes" for this same invoice: it is already open, so the
      // POS shows it as it is. (Keeping a DIFFERENT open invoice stays here.)
      if (PosCartScope.read(context).resumedSaleId != sale.saleId) return;
    } else {
      _posMessage = resumedMessage(s, sale, missing);
    }
    // Clear this screen's messages so none covers the POS buttons.
    ScaffoldMessenger.of(context).removeCurrentSnackBar();
    await _navigate(SidebarDestination.pos);
  }

  /// Leaves the product form: back to where it was opened from (Product
  /// Details keeps showing the same product).
  void _backToList() {
    setState(() {
      _destination = _formReturn;
      if (_formReturn != SidebarDestination.productDetails) {
        _editingProductId = null;
      }
      _formReturn = SidebarDestination.products;
    });
  }

  Widget _buildContent(ProductStore? store) {
    final s = AppStrings.of(context);
    if (_choosingHome) {
      return const Center(
        key: Key('choosingHome'),
        child: CircularProgressIndicator(),
      );
    }
    switch (_destination) {
      case SidebarDestination.purchaseOrders:
        return PurchaseOrdersPage(
          key: const ValueKey('purchaseOrders'),
          api: PurchaseApiClient(widget.http),
          onOpenOrder: _openPurchaseOrder,
          onNewOrder: () => _openPurchaseForm(),
          onOverdueCount: (n) {
            _overdueRequest++; // this fresh value wins over older reads
            _setPurchaseOverdue(n);
          },
        );
      case SidebarDestination.purchaseRecommendations:
        return PurchaseRecommendationsPage(
          key: const ValueKey('purchaseRecommendations'),
          api: PurchaseApiClient(widget.http),
          onCreateOrder: (seed) => _openPurchaseForm(seed: seed),
          onManualOrder: () => _openPurchaseForm(),
          onOpenOrder: _openPurchaseOrder,
        );
      case SidebarDestination.purchaseOrderDetails:
        return PurchaseOrderDetailsPage(
          key: ValueKey('purchaseOrder-$_purchaseOrderId'),
          api: _NumberTrackingPurchaseApi(PurchaseApiClient(widget.http), (n) {
            if (mounted && _purchaseOrderNumber != n) {
              setState(() => _purchaseOrderNumber = n);
            }
          }, onOrderChanged: _refreshPurchaseOverdue),
          orderId: _purchaseOrderId!,
          currentUserId: widget.user.userId,
          onBack: () => _navigate(SidebarDestination.purchaseOrders),
          onEdit: (id) => _openPurchaseForm(editId: id),
          onOpenSuppliers: () => _navigate(SidebarDestination.suppliers),
        );
      case SidebarDestination.purchaseOrderForm:
        return PurchaseOrderFormPage(
          key: _purchaseFormKey,
          api: _ShellPurchaseApi(
            widget.http,
            onOrderChanged: _refreshPurchaseOverdue,
          ),
          supplierApi: SupplierApiClient(widget.http),
          seed: _purchaseSeed,
          editOrderId: _purchaseEditId,
          currentUserId: widget.user.userId,
          onSaved: (order) {
            ScaffoldMessenger.of(context).hideCurrentSnackBar();
            setState(() {
              _purchaseOrderId = order.orderId;
              _purchaseOrderNumber = order.orderNumber;
              _purchaseSeed = null;
              _purchaseEditId = null;
              _destination = SidebarDestination.purchaseOrderDetails;
            });
          },
          onCancel: () => _navigate(
            _purchaseFormReturn == SidebarDestination.purchaseOrderDetails &&
                    _purchaseOrderId == null
                ? SidebarDestination.purchaseOrders
                : _purchaseFormReturn,
          ),
        );
      case SidebarDestination.guardian:
        return GuardianPage(
          key: const ValueKey('guardian'),
          api: GuardianApiClient(widget.http),
          salesApi: SalesApiClient(widget.http),
          onOpenStockAlerts: () => _navigate(SidebarDestination.stockAlerts),
          // Figure 3.1 "Create purchase order" (D21 / P4-D3): the real flow.
          onCreatePurchaseOrder: () =>
              _navigate(SidebarDestination.purchaseRecommendations),
          onOpenProduct: (id) async {
            // Read it again: it may have been added or deleted since login.
            final product = await store?.refreshProduct('$id');
            if (product == null || !mounted) return false;
            await _navigate(
              SidebarDestination.productDetails,
              product: product,
            );
            return true;
          },
        );
      case SidebarDestination.stores:
        return StoresPage(key: const ValueKey('stores'), http: widget.http);
      case SidebarDestination.pos:
        final message = _posMessage;
        _posMessage = null;
        return PosPage(
          key: const ValueKey('pos'),
          api: SalesApiClient(widget.http),
          shifts: ShiftApiClient(widget.http),
          favorites: FavoriteApiClient(widget.http),
          packages: PackageApiClient(widget.http),
          message: message,
        );
      case SidebarDestination.cheques:
        return ChequesPage(
          key: const ValueKey('cheques'),
          api: ChequeApiClient(widget.http),
        );
      case SidebarDestination.shifts:
        return ShiftsPage(
          key: const ValueKey('shifts'),
          api: ShiftApiClient(widget.http),
        );
      case SidebarDestination.suspendedInvoices:
        return SuspendedInvoicesPage(
          key: const ValueKey('suspendedInvoices'),
          api: SalesApiClient(widget.http),
          onResume: _resumeInPos,
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
          onCreatePurchaseOrder: () => _openPurchaseForm(),
          onUpdateStock: (id) {
            final product = store?.findById(id);
            if (product != null) {
              _navigate(SidebarDestination.editProduct, product: product);
            }
          },
        );
      case SidebarDestination.suppliers:
        return SuppliersPage(
          key: const ValueKey('suppliers'),
          api: SupplierApiClient(widget.http),
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
      case SidebarDestination.settings:
        final systemAdmin = widget.user.role == UserRole.systemAdmin;
        return SettingsPage(
          key: const ValueKey('settings'),
          user: widget.user,
          api: SettingsApiClient(widget.http),
          // Users: the existing user management, no second one.
          onOpenUsers: () => _navigate(
            systemAdmin
                ? SidebarDestination.stores
                : SidebarDestination.cashiers,
          ),
          onOpenStores: () => _navigate(SidebarDestination.stores),
          onSetLanguage: widget.onSetLanguage,
          // New tax / discount apply to this admin's POS at once.
          onStoreSettingsSaved: (store) =>
              PosCartScope.maybeRead(context)?.applyStoreSettings(
                taxRate: store.taxRate,
                maxDiscountPercent: store.maxDiscountPercent,
              ),
        );
      case SidebarDestination.products:
        return InventoryPage(
          key: const ValueKey('inventory'),
          readOnly: widget.user.role == UserRole.cashier,
          // Import / export (UR-M11): Store Admins only.
          productFiles: widget.user.role == UserRole.storeAdmin
              ? ProductFileApiClient(widget.http)
              : null,
          // POS favorites (feature 5): Store Admins choose and order them.
          favorites: widget.user.role == UserRole.storeAdmin
              ? FavoriteApiClient(widget.http)
              : null,
          onAddProduct: () => _navigate(SidebarDestination.addProduct),
          onEditProduct: (p) =>
              _navigate(SidebarDestination.editProduct, product: p),
          // Product Details (Figure 3.4): Store Admins only.
          onOpenProduct: widget.user.role == UserRole.storeAdmin
              ? (p) => _navigate(SidebarDestination.productDetails, product: p)
              : null,
        );
      case SidebarDestination.productDetails:
        final id = _editingProductId;
        if (id == null) return _MissingProduct(onBack: _backToList);
        return ProductDetailsPage(
          key: ValueKey('details-$id'),
          productId: id,
          onBack: () => _navigate(SidebarDestination.products),
          onEdit: (p) => _navigate(SidebarDestination.editProduct, product: p),
          packages: PackageApiClient(widget.http),
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
    final store = widget.user.role.worksInStore
        ? ProductStoreScope.of(context)
        : null;
    final productName = _editingProductId == null
        ? null
        : store?.findById(_editingProductId!)?.name;
    final editingName = _destination == SidebarDestination.editProduct
        ? productName
        : null;
    final viewingName = _destination == SidebarDestination.productDetails
        ? productName
        : null;

    final content = AnimatedSwitcher(
      duration: const Duration(milliseconds: 220),
      switchInCurve: Curves.easeOut,
      // Expand children to the full content area so short pages (the form)
      // stay top-aligned instead of being centred by the default Stack.
      layoutBuilder: (current, previous) =>
          Stack(fit: StackFit.expand, children: [...previous, ?current]),
      child: KeyedSubtree(
        key: ValueKey(
          '$_destination-$_editingProductId-$_purchaseOrderId-$_purchaseEditId-$_choosingHome',
        ),
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
          viewingProductName: viewingName,
          alertCount: store == null
              ? 0
              : store.stats.lowStock + store.stats.outOfStock,
          purchaseOverdue: _purchaseOverdue,
          purchaseOrderCaption: _purchaseOrderId == null
              ? null
              : (_purchaseOrderNumber ?? '#$_purchaseOrderId'),
          purchaseFormCaption:
              _destination == SidebarDestination.purchaseOrderForm
              ? (_purchaseEditId != null
                    ? PurchaseOrderStrings.of(context).editOrderTitle(
                        _purchaseOrderNumber ?? '#$_purchaseEditId',
                      )
                    : PurchaseOrderStrings.of(context).newOrderTitle)
              : null,
          onSelect: (d) => _navigate(d),
          onToggleLanguage: widget.onToggleLanguage,
          onLogout: _logout,
        );

        // Desktop dashboard (report Figures 3.2–3.9) at every size: the
        // sidebar always stays on screen; it shrinks to icons when space is
        // tight. No separate mobile layout (only AI Product Identification,
        // Figure 3.10, will have one).
        return Scaffold(
          key: _scaffoldKey,
          body: Row(
            children: [
              sidebar(compact: width < 1100),
              Expanded(child: content),
            ],
          ),
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

/// Passes calls through and reports the order number of the loaded order
/// (for the sidebar caption).
class _NumberTrackingPurchaseApi extends _ShellPurchaseApi {
  _NumberTrackingPurchaseApi(
    PurchaseApiClient inner,
    this.onNumber, {
    required super.onOrderChanged,
  }) : super(inner.http);

  final ValueChanged<String> onNumber;

  @override
  Future<PurchaseOrder> get(int orderId) async {
    final o = await super.get(orderId);
    onNumber(o.orderNumber);
    return o;
  }
}

/// D2: passes calls through and, after every request that may change an
/// order (successful, refused or with an unknown outcome), asks the shell to
/// refresh the overdue badge once. No timer, no polling.
class _ShellPurchaseApi extends PurchaseApiClient {
  _ShellPurchaseApi(super.http, {required this.onOrderChanged});

  final VoidCallback onOrderChanged;

  Future<T> _after<T>(Future<T> f) async {
    try {
      return await f;
    } finally {
      onOrderChanged();
    }
  }

  @override
  Future<PurchaseOrder> create(CreateOrderRequest request) =>
      _after(super.create(request));

  @override
  Future<PurchaseOrder> edit(
    int orderId, {
    required int version,
    required String? note,
    required List<OrderLine> lines,
    required List<CautionAck> acks,
  }) => _after(
    super.edit(orderId, version: version, note: note, lines: lines, acks: acks),
  );

  @override
  Future<PurchaseOrder> approve(int orderId, int version) =>
      _after(super.approve(orderId, version));

  @override
  Future<PurchaseOrder> reject(int orderId, int version, String reason) =>
      _after(super.reject(orderId, version, reason));

  @override
  Future<PurchaseOrder> cancel(
    int orderId, {
    required int version,
    required String reason,
    ContactStatus? contactStatus,
    bool? confirmNotSent,
    bool? acknowledgeSupplierNotNotified,
  }) => _after(
    super.cancel(
      orderId,
      version: version,
      reason: reason,
      contactStatus: contactStatus,
      confirmNotSent: confirmNotSent,
      acknowledgeSupplierNotNotified: acknowledgeSupplierNotNotified,
    ),
  );

  @override
  Future<PurchaseOrder> generateMessage(
    int orderId, {
    required int version,
    required String language,
    required String previewToken,
  }) => _after(
    super.generateMessage(
      orderId,
      version: version,
      language: language,
      previewToken: previewToken,
    ),
  );

  @override
  Future<PurchaseOrder> markSent(int orderId, int version) =>
      _after(super.markSent(orderId, version));

  @override
  Future<PurchaseOrder> supplierConfirmation(
    int orderId,
    int version,
    String? note,
  ) => _after(super.supplierConfirmation(orderId, version, note));

  @override
  Future<PurchaseOrder> close(int orderId, int version) =>
      _after(super.close(orderId, version));
}
