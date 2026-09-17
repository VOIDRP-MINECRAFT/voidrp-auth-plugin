package ru.voidrp.auth.model;

import java.util.List;

/**
 * Outcome of one backend call, in the shape the dialogs need: either it worked and we
 * know what the player still owes us, or it failed and we have a message to show them.
 */
public record AuthResult(
        boolean ok,
        int httpStatus,
        String message,
        String nickname,
        boolean registered,
        boolean accountActive,
        boolean emailVerified,
        List<String> consentsMissing) {

    public static AuthResult failure(int httpStatus, String message) {
        return new AuthResult(false, httpStatus, message, null, false, false, false, List.of());
    }

    public static AuthResult error(String message) {
        return failure(0, message);
    }

    public boolean needsConsents() {
        return consentsMissing != null && !consentsMissing.isEmpty();
    }
}
