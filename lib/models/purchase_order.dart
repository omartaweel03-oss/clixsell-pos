/// Purchase orders as the backend returns them (Purchase Orders Phases 2–3).
///
/// Every value here comes from the server: the app never calculates a
/// quantity, a caution, a message, its length or which actions are allowed.
library;

int _int(Object? v) => (v as num?)?.toInt() ?? 0;
int? _intOrNull(Object? v) => (v as num?)?.toInt();
double? _double(Object? v) => (v as num?)?.toDouble();
List<Map<String, dynamic>> _list(Object? v) => [
  for (final e in (v as List<dynamic>? ?? const [])) e as Map<String, dynamic>,
];

/// The eight states of report Figure 3.20 (+ the approved deviations).
enum PurchaseOrderStatus {
  pendingApproval('PENDING_APPROVAL'),
  approved('APPROVED'),
  messageGenerated('MESSAGE_GENERATED'),
  sent('SENT'),
  confirmed('CONFIRMED'),
  closed('CLOSED'),
  rejected('REJECTED'),
  canceled('CANCELED');

  const PurchaseOrderStatus(this.code);

  final String code;

  static PurchaseOrderStatus parse(String code) =>
      values.firstWhere((s) => s.code == code, orElse: () => canceled);

  bool get isFinal => this == closed || this == rejected || this == canceled;
}

/// A person recorded on an order.
class PurchasePerson {
  const PurchasePerson(this.userId, this.fullName);

  final int userId;
  final String? fullName;

  static PurchasePerson? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    return PurchasePerson(_int(json['userId']), json['fullName'] as String?);
  }

  String get label => fullName ?? '#$userId';
}

/// A recent order that may already bring a product (RECENTLY_CLOSED,
/// CANCELED_AFTER_CONTACT, CONTACT_UNKNOWN, DECLARED_NOT_SENT).
class PurchaseCaution {
  const PurchaseCaution({
    required this.orderId,
    required this.orderNumber,
    required this.type,
    required this.at,
    required this.quantity,
    this.productId,
    this.productName,
  });

  final int orderId;
  final String orderNumber;
  final String type;
  final String? at;
  final int quantity;

  /// Filled when the caution comes with a 409 REORDER_CAUTION_ACK_REQUIRED.
  final int? productId;
  final String? productName;

  factory PurchaseCaution.fromJson(Map<String, dynamic> j) => PurchaseCaution(
    orderId: _int(j['orderId']),
    orderNumber: j['orderNumber'] as String? ?? '',
    type: j['type'] as String? ?? '',
    at: j['at'] as String?,
    quantity: _int(j['quantity']),
    productId: _intOrNull(j['productId']),
    productName: j['productName'] as String?,
  );
}

/// The open order that already holds a product.
class OpenOrderRef {
  const OpenOrderRef(
    this.orderId,
    this.orderNumber,
    this.status,
    this.quantity,
  );

  final int orderId;
  final String orderNumber;
  final String status;
  final int quantity;

  static OpenOrderRef? fromJson(Object? j) {
    if (j is! Map<String, dynamic>) return null;
    return OpenOrderRef(
      _int(j['orderId']),
      j['orderNumber'] as String? ?? '',
      j['status'] as String? ?? '',
      _int(j['quantity']),
    );
  }
}

class PurchaseOrderItem {
  const PurchaseOrderItem({
    required this.itemId,
    required this.productId,
    required this.productDeleted,
    required this.productName,
    required this.barcode,
    required this.quantity,
    required this.suggestedQuantity,
    required this.basis,
    required this.expectedUnitCost,
    required this.reserved,
    required this.calculation,
  });

  final int itemId;
  final int? productId;
  final bool productDeleted;
  final String productName;
  final String barcode;
  final int quantity;
  final int? suggestedQuantity;
  final String basis;
  final double? expectedUnitCost;
  final bool reserved;
  final Map<String, dynamic> calculation;

  factory PurchaseOrderItem.fromJson(Map<String, dynamic> j) =>
      PurchaseOrderItem(
        itemId: _int(j['itemId']),
        productId: _intOrNull(j['productId']),
        productDeleted: j['productDeleted'] as bool? ?? false,
        productName: j['productName'] as String? ?? '',
        barcode: j['barcode'] as String? ?? '',
        quantity: _int(j['quantity']),
        suggestedQuantity: _intOrNull(j['suggestedQuantity']),
        basis: j['basis'] as String? ?? 'MANUAL',
        expectedUnitCost: _double(j['expectedUnitCost']),
        reserved: j['reserved'] as bool? ?? false,
        calculation: (j['calculation'] as Map<String, dynamic>?) ?? const {},
      );
}

