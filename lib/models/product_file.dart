/// Product import / export (UR-M11, SR24, Figure 3.3): what the backend
/// returns for a checked CSV file and for a finished import.
library;

int _int(Object? v) => (v as num?)?.toInt() ?? 0;

List<String> _strings(Object? v) => [
  for (final x in (v as List<dynamic>? ?? const [])) x.toString(),
];

Map<String, String> _stringMap(Object? v) => {
  for (final e in (v as Map<dynamic, dynamic>? ?? const {}).entries)
    e.key.toString(): e.value?.toString() ?? '',
};

/// One row of the file as read by the server, with its problems.
class ImportRow {
  const ImportRow({
    required this.line,
    required this.values,
    required this.supplierStatus,
    required this.errors,
  });

  /// Line in the file where the row starts (the header is line 1).
  final int line;

  /// Column → text as read (name, barcode, category, sellingPrice, costPrice,
  /// quantity, minStock, supplier).
  final Map<String, String> values;

  /// EXISTING, NEW (would be created) or INACTIVE (refused); null if unknown.
  final String? supplierStatus;

  /// Field (or "row") → error code / message. Empty = the row is valid.
  final Map<String, String> errors;

  bool get isValid => errors.isEmpty;

  factory ImportRow.fromJson(Map<String, dynamic> j) => ImportRow(
    line: _int(j['line']),
    values: _stringMap(j['values']),
    supplierStatus: j['supplierStatus'] as String?,
    errors: _stringMap(j['errors']),
  );
}

/// The server's check of a whole file (nothing saved yet).
class ImportPreview {
  const ImportPreview({
    required this.fileName,
    required this.fileHash,
    required this.totalRows,
    required this.validRows,
    required this.invalidRows,
    required this.fileErrors,
    required this.newSuppliers,
    required this.rows,
    required this.canImport,
    required this.maxRows,
    required this.maxBytes,
  });

  final String fileName;

  /// SHA-256 of the checked file; the import must send it back.
  final String fileHash;
  final int totalRows;
  final int validRows;
  final int invalidRows;

  /// Problems of the whole file (e.g. MISSING_COLUMN:barcode, NOT_UTF8).
  final List<String> fileErrors;

  /// Suppliers the import would create (by name, as on the product form).
  final List<String> newSuppliers;
  final List<ImportRow> rows;
  final bool canImport;
  final int maxRows;
  final int maxBytes;

  factory ImportPreview.fromJson(Map<String, dynamic> j) => ImportPreview(
    fileName: j['fileName'] as String? ?? '',
    fileHash: j['fileHash'] as String? ?? '',
    totalRows: _int(j['totalRows']),
    validRows: _int(j['validRows']),
    invalidRows: _int(j['invalidRows']),
    fileErrors: _strings(j['fileErrors']),
    newSuppliers: _strings(j['newSuppliers']),
    rows: [
      for (final r in (j['rows'] as List<dynamic>? ?? const []))
        ImportRow.fromJson(r as Map<String, dynamic>),
    ],
    canImport: j['canImport'] == true,
    maxRows: _int(j['maxRows']),
    maxBytes: _int(j['maxBytes']),
  );
}

/// A finished import: every product was created in one transaction.
class ImportResult {
  const ImportResult({required this.created, required this.suppliersCreated});

  final int created;
  final List<String> suppliersCreated;

  factory ImportResult.fromJson(Map<String, dynamic> j) => ImportResult(
    created: _int(j['created']),
    suppliersCreated: _strings(j['suppliersCreated']),
  );
}

/// An exported CSV file.
class ProductExport {
  const ProductExport({
    required this.fileName,
    required this.bytes,
    required this.count,
  });

  final String fileName;
  final List<int> bytes;

  /// Number of products in the file (null if the server did not say).
  final int? count;
}
