package com.clixsell.pos.backup;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Database backups – System Admin only (the whole /api/admin/** path is
 * SYSTEM_ADMIN in SecurityConfig). The database holds every store, so Store
 * Admins and Cashiers never reach it.
 * <pre>
 * GET  /api/admin/backups   the backup folder and its backups (name, size, date)
 * POST /api/admin/backups   create a backup now, checked with PRAGMA integrity_check
 * </pre>
 * There is no download, delete or restore through the API.
 */
@RestController
@RequestMapping("/api/admin/backups")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    @GetMapping
    public BackupList list() {
        return backups.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BackupInfo create() {
        return backups.create();
    }
}