/// One line of the message-length report.
class LineLength {
  const LineLength(this.productId, this.productName, this.length);

  final int? productId;
  final String productName;
  final int length;
}

/// The backend's message-length report for one language (P3-D1).
class MessageLength {
  const MessageLength({
    required this.language,
    required this.length,
    required this.maxPossibleLength,
    required this.limit,
    required this.fits,
    required this.alwaysFits,
    required this.longestLines,
    required this.minLinesToRemove,
  });

  final String language;
  final int length;
  final int maxPossibleLength;
  final int limit;
  final bool fits;
  final bool alwaysFits;
  final List<LineLength> longestLines;
  final int minLinesToRemove;

  factory MessageLength.fromJson(Map<String, dynamic> j) => MessageLength(
    language: j['language'] as String? ?? 'ar',
    length: _int(j['length']),
    maxPossibleLength: _int(j['maxPossibleLength']),
    limit: _int(j['limit']),
    fits: j['fits'] as bool? ?? false,
    alwaysFits: j['alwaysFits'] as bool? ?? false,
    longestLines: [
      for (final l in _list(j['longestLines']))
        LineLength(
          _intOrNull(l['productId']),
          l['productName'] as String? ?? '',
          _int(l['length']),
        ),
    ],
    minLinesToRemove: _int(j['minLinesToRemove']),
  );

  static Map<String, MessageLength>? mapFromJson(Object? j) {
    if (j is! Map<String, dynamic>) return null;
    return {
      for (final e in j.entries)
        e.key: MessageLength.fromJson(e.value as Map<String, dynamic>),
    };
  }
}

/// The stored supplier message (never a link).
class SupplierMessage {
  const SupplierMessage({
    required this.text,
    required this.language,
    required this.whatsappNumber,
    required this.length,
    required this.maxLength,
    required this.generatedBy,
    required this.generatedAt,
    required this.actionable,
    required this.numberChangedSinceGeneration,
    required this.currentSupplierWhatsapp,
  });

  final String text;
  final String language;
  final String whatsappNumber;
  final int length;
  final int maxLength;
  final PurchasePerson? generatedBy;
  final String? generatedAt;

  /// The order is not final: the message may still be copied / opened.
  final bool actionable;
  final bool numberChangedSinceGeneration;
  final String? currentSupplierWhatsapp;

  static SupplierMessage? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    return SupplierMessage(
      text: json['text'] as String? ?? '',
      language: json['language'] as String? ?? 'ar',
      whatsappNumber: json['whatsappNumber'] as String? ?? '',
      length: _int(json['length']),
      maxLength: _int(json['maxLength']),
      generatedBy: PurchasePerson.fromJson(json['generatedBy']),
      generatedAt: json['generatedAt'] as String?,
      actionable: json['actionable'] as bool? ?? false,
      numberChangedSinceGeneration:
          json['numberChangedSinceGeneration'] as bool? ?? false,
      currentSupplierWhatsapp: json['currentSupplierWhatsapp'] as String?,
    );
  }
}

/// What the history records about the communication. Copy / opening counts are
/// reports from the app (never verified); the rest are the manager's records.
class Communication {
  const Communication({
    required this.copyReports,
    required this.openRequests,
    required this.clientOpenReports,
    required this.sentRecordedByManager,
    this.sentMarkedBy,
    this.sentMarkedAt,
    this.confirmationRecordedBy,
    this.confirmationRecordedAt,
    this.confirmationNote,
    this.closedBy,
    this.closedAt,
    required this.canceledAfterContact,
    this.contactStatusAtCancel,
    required this.possibleSupplierContact,
    required this.supplierCancellationRecorded,
    required this.supplierCancellationRecordable,
  });

  final int copyReports;
  final int openRequests;
  final int clientOpenReports;
  final bool sentRecordedByManager;
  final PurchasePerson? sentMarkedBy;
  final String? sentMarkedAt;
  final PurchasePerson? confirmationRecordedBy;
  final String? confirmationRecordedAt;
  final String? confirmationNote;
  final PurchasePerson? closedBy;
  final String? closedAt;
  final bool canceledAfterContact;
  final String? contactStatusAtCancel;
  final bool possibleSupplierContact;
  final bool supplierCancellationRecorded;
  final bool supplierCancellationRecordable;

