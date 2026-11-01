package com.clixsell.pos.common;

import java.util.Map;

/**
 * JSON body of every error response, for example:
 * <pre>
 * { "status": 409, "code": "DUPLICATE_BARCODE",
 *   "message": "Another product already uses barcode 12345678",
 *   "fieldErrors": { "barcode": "Another product already uses barcode 12345678" } }
 * </pre>
 */
public record ApiError(int status, String code, String message, Map<String, String> fieldErrors) {
}
