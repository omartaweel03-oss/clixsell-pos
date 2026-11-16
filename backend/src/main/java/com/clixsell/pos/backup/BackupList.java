package com.clixsell.pos.backup;

import java.util.List;

/** The backup folder on the server (for the manual restore steps) and its backups, newest first. */
public record BackupList(String folder, List<BackupInfo> backups) {
}