  static const empty = Communication(
    copyReports: 0,
    openRequests: 0,
    clientOpenReports: 0,
    sentRecordedByManager: false,
    canceledAfterContact: false,
    possibleSupplierContact: false,
    supplierCancellationRecorded: false,
    supplierCancellationRecordable: false,
  );

  static Communication fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return empty;
    return Communication(
      copyReports: _int(json['copyReports']),
      openRequests: _int(json['openRequests']),
      clientOpenReports: _int(json['clientOpenReports']),
      sentRecordedByManager: json['sentRecordedByManager'] as bool? ?? false,
      sentMarkedBy: PurchasePerson.fromJson(json['sentMarkedBy']),
      sentMarkedAt: json['sentMarkedAt'] as String?,
      confirmationRecordedBy: PurchasePerson.fromJson(
        json['confirmationRecordedBy'],
      ),
      confirmationRecordedAt: json['confirmationRecordedAt'] as String?,
      confirmationNote: json['confirmationNote'] as String?,
      closedBy: PurchasePerson.fromJson(json['closedBy']),
      closedAt: json['closedAt'] as String?,
      canceledAfterContact: json['canceledAfterContact'] as bool? ?? false,
      contactStatusAtCancel: json['contactStatusAtCancel'] as String?,
      possibleSupplierContact:
          json['possibleSupplierContact'] as bool? ?? false,
      supplierCancellationRecorded:
          json['supplierCancellationRecorded'] as bool? ?? false,
      supplierCancellationRecordable:
          json['supplierCancellationRecordable'] as bool? ?? false,
    );
  }
}

/// GET /api/purchase-orders/{id}.
class PurchaseOrder {
  const PurchaseOrder({
    required this.orderId,
    required this.orderNumber,
    required this.status,
    required this.source,
    required this.supplierId,
    required this.supplierName,
    required this.supplierActive,
    required this.note,
    required this.version,
    required this.createdBy,
    required this.createdAt,
    this.approvedBy,
    this.approvedAt,
    this.rejectedBy,
    this.rejectedAt,
    this.rejectionReason,
    this.canceledBy,
    this.canceledAt,
    this.cancelReason,
    required this.overdue,
    required this.expectedTotalCost,
    required this.allowedActions,
    required this.approvalBlockers,
    required this.items,
    required this.replayed,
    required this.message,
    required this.communication,
    required this.messageLength,
  });

  final int orderId;
  final String orderNumber;
  final PurchaseOrderStatus status;
  final String source;
  final int supplierId;
  final String supplierName;
  final bool supplierActive;
  final String? note;
  final int version;
  final PurchasePerson? createdBy;
  final String? createdAt;
  final PurchasePerson? approvedBy;
  final String? approvedAt;
  final PurchasePerson? rejectedBy;
  final String? rejectedAt;
  final String? rejectionReason;
  final PurchasePerson? canceledBy;
  final String? canceledAt;
  final String? cancelReason;
  final bool overdue;
  final double? expectedTotalCost;
  final List<String> allowedActions;
  final List<String> approvalBlockers;
  final List<PurchaseOrderItem> items;
  final bool replayed;
  final SupplierMessage? message;
  final Communication communication;
  final Map<String, MessageLength>? messageLength;

  bool can(String action) => allowedActions.contains(action);

  factory PurchaseOrder.fromJson(Map<String, dynamic> j) => PurchaseOrder(
    orderId: _int(j['orderId']),
    orderNumber: j['orderNumber'] as String? ?? '',
    status: PurchaseOrderStatus.parse(j['status'] as String? ?? ''),
    source: j['source'] as String? ?? 'MANUAL',
    supplierId: _int(j['supplierId']),
    supplierName: j['supplierName'] as String? ?? '',
    supplierActive: j['supplierActive'] as bool? ?? false,
    note: j['note'] as String?,
    version: _int(j['version']),
    createdBy: PurchasePerson.fromJson(j['createdBy']),
    createdAt: j['createdAt'] as String?,
    approvedBy: PurchasePerson.fromJson(j['approvedBy']),
    approvedAt: j['approvedAt'] as String?,
    rejectedBy: PurchasePerson.fromJson(j['rejectedBy']),
    rejectedAt: j['rejectedAt'] as String?,
    rejectionReason: j['rejectionReason'] as String?,
    canceledBy: PurchasePerson.fromJson(j['canceledBy']),
    canceledAt: j['canceledAt'] as String?,
    cancelReason: j['cancelReason'] as String?,
    overdue: j['overdue'] as bool? ?? false,
    expectedTotalCost: _double(j['expectedTotalCost']),
    allowedActions: [
      for (final a in (j['allowedActions'] as List<dynamic>? ?? const []))
        a as String,
    ],
    approvalBlockers: [
      for (final a in (j['approvalBlockers'] as List<dynamic>? ?? const []))
        a as String,
    ],
    items: [for (final i in _list(j['items'])) PurchaseOrderItem.fromJson(i)],
    replayed: j['replayed'] as bool? ?? false,
    message: SupplierMessage.fromJson(j['message']),
    communication: Communication.fromJson(j['communication']),
    messageLength: MessageLength.mapFromJson(j['messageLength']),
  );
}

