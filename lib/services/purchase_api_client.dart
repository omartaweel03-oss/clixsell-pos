import 'dart:math';

import '../models/purchase_order.dart';
import 'api_http.dart';

/// A random UUID (version 4) for idempotency keys: clientRequestId (one per
/// logical new order) and clientActionId (one per copy / opening action).
String newClientId([Random? random]) {
  final r = random ?? Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-'
      '${h.substring(16, 20)}-${h.substring(20)}';
}

/// One line of an order request.
class OrderLine {
  const OrderLine(this.productId, this.quantity);

  final int productId;
  final int quantity;

  Map<String, dynamic> toJson() => {
    'productId': productId,
    'quantity': quantity,
  };
}

/// The acknowledgment of one reorder caution: (product, recent order).
class CautionAck {
  const CautionAck(this.productId, this.orderId);

  final int productId;
  final int orderId;

  Map<String, dynamic> toJson() => {'productId': productId, 'orderId': orderId};

  @override
  bool operator ==(Object other) =>
      other is CautionAck &&
      other.productId == productId &&
      other.orderId == orderId;

  @override
  int get hashCode => Object.hash(productId, orderId);
}

/// A create request, kept unchanged (with its clientRequestId) until its outcome
/// is known: resending exactly this body is always safe (the server replays it).
class CreateOrderRequest {
  const CreateOrderRequest({
    required this.clientRequestId,
    required this.supplierId,
    required this.source,
    required this.note,
    required this.lines,
    required this.acks,
  });

  final String clientRequestId;
  final int supplierId;
  final String source; // MANUAL or GUARDIAN
  final String? note;
  final List<OrderLine> lines;
  final List<CautionAck> acks;

  Map<String, dynamic> toJson() => {
    'clientRequestId': clientRequestId,
    'supplierId': supplierId,
    'source': source,
    'note': note,
    'items': [for (final l in lines) l.toJson()],
    'acknowledgedCautions': [for (final a in acks) a.toJson()],
  };
}

/// Contact status when cancelling an order whose message was prepared.
enum ContactStatus { notSent, unknown, sent }

extension ContactStatusCode on ContactStatus {
  String get code => switch (this) {
    ContactStatus.notSent => 'NOT_SENT',
    ContactStatus.unknown => 'UNKNOWN',
    ContactStatus.sent => 'SENT',
  };
}

/// Purchasing (Store Admin): /api/purchase-*. The store always comes from the
/// login on the server; no store id is ever sent. Only the fields each
/// endpoint accepts are sent (the server refuses unknown fields).
abstract class PurchaseApi {
  /// [overdueOnly]: only overdue orders (the server's rule over ALL the
  /// store's orders, then paged).
  Future<PurchaseOrderPage> list({
    List<PurchaseOrderStatus> statuses = const [],
    String? query,
    bool overdueOnly = false,
    int page = 0,
    int size = 20,
  });

  /// D1: exact counts by status and overdue (GET /purchase-orders/summary).
  Future<PurchaseOrdersSummary> summary();

  Future<PurchaseOrder> get(int orderId);

  Future<List<PurchaseOrderEvent>> events(int orderId);

  Future<PurchaseRecommendations> recommendations();

  Future<PurchaseCandidates> candidates(int supplierId);

  Future<PurchaseOrder> create(CreateOrderRequest request);

  Future<PurchaseOrder> edit(
    int orderId, {
    required int version,
    required String? note,
    required List<OrderLine> lines,
    required List<CautionAck> acks,
  });

  Future<PurchaseOrder> approve(int orderId, int version);

  Future<PurchaseOrder> reject(int orderId, int version, String reason);

  Future<PurchaseOrder> cancel(
    int orderId, {
    required int version,
    required String reason,
    ContactStatus? contactStatus,
    bool? confirmNotSent,
    bool? acknowledgeSupplierNotNotified,
  });

  Future<MessagePreview> preview(int orderId, String language);

  Future<PurchaseOrder> generateMessage(
    int orderId, {
    required int version,
    required String language,
    required String previewToken,
  });

  Future<ClientActionResult> messageCopied(int orderId, String clientActionId);

  Future<ClientActionResult> openRequested(int orderId, String clientActionId);

  Future<ClientActionResult> openResult(
    int orderId,
    String clientActionId,
    bool opened,
  );

  Future<PurchaseOrder> markSent(int orderId, int version);

  Future<PurchaseOrder> supplierConfirmation(
    int orderId,
    int version,
    String? note,
  );

  Future<PurchaseOrder> close(int orderId, int version);

  Future<PurchaseOrder> supplierCancellation(
    int orderId, {
    required String channel,
    required String note,
  });
}

class PurchaseApiClient implements PurchaseApi {
  PurchaseApiClient(this._http);

  final ApiHttp _http;

  ApiHttp get http => _http;

  static const _base = '/purchase-orders';

  Future<PurchaseOrder> _order(Future<dynamic> f) async =>
      PurchaseOrder.fromJson(await f as Map<String, dynamic>);

  @override
  Future<PurchaseOrderPage> list({
    List<PurchaseOrderStatus> statuses = const [],
    String? query,
    bool overdueOnly = false,
    int page = 0,
    int size = 20,
  }) async {
    final params = <String>[
      if (statuses.isNotEmpty)
        'status=${statuses.map((s) => s.code).join(',')}',
      if (query != null && query.trim().isNotEmpty)
        'q=${Uri.encodeQueryComponent(query.trim())}',
      if (overdueOnly) 'overdue=true',
      'page=$page',
      'size=$size',
    ];
    return PurchaseOrderPage.fromJson(
      await _http.get('$_base?${params.join('&')}') as Map<String, dynamic>,
    );
  }

