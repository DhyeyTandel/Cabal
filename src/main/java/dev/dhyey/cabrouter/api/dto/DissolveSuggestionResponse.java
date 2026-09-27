package dev.dhyey.cabrouter.api.dto;

import dev.dhyey.cabrouter.routing.ShiftPlan;
import java.util.List;

/** One cab that could be dissolved, with money figures rounded for display. */
public record DissolveSuggestionResponse(
        int cabNumber,
        int ridersMoved,
        List<Integer> receivingCabNumbers,
        double costBefore,
        double costAfter,
        double saving) {

    public static DissolveSuggestionResponse from(ShiftPlan.DissolveSuggestion s) {
        return new DissolveSuggestionResponse(s.cabNumber(), s.ridersMoved(), s.receivingCabNumbers(),
                round(s.costBefore()), round(s.costAfter()), round(s.saving()));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