/// One row of the order list.
class PurchaseOrderSummary {
  const PurchaseOrderSummary({
    required this.orderId,
    required this.orderNumber,
    required this.status,
    required this.source,
    required this.supplierId,
    required this.supplierName,
    required this.itemCount,
    required this.createdBy,
    required this.createdAt,
    required this.overdue,
    required this.version,
  });

  final int orderId;
  final String orderNumber;
  final PurchaseOrderStatus status;
  final String source;
  final int supplierId;
  final String supplierName;
  final int itemCount;
  final PurchasePerson? createdBy;
  final String? createdAt;
  final bool overdue;
  final int version;

  factory PurchaseOrderSummary.fromJson(Map<String, dynamic> j) =>
      PurchaseOrderSummary(
        orderId: _int(j['orderId']),
        orderNumber: j['orderNumber'] as String? ?? '',
        status: PurchaseOrderStatus.parse(j['status'] as String? ?? ''),
        source: j['source'] as String? ?? 'MANUAL',
        supplierId: _int(j['supplierId']),
        supplierName: j['supplierName'] as String? ?? '',
        itemCount: _int(j['itemCount']),
        createdBy: PurchasePerson.fromJson(j['createdBy']),
        createdAt: j['createdAt'] as String?,
        overdue: j['overdue'] as bool? ?? false,
        version: _int(j['version']),
      );
}

class PurchaseOrderPage {
  const PurchaseOrderPage(this.page, this.size, this.total, this.orders);

  final int page;
  final int size;
  final int total;
  final List<PurchaseOrderSummary> orders;

  int get pageCount =>
      size <= 0 ? 1 : ((total + size - 1) ~/ size).clamp(1, 1 << 30);

  factory PurchaseOrderPage.fromJson(Map<String, dynamic> j) =>
      PurchaseOrderPage(_int(j['page']), _int(j['size']), _int(j['total']), [
        for (final o in _list(j['orders'])) PurchaseOrderSummary.fromJson(o),
      ]);
}

/// D1: exact counts of the store's orders from the server (one database
/// statement: no paging, no limit, nothing counted twice).
class PurchaseOrdersSummary {
  const PurchaseOrdersSummary({
    required this.byStatus,
    required this.open,
    required this.overdue,
    required this.overdueByStatus,
  });

  final Map<PurchaseOrderStatus, int> byStatus;
  final int open;
  final int overdue;
  final Map<PurchaseOrderStatus, int> overdueByStatus;

  int count(PurchaseOrderStatus s) => byStatus[s] ?? 0;

  factory PurchaseOrdersSummary.fromJson(Map<String, dynamic> j) {
    Map<PurchaseOrderStatus, int> counts(Object? v) {
      final m = v is Map ? v : const {};
      return {
        for (final s in PurchaseOrderStatus.values)
          if (m.containsKey(s.code)) s: _int(m[s.code]),
      };
    }

    return PurchaseOrdersSummary(
      byStatus: counts(j['byStatus']),
      open: _int(j['open']),
      overdue: _int(j['overdue']),
      overdueByStatus: counts(j['overdueByStatus']),
    );
  }
}

/// One history entry (append only).
class PurchaseOrderEvent {
  const PurchaseOrderEvent({
    required this.eventId,
    required this.type,
    required this.fromStatus,
    required this.toStatus,
    required this.user,
    required this.details,
    required this.createdAt,
  });

  final int eventId;
  final String type;
  final String? fromStatus;
  final String? toStatus;
  final PurchasePerson? user;
  final Map<String, dynamic> details;
  final String? createdAt;

