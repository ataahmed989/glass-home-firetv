package app.glasshome.launcher;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Filled, multicolour weather symbols in the style of a phone weather app: yellow sun and
 * lightning, white clouds and moon, blue rain. Drawn here with paths (no third-party glyphs).
 * Where a cloud overlaps the sun or moon, a thin gap is cut so the shapes stay distinct.
 * Colours follow Jack Labbe's weather-app-clone (MIT): yellow #F9D64A, blue #7ED0FA.
 */
final class WeatherIcons {
    private static final int YELLOW = 0xFFF9D64A, WHITE = 0xFFFFFFFF, BLUE = 0xFF7ED0FA;
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path CLOUD = new Path(), GAP = new Path(), PART = new Path(), SHAPE = new Path(), CUT = new Path();
    private static final Matrix GROW = new Matrix();
    private static final RectF R = new RectF();

    static {
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeCap(Paint.Cap.ROUND);
    }

    private WeatherIcons() {}

    /** {@code s} is the icon's overall size in px; (cx, cy) its centre. */
    static void draw(Canvas c, int code, boolean day, float cx, float cy, float s, int alpha) {
        FILL.setAlpha(alpha);
        if (code <= 1) {
            if (day) sun(c, cx, cy, s); else moonStars(c, cx, cy, s);
        } else if (code == 2) {
            behindCloud(c, day, cx - s * 0.14f, cy - s * 0.13f, s * 0.74f, cx + s * 0.07f, cy + s * 0.09f, s * 0.84f);
        } else if (code == 3) {
            cloud(c, cx, cy, s);
        } else if (code == 45 || code == 48) {
            cloud(c, cx, cy - s * 0.14f, s * 0.86f);
            line(WHITE, s);
            c.drawLine(cx - s * 0.30f, cy + s * 0.22f, cx + s * 0.22f, cy + s * 0.22f, LINE);
            c.drawLine(cx - s * 0.18f, cy + s * 0.36f, cx + s * 0.32f, cy + s * 0.36f, LINE);
        } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            cloud(c, cx, cy - s * 0.14f, s * 0.88f);
            fill(WHITE);
            for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.19f, cy + s * 0.27f + (i == 0 ? s * 0.09f : 0), s * 0.05f, FILL);
        } else if (code >= 95) {
            cloud(c, cx, cy - s * 0.14f, s * 0.88f);
            fill(YELLOW);
            SHAPE.reset();
            SHAPE.moveTo(cx + s * 0.05f, cy + s * 0.07f);
            SHAPE.lineTo(cx - s * 0.12f, cy + s * 0.29f);
            SHAPE.lineTo(cx - s * 0.01f, cy + s * 0.29f);
            SHAPE.lineTo(cx - s * 0.07f, cy + s * 0.46f);
            SHAPE.lineTo(cx + s * 0.13f, cy + s * 0.22f);
            SHAPE.lineTo(cx + s * 0.02f, cy + s * 0.22f);
            SHAPE.lineTo(cx + s * 0.08f, cy + s * 0.07f);
            SHAPE.close();
            c.drawPath(SHAPE, FILL);
        } else {
            // Drizzle, rain and showers: blue drops under a white cloud
            boolean heavy = code == 65 || code == 82 || code == 67;
            cloud(c, cx, cy - s * 0.14f, s * 0.88f);
            line(BLUE, s);
            int drops = heavy ? 4 : 3;
            for (int i = 0; i < drops; i++) {
                float x = cx + (i - (drops - 1) / 2f) * s * (heavy ? 0.14f : 0.17f);
                c.drawLine(x + s * 0.03f, cy + s * 0.20f, x - s * 0.03f, cy + s * 0.36f, LINE);
            }
        }
    }

    private static void fill(int color) {
        int a = FILL.getAlpha();
        FILL.setColor(color);
        FILL.setAlpha(a);
    }

    private static void line(int color, float s) {
        LINE.setColor(color);
        LINE.setAlpha(FILL.getAlpha());
        LINE.setStrokeWidth(Math.max(1.5f, s * 0.075f));
    }

    private static void sun(Canvas c, float cx, float cy, float s) {
        fill(YELLOW);
        c.drawCircle(cx, cy, s * 0.20f, FILL);
        line(YELLOW, s);
        LINE.setStrokeWidth(Math.max(1.5f, s * 0.07f));
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * s * 0.30f, cy + sin * s * 0.30f, cx + cos * s * 0.40f, cy + sin * s * 0.40f, LINE);
        }
    }

    private static void moon(Canvas c, float cx, float cy, float s) {
        SHAPE.reset();
        SHAPE.addCircle(cx, cy, s * 0.28f, Path.Direction.CW);
        CUT.reset();
        CUT.addCircle(cx + s * 0.16f, cy - s * 0.12f, s * 0.23f, Path.Direction.CW);
        SHAPE.op(CUT, Path.Op.DIFFERENCE);
        fill(WHITE);
        c.drawPath(SHAPE, FILL);
    }

    private static void moonStars(Canvas c, float cx, float cy, float s) {
        moon(c, cx - s * 0.06f, cy + s * 0.04f, s);
        star(c, cx + s * 0.24f, cy - s * 0.22f, s * 0.09f);
        star(c, cx + s * 0.33f, cy + s * 0.02f, s * 0.06f);
    }

    /** Four-point sparkle. */
    private static void star(Canvas c, float cx, float cy, float r) {
        SHAPE.reset();
        SHAPE.moveTo(cx, cy - r);
        SHAPE.quadTo(cx, cy, cx + r, cy);
        SHAPE.quadTo(cx, cy, cx, cy + r);
        SHAPE.quadTo(cx, cy, cx - r, cy);
        SHAPE.quadTo(cx, cy, cx, cy - r);
        SHAPE.close();
        fill(WHITE);
        c.drawPath(SHAPE, FILL);
    }

    /** Sun or moon partly hidden by a cloud, separated by a thin cut-out gap. */
    private static void behindCloud(Canvas c, boolean day, float bx, float by, float bs, float cx, float cy, float cs) {
        cloudPath(cx, cy, cs);
        GROW.setScale(1.12f, 1.14f, cx, cy + cs * 0.06f);
        CLOUD.transform(GROW, GAP);
        c.save();
        c.clipOutPath(GAP);
        if (day) sun(c, bx, by, bs); else moon(c, bx, by, bs);
        c.restore();
        fill(WHITE);
        c.drawPath(CLOUD, FILL);
    }

    private static void cloud(Canvas c, float cx, float cy, float s) {
        cloudPath(cx, cy, s);
        fill(WHITE);
        c.drawPath(CLOUD, FILL);
    }

    private static void cloudPath(float cx, float cy, float s) {
        CLOUD.reset();
        CLOUD.addCircle(cx - s * 0.17f, cy + s * 0.05f, s * 0.15f, Path.Direction.CW);
        PART.reset();
        PART.addCircle(cx + s * 0.02f, cy - s * 0.05f, s * 0.21f, Path.Direction.CW);
        CLOUD.op(PART, Path.Op.UNION);
        PART.reset();
        PART.addCircle(cx + s * 0.21f, cy + s * 0.06f, s * 0.13f, Path.Direction.CW);
        CLOUD.op(PART, Path.Op.UNION);
        PART.reset();
        R.set(cx - s * 0.32f, cy + s * 0.04f, cx + s * 0.34f, cy + s * 0.20f);
        PART.addRoundRect(R, s * 0.08f, s * 0.08f, Path.Direction.CW);
        CLOUD.op(PART, Path.Op.UNION);
    }
}
