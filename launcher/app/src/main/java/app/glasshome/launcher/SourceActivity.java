package app.glasshome.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Source: the TV's HDMI inputs, shown here in a TvView (Fire OS keeps its own input switcher to
 * system apps). A list of inputs in Control Center's style, dark glass with the focused row lit;
 * Select shows that input full screen, Menu or Select brings the list back, Back goes Home.
 */
public final class SourceActivity extends Activity {
    private TvView tv;
    private SourceList list;
    private final List<TvInputInfo> inputs = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(getResources().getDisplayMetrics().widthPixels);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        tv = new TvView(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        TvInputManager manager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (manager != null) {
            List<TvInputInfo> all = new ArrayList<>(manager.getTvInputList());
            // HW4, HW5, HW6 are HDMI 1, 2, 3
            all.sort((a, b) -> a.getType() != b.getType() ? (a.getType() == TvInputInfo.TYPE_HDMI ? -1 : b.getType() == TvInputInfo.TYPE_HDMI ? 1 : 0) : a.getId().compareTo(b.getId()));
            for (TvInputInfo info : all) {
                int t = info.getType();
                if (t != TvInputInfo.TYPE_HDMI && t != TvInputInfo.TYPE_COMPOSITE && t != TvInputInfo.TYPE_COMPONENT || info.isHidden(this)) continue;
                inputs.add(info);
                CharSequence label = info.loadCustomLabel(this);
                if (label == null) label = info.loadLabel(this);
                names.add(label == null ? (t == TvInputInfo.TYPE_HDMI ? "HDMI " : "AV ") + inputs.size() : label.toString());
            }
        }
        list = new SourceList(this);
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        list.requestFocus();
    }

    private void tune(int i) {
        String id = inputs.get(i).getId();
        tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
        list.current = i;
        list.setVisibility(View.GONE);
        tv.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (list.getVisibility() != View.VISIBLE && e.getAction() == KeyEvent.ACTION_UP
                && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            list.setVisibility(View.VISIBLE);
            list.requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStop() {
        // Amazon's brief overlays (input notices, volume) stop this too: keep the list, drop the picture
        super.onStop();
        tv.reset();
        list.current = -1;
        list.setVisibility(View.VISIBLE);
    }

    /** The list, drawn like Control Center's modules. */
    private final class SourceList extends View {
        private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG), lit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint heading = new TextPaint(Paint.ANTI_ALIAS_FLAG), row = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        int focused, current = -1;

        SourceList(Context c) {
            super(c);
            setFocusable(true);
            setDefaultFocusHighlightEnabled(false);
            panel.setColor(0xD91C1C1E);
            panel.setShadowLayer(Ui.px(40), 0, Ui.px(10), 0x40000000);
            lit.setColor(0xF2FFFFFF);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);
            FeaturePainter.style(heading, c, 600, 30, 0xFFFFFFFF);
            FeaturePainter.style(row, c, 500, 26, 0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = Ui.px(560), rowH = Ui.px(84), pad = Ui.px(18), rad = Ui.px(42);
            int n = Math.max(1, names.size());
            float h = Ui.px(110) + n * rowH + pad;
            float left = getWidth() - Ui.px(86) - w, top = Ui.px(96);
            r.set(left, top, left + w, top + h);
            c.drawRoundRect(r, rad, rad, panel);
            // Header: the input symbol and "Source"
            float iy = top + Ui.px(56), ix = left + Ui.px(52);
            drawInputIcon(c, ix, iy, Ui.px(34), 0xFFFFFFFF);
            heading.setTextSize(Ui.px(30));
            c.drawText("Source", ix + Ui.px(40), iy + Ui.px(11), heading);
            row.setTextSize(Ui.px(26));
            if (names.isEmpty()) {
                row.setColor(0x8CFFFFFF);
                c.drawText("No HDMI inputs found", left + Ui.px(40), top + Ui.px(110) + rowH / 2f + Ui.px(9), row);
                return;
            }
            for (int i = 0; i < names.size(); i++) {
                float y = top + Ui.px(100) + i * rowH;
                r.set(left + pad, y, left + w - pad, y + rowH - Ui.px(8));
                boolean f = i == focused;
                if (f) c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, lit);
                row.setColor(f ? 0xFF111215 : 0xFFFFFFFF);
                c.drawText(names.get(i), r.left + Ui.px(34), r.centerY() + Ui.px(9), row);
                if (i == current) {
                    // A check on the one showing
                    icon.setColor(f ? 0xFF111215 : 0xFF0088FF);
                    icon.setStrokeWidth(Ui.px(4));
                    float cx = r.right - Ui.px(44), cy = r.centerY();
                    path.reset();
                    path.moveTo(cx - Ui.px(11), cy);
                    path.lineTo(cx - Ui.px(3), cy + Ui.px(8));
                    path.lineTo(cx + Ui.px(12), cy - Ui.px(9));
                    c.drawPath(path, icon);
                }
            }
        }

        /** An arrow into a box, the input symbol. */
        private void drawInputIcon(Canvas c, float cx, float cy, float s, int color) {
            float h = s / 2f;
            icon.setColor(color);
            icon.setStrokeWidth(Math.max(2f, s * 0.1f));
            path.reset();
            path.moveTo(cx - h * 0.2f, cy - h * 0.9f);
            path.lineTo(cx + h * 0.9f, cy - h * 0.9f);
            path.lineTo(cx + h * 0.9f, cy + h * 0.9f);
            path.lineTo(cx - h * 0.2f, cy + h * 0.9f);
            c.drawPath(path, icon);
            c.drawLine(cx - h * 0.95f, cy, cx + h * 0.35f, cy, icon);
            path.reset();
            path.moveTo(cx - h * 0.05f, cy - h * 0.4f);
            path.lineTo(cx + h * 0.35f, cy);
            path.lineTo(cx - h * 0.05f, cy + h * 0.4f);
            c.drawPath(path, icon);
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (names.isEmpty()) return super.onKeyDown(code, e);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (focused > 0) focused--;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (focused < names.size() - 1) focused++;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    tune(focused);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
            }
            return super.onKeyDown(code, e);
        }
    }
}