  factory PurchaseOrderEvent.fromJson(Map<String, dynamic> j) =>
      PurchaseOrderEvent(
        eventId: _int(j['eventId']),
        type: j['type'] as String? ?? '',
        fromStatus: j['fromStatus'] as String?,
        toStatus: j['toStatus'] as String?,
        user: PurchasePerson.fromJson(j['user']),
        details: (j['details'] as Map<String, dynamic>?) ?? const {},
        createdAt: j['createdAt'] as String?,
      );
}

/// GET …/message-preview: computed now, nothing saved.
class MessagePreview {
  const MessagePreview({
    required this.orderId,
    required this.version,
    required this.language,
    required this.text,
    required this.supplierName,
    required this.whatsappNumber,
    required this.length,
    required this.previewToken,
  });

  final int orderId;
  final int version;
  final String language;
  final String text;
  final String supplierName;
  final String whatsappNumber;
  final MessageLength length;
  final String previewToken;

  factory MessagePreview.fromJson(Map<String, dynamic> j) => MessagePreview(
    orderId: _int(j['orderId']),
    version: _int(j['version']),
    language: j['language'] as String? ?? 'ar',
    text: j['text'] as String? ?? '',
    supplierName: j['supplierName'] as String? ?? '',
    whatsappNumber: j['whatsappNumber'] as String? ?? '',
    length: MessageLength.fromJson(
      (j['length'] as Map<String, dynamic>?) ?? const {},
    ),
    previewToken: j['previewToken'] as String? ?? '',
  );
}

/// The answer to a client report (copy, opening request, opening result).
class ClientActionResult {
  const ClientActionResult({
    required this.eventId,
    required this.duplicate,
    required this.whatsappUrl,
    required this.whatsappNumber,
  });

  final int eventId;
  final bool duplicate;

  /// Only for a confirmed opening request of a non-final order.
  final String? whatsappUrl;
  final String? whatsappNumber;

  factory ClientActionResult.fromJson(Map<String, dynamic> j) =>
      ClientActionResult(
        eventId: _int(j['eventId']),
        duplicate: j['duplicate'] as bool? ?? false,
        whatsappUrl: j['whatsappUrl'] as String?,
        whatsappNumber: j['whatsappNumber'] as String?,
      );
}

// ── recommendations and candidates ─────────────────────────────────────────

class RecommendationItem {
  const RecommendationItem({
    required this.productId,
    required this.name,
    required this.barcode,
    required this.quantity,
    required this.minStock,
    required this.classification,
    required this.basis,
    required this.suggestedQuantity,
    required this.targetStock,
    required this.observedDays,
    required this.netUnits,
    required this.unitsPerDay,
    required this.daysLeft,
    required this.expectedStockout,
    required this.expectedUnitCost,
    required this.available,
    required this.unavailableReason,
    required this.openOrder,
    required this.cautions,
    required this.explanation,
  });

  final int productId;
  final String name;
  final String barcode;
  final int quantity;
  final int minStock;
  final String classification;
  final String basis;
  final int? suggestedQuantity;
  final int? targetStock;
  final int observedDays;
  final int netUnits;
  final double? unitsPerDay;
  final double? daysLeft;
  final String? expectedStockout;
  final double? expectedUnitCost;
  final bool available;
  final String? unavailableReason;
  final OpenOrderRef? openOrder;
  final List<PurchaseCaution> cautions;
  final String explanation;

  factory RecommendationItem.fromJson(Map<String, dynamic> j) =>
      RecommendationItem(
        productId: _int(j['productId']),
        name: j['name'] as String? ?? '',
        barcode: j['barcode'] as String? ?? '',
        quantity: _int(j['quantity']),
        minStock: _int(j['minStock']),
        classification: j['classification'] as String? ?? '',
        basis: j['basis'] as String? ?? 'MANUAL',
        suggestedQuantity: _intOrNull(j['suggestedQuantity']),
        targetStock: _intOrNull(j['targetStock']),
        observedDays: _int(j['observedDays']),
        netUnits: _int(j['netUnits']),
        unitsPerDay: _double(j['unitsPerDay']),
        daysLeft: _double(j['daysLeft']),
        expectedStockout: j['expectedStockout'] as String?,
        expectedUnitCost: _double(j['expectedUnitCost']),
        available: j['available'] as bool? ?? false,
        unavailableReason: j['unavailableReason'] as String?,
        openOrder: OpenOrderRef.fromJson(j['openOrder']),
        cautions: [
          for (final c in _list(j['cautions'])) PurchaseCaution.fromJson(c),
        ],
        explanation: j['explanation'] as String? ?? '',
      );
}

