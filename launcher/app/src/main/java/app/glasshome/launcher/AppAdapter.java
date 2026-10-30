package app.glasshome.launcher;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {
    interface Listener {
        void onLaunch(AppEntry app, int position);

        void onFocused(AppEntry app, int position);
    }

    private final List<AppEntry> apps = new ArrayList<>();
    private final Listener listener;
    /** The app being moved (hold Select, Move), or null. */
    private String movingKey;
    /** The app whose menu is open (hold Select), or null. */
    private String menuKey;
    /** Told when a cell leaves the grid, so nothing goes on moving it. */
    private java.util.function.Consumer<android.view.View> onRecycled;

    AppAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    AppEntry get(int position) {
        return apps.get(position);
    }

    int indexOf(String key) {
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).key.equals(key)) return i;
        return -1;
    }

    void setApps(List<AppEntry> list) {
        apps.clear();
        apps.addAll(list);
        notifyDataSetChanged();
    }

    /** The apps' keys, in their order on Home. */
    List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (AppEntry app : apps) keys.add(app.key);
        return keys;
    }

    /** Moves the app at {@code from} to {@code to}; its tile goes with it, keeping focus. */
    void move(int from, int to) {
        apps.add(to, apps.remove(from));
        notifyItemMoved(from, to);
    }

    /** The apps' ids, in their order on Home. */
    List<Long> itemIds() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < apps.size(); i++) ids.add(getItemId(i));
        return ids;
    }

    void setMovingKey(String key) {
        movingKey = key;
    }

    void setMenuKey(String key) {
        menuKey = key;
    }

    String menuKey() {
        return menuKey;
    }

    void setOnRecycled(java.util.function.Consumer<android.view.View> listener) {
        onRecycled = listener;
    }

    /** The focused app's name shows, except while apps are being arranged. */
    private boolean nameShown(TileView tile) {
        return tile.isFocused() && movingKey == null && menuKey == null;
    }

    @Override
    public long getItemId(int position) {
        return apps.get(position).key.hashCode();
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Each cell spans one 290 px column and one 224 px row; the tile sits centred at its top, and
        // the space under it is reserved for the name, so showing it never moves anything.
        // Rows fading out above the dock: the tile and its name never overlap, so the fade needs no offscreen pass
        FrameLayout cell = new FrameLayout(parent.getContext()) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(Ui.ROW_PITCH)));
        TileView tile = new TileView(parent.getContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.tileW(), Ui.tileH());
        lp.leftMargin = Ui.px((Ui.COL_PITCH - Ui.TILE_W) / 2f);
        cell.addView(tile, lp);
        TextView name = new TextView(parent.getContext());
        name.setTypeface(Fonts.inter(parent.getContext(), 500));
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(20));
        name.setTextColor(0xF2FFFFFF);
        name.setShadowLayer(Ui.px(8), 0, Ui.px(1), 0x99000000);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setAlpha(0f);
        name.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(Ui.px(Ui.COL_PITCH), Ui.px(Ui.LABEL_H));
        nl.topMargin = Ui.tileH() + Ui.px(Ui.LABEL_GAP);
        cell.addView(name, nl);
        return new Holder(cell, tile, name);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AppEntry app = apps.get(position);
        holder.tile.setArt(app.art, app.layers);
        holder.tile.setContentDescription(app.label);
        holder.tile.resetFocusState();
        if (movingKey != null) holder.tile.setRole(app.key.equals(movingKey) ? TileView.LIFTED : TileView.RECEDED, false);
        if (menuKey != null) holder.tile.setMenuState(app.key.equals(menuKey), !app.key.equals(menuKey), false);
        holder.name.setText(app.label);
        holder.name.animate().cancel();
        holder.name.setAlpha(nameShown(holder.tile) ? 1f : 0f);
        holder.tile.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onLaunch(apps.get(pos), pos);
        });
        holder.tile.setOnFocusChangeListener((v, hasFocus) -> {
            holder.name.animate().alpha(nameShown(holder.tile) ? 1f : 0f).setDuration(Ui.reducedMotion ? 0 : Ui.FOCUS_MS)
                    .setInterpolator(Ui.EASE).start();
            int pos = holder.getBindingAdapterPosition();
            if (hasFocus && pos != RecyclerView.NO_POSITION) listener.onFocused(apps.get(pos), pos);
        });
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.itemView.setTranslationX(0f);
        holder.itemView.setTranslationY(0f);
        if (onRecycled != null) onRecycled.accept(holder.itemView);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TileView tile;
        final TextView name;

        Holder(FrameLayout cell, TileView tile, TextView name) {
            super(cell);
            this.tile = tile;
            this.name = name;
        }
    }
}
