package app.glasshome.launcher;

import android.animation.TimeInterpolator;

/**
 * A damped spring, solved exactly (mass 1), described the way Apple describes its springs: a
 * response (the period, in seconds) and a damping ratio below 1. Used where motion should feel
 * like iOS's: the tile menu unfolding, and tiles making room for one being moved.
 */
final class Spring {
    /** The tile menu unfolding: about 1 % overshoot. */
    static final Spring MENU = new Spring(0.34f, 0.82f);
    /** The held tile rising to make room for its menu: a soft pop. */
    static final Spring POP = new Spring(0.32f, 0.72f);
    /** Tiles sliding aside for one being moved, as icons do on an iPhone's home screen. */
    static final Spring REORDER = new Spring(0.38f, 0.86f);
    /** The tile being moved: a touch quicker than the others, so it leads them. */
    static final Spring LIFT = new Spring(0.30f, 0.86f);

    private final float w, z, wd;

    Spring(float response, float damping) {
        w = (float) (2 * Math.PI / response);
        z = damping;
        wd = w * (float) Math.sqrt(1 - z * z);
    }

    /** Displacement {@code t} seconds after starting at {@code x0} with velocity {@code v0}; the rest is 0. */
    float x(float x0, float v0, float t) {
        float e = (float) Math.exp(-z * w * t), b = (v0 + z * w * x0) / wd;
        return e * (x0 * (float) Math.cos(wd * t) + b * (float) Math.sin(wd * t));
    }

    /** Velocity at the same moment. */
    float v(float x0, float v0, float t) {
        float e = (float) Math.exp(-z * w * t), b = (v0 + z * w * x0) / wd;
        float c = (float) Math.cos(wd * t), s = (float) Math.sin(wd * t);
        return e * (-z * w * (x0 * c + b * s) + wd * (b * c - x0 * s));
    }

    /** Time until within 0.1 % of rest. */
    long settleMs() {
        return (long) Math.ceil(Math.log(1000) / (z * w) * 1000);
    }

    /** The spring from rest to its target as an interpolator over {@link #settleMs()}, for property animators. */
    TimeInterpolator interpolator() {
        final float T = settleMs() / 1000f;
        return f -> f >= 1f ? 1f : 1f - x(1f, 0f, f * T);
    }
}
