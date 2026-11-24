import 'package:clixsell_pos/models/purchase_order.dart';
import 'package:clixsell_pos/models/supplier.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/purchase_api_client.dart';
import 'package:clixsell_pos/services/supplier_api_client.dart';
import 'package:clixsell_pos/services/url_opener.dart';

/// Builds backend-shaped JSON for an order (the same fields the real API sends).
Map<String, dynamic> orderJson({
  int id = 1,
  String number = 'PO-000001',
  String status = 'PENDING_APPROVAL',
  int version = 0,
  List<String>? actions,
  List<String> blockers = const [],
  Map<String, dynamic>? message,
  Map<String, dynamic>? communication,
  Map<String, dynamic>? messageLength,
  bool overdue = false,
  String source = 'MANUAL',
}) => {
  'orderId': id,
  'orderNumber': number,
  'status': status,
  'source': source,
  'supplierId': 7,
  'supplierName': 'Dairy Co',
  'supplierActive': true,
  'note': 'internal note',
  'version': version,
  'createdBy': {'userId': 1, 'fullName': 'Ahmad'},
  'createdAt': '2026-11-24T10:00:00',
  'overdue': overdue,
  'expectedTotalCost': 47.5,
  'allowedActions':
      actions ??
      switch (status) {
        'PENDING_APPROVAL' => ['EDIT', 'APPROVE', 'REJECT', 'CANCEL'],
        'APPROVED' => ['PREVIEW_MESSAGE', 'GENERATE_MESSAGE', 'CANCEL'],
        'MESSAGE_GENERATED' => [
          'VIEW_MESSAGE',
          'COPY_MESSAGE',
          'REQUEST_WHATSAPP_OPEN',
          'MARK_SENT',
          'CANCEL',
        ],
        'SENT' => [
          'VIEW_MESSAGE',
          'COPY_MESSAGE',
          'REQUEST_WHATSAPP_OPEN',
          'RECORD_SUPPLIER_CONFIRMATION',
          'CANCEL_AFTER_CONTACT',
        ],
        'CONFIRMED' => [
          'VIEW_MESSAGE',
          'COPY_MESSAGE',
          'REQUEST_WHATSAPP_OPEN',
          'CLOSE',
          'CANCEL_AFTER_CONTACT',
        ],
        'CLOSED' => ['VIEW_MESSAGE'],
        _ => <String>[],
      },
  'approvalBlockers': blockers,
  'items': [
    {
      'itemId': 11,
      'productId': 101,
      'productDeleted': false,
      'productName': 'Labneh',
      'barcode': '7290000000017',
      'quantity': 5,
      'suggestedQuantity': null,
      'basis': 'MANUAL',
      'expectedUnitCost': 9.5,
      'reserved': true,
      'calculation': {'basis': 'MANUAL'},
    },
  ],
  'replayed': false,
  'message': ?message,
  'communication':
      communication ??
      {
        'copyReports': 0,
        'openRequests': 0,
        'clientOpenReports': 0,
        'sentRecordedByManager': status == 'SENT' || status == 'CONFIRMED',
        'sentMarkedAt': status == 'SENT' || status == 'CONFIRMED'
            ? '2026-11-24T11:00:00'
            : null,
        'canceledAfterContact': false,
        'possibleSupplierContact': false,
        'supplierCancellationRecorded': false,
        'supplierCancellationRecordable': false,
      },
  'messageLength': ?messageLength,
};

Map<String, dynamic> messageJson({
  String number = '970599000001',
  bool changed = false,
  bool actionable = true,
  String text = 'طلب شراء رقم: PO-000001\n1) Labneh | الكمية: 5',
}) => {
  'text': text,
  'language': 'ar',
  'whatsappNumber': number,
  'length': text.runes.length,
  'maxLength': 2000,
  'generatedBy': {'userId': 1, 'fullName': 'Ahmad'},
  'generatedAt': '2026-11-24T10:30:00',
  'actionable': actionable,
  'numberChangedSinceGeneration': changed,
  'currentSupplierWhatsapp': changed ? '970599000002' : number,
};

/// An in-memory PurchaseApi for widget tests: answers from [order], records
/// every call, and can be told to fail the next call of a kind.
class FakePurchaseApi implements PurchaseApi {
  FakePurchaseApi({Map<String, dynamic>? order}) : order = order ?? orderJson();

  Map<String, dynamic> order;
  final List<String> calls = [];
  final List<Map<String, dynamic>> bodies = [];

  /// Next failure for a call name (e.g. 'approve' → ORDER_CHANGED).
  final Map<String, ApiException> failNext = {};

