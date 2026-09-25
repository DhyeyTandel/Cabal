package dev.dhyey.cabrouter.api.dto;

import dev.dhyey.cabrouter.domain.Office;

public record OfficeResponse(long id, String name, double latitude, double longitude) {

    public static OfficeResponse from(Office o) {
        return new OfficeResponse(o.getId(), o.getName(), o.getLatitude(), o.getLongitude());
    }
}