class SupplierRecommendations {
  const SupplierRecommendations(
    this.supplierId,
    this.supplierName,
    this.active,
    this.hasWhatsapp,
    this.items,
  );

  final int supplierId;
  final String supplierName;
  final bool active;
  final bool hasWhatsapp;
  final List<RecommendationItem> items;

  factory SupplierRecommendations.fromJson(Map<String, dynamic> j) =>
      SupplierRecommendations(
        _int(j['supplierId']),
        j['supplierName'] as String? ?? '',
        j['active'] as bool? ?? false,
        j['hasWhatsapp'] as bool? ?? false,
        [for (final i in _list(j['items'])) RecommendationItem.fromJson(i)],
      );
}

/// GET /api/purchase-recommendations. engine is always "RULES".
class PurchaseRecommendations {
  const PurchaseRecommendations({
    required this.guardianEnabled,
    required this.engine,
    required this.generatedAt,
    required this.horizonDays,
    required this.urgentDays,
    required this.coverDays,
    required this.suppliers,
    required this.inconsistent,
  });

  final bool guardianEnabled;
  final String engine;
  final String? generatedAt;
  final int horizonDays;
  final int urgentDays;
  final int coverDays;
  final List<SupplierRecommendations> suppliers;
  final List<RecommendationItem> inconsistent;

  factory PurchaseRecommendations.fromJson(Map<String, dynamic> j) =>
      PurchaseRecommendations(
        guardianEnabled: j['guardianEnabled'] as bool? ?? false,
        engine: j['engine'] as String? ?? 'RULES',
        generatedAt: j['generatedAt'] as String?,
        horizonDays: _int(j['horizonDays']),
        urgentDays: _int(j['urgentDays']),
        coverDays: _int(j['coverDays']),
        suppliers: [
          for (final s in _list(j['suppliers']))
            SupplierRecommendations.fromJson(s),
        ],
        inconsistent: [
          for (final i in _list(j['inconsistent']))
            RecommendationItem.fromJson(i),
        ],
      );
}

/// A product of the supplier for a manual order: facts only.
class PurchaseCandidate {
  const PurchaseCandidate({
    required this.productId,
    required this.name,
    required this.barcode,
    required this.quantity,
    required this.minStock,
    required this.stockStatus,
    required this.expectedUnitCost,
    required this.available,
    required this.unavailableReason,
    required this.openOrder,
    required this.cautions,
  });

  final int productId;
  final String name;
  final String barcode;
  final int quantity;
  final int minStock;
  final String stockStatus;
  final double? expectedUnitCost;
  final bool available;
  final String? unavailableReason;
  final OpenOrderRef? openOrder;
  final List<PurchaseCaution> cautions;

  factory PurchaseCandidate.fromJson(Map<String, dynamic> j) =>
      PurchaseCandidate(
        productId: _int(j['productId']),
        name: j['name'] as String? ?? '',
        barcode: j['barcode'] as String? ?? '',
        quantity: _int(j['quantity']),
        minStock: _int(j['minStock']),
        stockStatus: j['stockStatus'] as String? ?? 'OK',
        expectedUnitCost: _double(j['expectedUnitCost']),
        available: j['available'] as bool? ?? false,
        unavailableReason: j['unavailableReason'] as String?,
        openOrder: OpenOrderRef.fromJson(j['openOrder']),
        cautions: [
          for (final c in _list(j['cautions'])) PurchaseCaution.fromJson(c),
        ],
      );
}

class PurchaseCandidates {
  const PurchaseCandidates(
    this.supplierId,
    this.supplierName,
    this.active,
    this.hasWhatsapp,
    this.products,
  );

  final int supplierId;
  final String supplierName;
  final bool active;
  final bool hasWhatsapp;
  final List<PurchaseCandidate> products;

  factory PurchaseCandidates.fromJson(Map<String, dynamic> j) =>
      PurchaseCandidates(
        _int(j['supplierId']),
        j['supplierName'] as String? ?? '',
        j['active'] as bool? ?? false,
        j['hasWhatsapp'] as bool? ?? false,
        [for (final p in _list(j['products'])) PurchaseCandidate.fromJson(p)],
      );
}