  /// Applied to [order] when a call "succeeds on the server but the answer is lost".
  final Map<String, void Function()> lostAnswer = {};

  List<Map<String, dynamic>> listRows = [];
  int listTotal = 0;
  final List<Map<String, dynamic>> listQueries = [];
  Map<String, dynamic> recommendationsJson = {
    'guardianEnabled': false,
    'engine': 'RULES',
    'suppliers': [],
    'inconsistent': [],
  };
  Map<String, dynamic> candidatesJson = {
    'supplierId': 7,
    'supplierName': 'Dairy Co',
    'active': true,
    'hasWhatsapp': true,
    'products': [],
  };
  List<Map<String, dynamic>> eventsJson = [];

  Future<T> _do<T>(
    String name,
    Map<String, dynamic> body,
    T Function() ok,
  ) async {
    calls.add(name);
    bodies.add(body);
    final lost = lostAnswer.remove(name);
    if (lost != null) {
      lost();
      throw const ApiException.network('The server did not respond in time');
    }
    final f = failNext.remove(name);
    if (f != null) throw f;
    return ok();
  }

  PurchaseOrder _o() => PurchaseOrder.fromJson(order);

  void _set(String status, {Map<String, dynamic>? extra}) {
    order = {
      ...order,
      'status': status,
      'version': (order['version'] as int) + 1,
      ...?extra,
    };
    order = orderJson(
      status: status,
      version: order['version'] as int,
      message: order['message'] as Map<String, dynamic>?,
      communication: extra?['communication'] as Map<String, dynamic>?,
    );
  }

  @override
  Future<PurchaseOrderPage> list({
    List<PurchaseOrderStatus> statuses = const [],
    String? query,
    int page = 0,
    int size = 20,
  }) => _do(
    'list',
    {
      'statuses': statuses.map((s) => s.code).join(','),
      'q': query,
      'page': page,
      'size': size,
    },
    () {
      listQueries.add({
        'statuses': statuses.map((s) => s.code).join(','),
        'q': query,
        'page': page,
        'size': size,
      });
      final rows = size == 100
          ? listRows
                .where((r) => statuses.any((s) => s.code == r['status']))
                .toList()
          : listRows;
      return PurchaseOrderPage.fromJson({
        'page': page,
        'size': size,
        'total': size == 100 ? rows.length : listTotal,
        'orders': rows,
      });
    },
  );

  @override
  Future<PurchaseOrder> get(int orderId) => _do('get', {'id': orderId}, _o);

  @override
  Future<List<PurchaseOrderEvent>> events(int orderId) => _do(
    'events',
    {},
    () => [for (final e in eventsJson) PurchaseOrderEvent.fromJson(e)],
  );

  @override
  Future<PurchaseRecommendations> recommendations() => _do(
    'recommendations',
    {},
    () => PurchaseRecommendations.fromJson(recommendationsJson),
  );

  @override
  Future<PurchaseCandidates> candidates(int supplierId) => _do('candidates', {
    'supplierId': supplierId,
  }, () => PurchaseCandidates.fromJson(candidatesJson));

  @override
  Future<PurchaseOrder> create(CreateOrderRequest request) =>
      _do('create', request.toJson(), () {
        order = orderJson(id: 5, number: 'PO-000005');
        return _o();
      });

  @override
  Future<PurchaseOrder> edit(
    int orderId, {
    required int version,
    required String? note,
    required List<OrderLine> lines,
    required List<CautionAck> acks,
  }) => _do(
    'edit',
    {
      'version': version,
      'note': note,
      'items': [for (final l in lines) l.toJson()],
    },
    () {
      _set('PENDING_APPROVAL');
      return _o();
    },
  );

  @override
  Future<PurchaseOrder> approve(int orderId, int version) =>
      _do('approve', {'version': version}, () {
        _set('APPROVED');
        return _o();
      });

  @override
  Future<PurchaseOrder> reject(int orderId, int version, String reason) =>
      _do('reject', {'version': version, 'reason': reason}, () {
        _set('REJECTED');
        return _o();
      });

  @override
  Future<PurchaseOrder> cancel(
    int orderId, {
    required int version,
    required String reason,
    ContactStatus? contactStatus,
    bool? confirmNotSent,
    bool? acknowledgeSupplierNotNotified,
  }) => _do(
    'cancel',
    {
      'version': version,
      'reason': reason,
      'contactStatus': contactStatus?.code,
      'confirmNotSent': confirmNotSent,
      'acknowledgeSupplierNotNotified': acknowledgeSupplierNotNotified,
    },
    () {
      _set('CANCELED');
      return _o();
    },
  );

