/// One database backup file (Settings → Backup, System Admin only).
class BackupInfo {
  const BackupInfo({
    required this.fileName,
    required this.sizeBytes,
    required this.createdAt,
    this.integrityCheck,
    this.rowCounts = const {},
  });

  factory BackupInfo.fromJson(Map<String, dynamic> json) => BackupInfo(
    fileName: json['fileName'] as String,
    sizeBytes: (json['sizeBytes'] as num).toInt(),
    createdAt: DateTime.parse(json['createdAt'] as String),
    integrityCheck: json['integrityCheck'] as String?,
    rowCounts: {
      for (final e
          in ((json['rowCounts'] as Map<String, dynamic>?) ?? const {}).entries)
        e.key: (e.value as num).toInt(),
    },
  );

  final String fileName;
  final int sizeBytes;
  final DateTime createdAt;

  /// "ok" when the new backup passed SQLite's integrity check (only right
  /// after it was created).
  final String? integrityCheck;

  /// Rows in the backup per table (only right after it was created).
  final Map<String, int> rowCounts;
}

/// The backup folder on the server and its backups, newest first.
class BackupList {
  const BackupList({required this.folder, required this.backups});

  factory BackupList.fromJson(Map<String, dynamic> json) => BackupList(
    folder: json['folder'] as String,
    backups: [
      for (final b in (json['backups'] as List<dynamic>))
        BackupInfo.fromJson(b as Map<String, dynamic>),
    ],
  );

  final String folder;
  final List<BackupInfo> backups;
}