  @override
  Future<PurchaseOrdersSummary> summary() async =>
      PurchaseOrdersSummary.fromJson(
        await _http.get('$_base/summary') as Map<String, dynamic>,
      );

  @override
  Future<PurchaseOrder> get(int orderId) =>
      _order(_http.get('$_base/$orderId'));

  @override
  Future<List<PurchaseOrderEvent>> events(int orderId) async => [
    for (final e
        in (await _http.get('$_base/$orderId/events') as List<dynamic>))
      PurchaseOrderEvent.fromJson(e as Map<String, dynamic>),
  ];

  @override
  Future<PurchaseRecommendations> recommendations() async =>
      PurchaseRecommendations.fromJson(
        await _http.get('/purchase-recommendations') as Map<String, dynamic>,
      );

  @override
  Future<PurchaseCandidates> candidates(int supplierId) async =>
      PurchaseCandidates.fromJson(
        await _http.get('/purchase-candidates?supplierId=$supplierId')
            as Map<String, dynamic>,
      );

  @override
  Future<PurchaseOrder> create(CreateOrderRequest request) =>
      _order(_http.post(_base, request.toJson()));

  @override
  Future<PurchaseOrder> edit(
    int orderId, {
    required int version,
    required String? note,
    required List<OrderLine> lines,
    required List<CautionAck> acks,
  }) => _order(
    _http.put('$_base/$orderId', {
      'version': version,
      'note': note,
      'items': [for (final l in lines) l.toJson()],
      'acknowledgedCautions': [for (final a in acks) a.toJson()],
    }),
  );

  @override
  Future<PurchaseOrder> approve(int orderId, int version) =>
      _order(_http.post('$_base/$orderId/approve', {'version': version}));

  @override
  Future<PurchaseOrder> reject(int orderId, int version, String reason) =>
      _order(
        _http.post('$_base/$orderId/reject', {
          'version': version,
          'reason': reason,
        }),
      );

  @override
  Future<PurchaseOrder> cancel(
    int orderId, {
    required int version,
    required String reason,
    ContactStatus? contactStatus,
    bool? confirmNotSent,
    bool? acknowledgeSupplierNotNotified,
  }) => _order(
    _http.post('$_base/$orderId/cancel', {
      'version': version,
      'reason': reason,
      if (contactStatus != null) 'contactStatus': contactStatus.code,
      'confirmNotSent': ?confirmNotSent,
      'acknowledgeSupplierNotNotified': ?acknowledgeSupplierNotNotified,
    }),
  );

  @override
  Future<MessagePreview> preview(int orderId, String language) async =>
      MessagePreview.fromJson(
        await _http.get('$_base/$orderId/message-preview?language=$language')
            as Map<String, dynamic>,
      );

  @override
  Future<PurchaseOrder> generateMessage(
    int orderId, {
    required int version,
    required String language,
    required String previewToken,
  }) => _order(
    _http.post('$_base/$orderId/message', {
      'version': version,
      'language': language,
      'previewToken': previewToken,
    }),
  );

  Future<ClientActionResult> _action(Future<dynamic> f) async =>
      ClientActionResult.fromJson(await f as Map<String, dynamic>);

  @override
  Future<ClientActionResult> messageCopied(
    int orderId,
    String clientActionId,
  ) => _action(
    _http.post('$_base/$orderId/message-copied', {
      'clientActionId': clientActionId,
    }),
  );

  @override
  Future<ClientActionResult> openRequested(
    int orderId,
    String clientActionId,
  ) => _action(
    _http.post('$_base/$orderId/whatsapp-open-requested', {
      'clientActionId': clientActionId,
      'confirmed': true,
    }),
  );

  @override
  Future<ClientActionResult> openResult(
    int orderId,
    String clientActionId,
    bool opened,
  ) => _action(
    _http.post('$_base/$orderId/whatsapp-open-result', {
      'clientActionId': clientActionId,
      'result': opened ? 'OPENED' : 'FAILED',
    }),
  );

  @override
  Future<PurchaseOrder> markSent(int orderId, int version) => _order(
    _http.post('$_base/$orderId/mark-sent', {
      'version': version,
      'confirmSentInWhatsapp': true,
    }),
  );

  @override
  Future<PurchaseOrder> supplierConfirmation(
    int orderId,
    int version,
    String? note,
  ) => _order(
    _http.post('$_base/$orderId/supplier-confirmation', {
      'version': version,
      'confirmSupplierConfirmed': true,
      if (note != null && note.trim().isNotEmpty) 'note': note.trim(),
    }),
  );

  @override
  Future<PurchaseOrder> close(int orderId, int version) => _order(
    _http.post('$_base/$orderId/close', {
      'version': version,
      'confirmNoReceiving': true,
    }),
  );

  @override
  Future<PurchaseOrder> supplierCancellation(
    int orderId, {
    required String channel,
    required String note,
  }) => _order(
    _http.post('$_base/$orderId/supplier-cancellation-recorded', {
      'confirmSupplierAccepted': true,
      'channel': channel,
      'note': note,
    }),
  );
}