  @override
  Future<MessagePreview> preview(int orderId, String language) => _do(
    'preview',
    {'language': language},
    () => MessagePreview.fromJson({
      'orderId': orderId,
      'version': order['version'],
      'language': language,
      'text': language == 'ar'
          ? 'طلب شراء رقم: PO-000001'
          : 'Purchase order: PO-000001',
      'supplierName': 'Dairy Co',
      'whatsappNumber': '970599000001',
      'length': {
        'language': language,
        'length': 25,
        'maxPossibleLength': 100,
        'limit': 2000,
        'fits': true,
        'alwaysFits': true,
        'longestLines': [],
        'minLinesToRemove': 0,
      },
      'previewToken': 'a' * 64,
    }),
  );

  @override
  Future<PurchaseOrder> generateMessage(
    int orderId, {
    required int version,
    required String language,
    required String previewToken,
  }) => _do(
    'generate',
    {'version': version, 'language': language, 'previewToken': previewToken},
    () {
      order = orderJson(
        status: 'MESSAGE_GENERATED',
        version: version + 1,
        message: messageJson(),
      );
      return _o();
    },
  );

  @override
  Future<ClientActionResult> messageCopied(
    int orderId,
    String clientActionId,
  ) => _do('copied', {
    'clientActionId': clientActionId,
  }, () => ClientActionResult.fromJson({'eventId': 1, 'duplicate': false}));

  @override
  Future<ClientActionResult> openRequested(
    int orderId,
    String clientActionId,
  ) => _do(
    'openRequested',
    {'clientActionId': clientActionId},
    () => ClientActionResult.fromJson({
      'eventId': 2,
      'duplicate': false,
      'whatsappUrl':
          'https://wa.me/${(order['message'] as Map)['whatsappNumber']}?text=x',
      'whatsappNumber': '970599000001',
    }),
  );

  @override
  Future<ClientActionResult> openResult(
    int orderId,
    String clientActionId,
    bool opened,
  ) => _do('openResult', {
    'clientActionId': clientActionId,
    'opened': opened,
  }, () => ClientActionResult.fromJson({'eventId': 3}));

  @override
  Future<PurchaseOrder> markSent(int orderId, int version) =>
      _do('markSent', {'version': version}, () {
        _set('SENT');
        return _o();
      });

  @override
  Future<PurchaseOrder> supplierConfirmation(
    int orderId,
    int version,
    String? note,
  ) => _do('confirm', {'version': version, 'note': note}, () {
    _set('CONFIRMED');
    return _o();
  });

  @override
  Future<PurchaseOrder> close(int orderId, int version) =>
      _do('close', {'version': version}, () {
        _set('CLOSED');
        return _o();
      });

  @override
  Future<PurchaseOrder> supplierCancellation(
    int orderId, {
    required String channel,
    required String note,
  }) => _do('supplierCancellation', {'channel': channel, 'note': note}, _o);

  int count(String name) => calls.where((c) => c == name).length;
}

class FakeSupplierApi implements SupplierApi {
  @override
  Future<List<Supplier>> list() async => const [
    Supplier(
      supplierId: 7,
      name: 'Dairy Co',
      whatsappNumber: '970599000001',
      active: true,
      productCount: 2,
    ),
    Supplier(
      supplierId: 8,
      name: 'Old Supplier',
      active: false,
      productCount: 0,
    ),
  ];

  @override
  Future<Supplier> get(int supplierId) => throw UnimplementedError();
  @override
  Future<Supplier> create({
    required String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  }) => throw UnimplementedError();
  @override
  Future<Supplier> update(
    int supplierId, {
    required String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  }) => throw UnimplementedError();
  @override
  Future<Supplier> setActive(int supplierId, bool active) =>
      throw UnimplementedError();
}

/// Records the links it was asked to open; never opens anything.
class FakeUrlOpener implements UrlOpener {
  FakeUrlOpener({this.result = true});

  bool result;
  final List<String> opened = [];

  @override
  Future<bool> open(String url) async {
    opened.add(url);
    return result;
  }
}

class FakeClipboard implements ClipboardWriter {
  FakeClipboard({this.ok = true});

  bool ok;
  final List<String> copied = [];

  @override
  Future<bool> copy(String text) async {
    if (ok) copied.add(text);
    return ok;
  }
}

const orderChanged = ApiException(
  statusCode: 409,
  code: 'ORDER_CHANGED',
  message: 'changed',
);
const databaseBusy = ApiException(
  statusCode: 409,
  code: 'DATABASE_BUSY',
  message: 'busy',
);
