package cn.jiang.opennote;

/**
 * Incremental adaptation of perfect-freehand's getStrokePoints streamline step.
 * Copyright (c) 2021 Stephen Ruiz Ltd. MIT; see assets/licenses/perfect-freehand.txt.
 * Only position interpolation is ported, not its outline/velocity-pressure algorithm.
 * Raw samples remain untouched. No minimum-distance gate: small letters must survive.
 */
public final class StrokeStreamline {
    // OpenNote's conservative profile: retain 85% of each new position.
    private static final double INTERPOLATION = .85;
    private Ink.Point previous;

    public Ink.Point add(Ink.Point raw) {
        Ink.Point adjusted = raw.copy(raw.y);
        if (previous != null) {
            adjusted.x = previous.x + (raw.x - previous.x) * INTERPOLATION;
            adjusted.y = previous.y + (raw.y - previous.y) * INTERPOLATION;
        }
        previous = adjusted;
        return adjusted;
    }
}
