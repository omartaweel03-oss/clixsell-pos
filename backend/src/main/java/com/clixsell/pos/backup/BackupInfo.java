package com.clixsell.pos.backup;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One backup file. {@code integrityCheck} and {@code rowCounts} are filled only
 * right after the backup is created (they come from opening the new file).
 */
public record BackupInfo(String fileName, long sizeBytes, LocalDateTime createdAt,
                         String integrityCheck, Map<String, Long> rowCounts) {
}
