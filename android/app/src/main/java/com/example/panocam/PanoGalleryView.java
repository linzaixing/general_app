package com.example.panocam;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页「最近作品」瀑布流：读取相册 Pictures/PanoCam 下拼接好的 2:1 成片，
 * 双列错落排布，点击可全屏预览。视觉与查看器页共用一套令牌。
 */
final class PanoGalleryView extends FrameLayout {

    interface Listener {
        void onShoot();
    }

    /** 卡片高度 / 列宽，交错取值制造瀑布感（源图统一 2:1，裁中间段展示）。 */
    private static final float[] RATIOS = {1.00f, 0.72f, 0.86f, 0.66f, 0.95f, 0.76f, 0.90f, 0.68f};
    private static final int MAX_ITEMS = 24;

    private final Listener listener;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
    private final Drawable placeholder;
    private final int cardGap;
    private final int colWidth;

    private LinearLayout colA;
    private LinearLayout colB;
    private TextView countView;
    private ScrollView scroll;
    private View emptyBox;
    private int heightA;
    private int heightB;

    PanoGalleryView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        float d = getResources().getDisplayMetrics().density;
        cardGap = Math.round(10 * d);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        colWidth = (screenW - Math.round(24 * d) - cardGap) / 2;

        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0xFF17171A);
        ph.setCornerRadius(14 * d);
        placeholder = ph;

        setBackground(backdrop());
        buildUi();
        refresh();
    }

    // ---------------- 结构 ----------------

    private Drawable backdrop() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B0B0C);
        return new LayerDrawable(new Drawable[] {bg, new DotGridDrawable(0x14EDEDED, dp(26), dp(1))});
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);

        // 品牌行
        LinearLayout brand = new LinearLayout(getContext());
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(20), dp(18), dp(20), 0);

        View dot = new View(getContext());
        dot.setBackground(oval(getContext().getColor(R.color.lib_accent)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(9);
        brand.addView(dot, dotLp);

        TextView mark = new TextView(getContext());
        mark.setText("PANOCAM");
        mark.setTextSize(11);
        mark.setLetterSpacing(0.22f);
        mark.setTextColor(0xD0FFFFFF);
        brand.addView(mark);
        root.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题
        LinearLayout title = new LinearLayout(getContext());
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(dp(20), dp(24), dp(20), dp(6));

        TextView h1 = new TextView(getContext());
        h1.setText("最近作品");
        h1.setTextSize(24);
        h1.setTextColor(0xFFFEFEFE);
        h1.setLetterSpacing(-0.01f);
        title.addView(h1);

        countView = new TextView(getContext());
        countView.setText("正在读取…");
        countView.setTextSize(11);
        countView.setTextColor(0xB8CACACA);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = dp(6);
        title.addView(countView, countLp);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 空状态
        emptyBox = buildEmpty();
        root.addView(emptyBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 瀑布流
        scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(12), dp(6), dp(12), dp(8));

        LinearLayout cols = new LinearLayout(getContext());
        cols.setOrientation(LinearLayout.HORIZONTAL);
        colA = column();
        colB = column();
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpA.rightMargin = cardGap;
        cols.addView(colA, lpA);
        cols.addView(colB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scroll.addView(cols, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部主行动
        LinearLayout footer = new LinearLayout(getContext());
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(20), dp(8), dp(20), dp(20));

        Button shoot = new Button(getContext());
        shoot.setText("开始拍摄");
        shoot.setTextSize(14);
        shoot.setAllCaps(false);
        shoot.setTextColor(getContext().getColor(R.color.lib_on_accent));
        shoot.setBackgroundResource(R.drawable.lib_cta);
        shoot.setPadding(dp(40), dp(15), dp(40), dp(15));
        shoot.setMinWidth(0);
        shoot.setMinimumWidth(0);
        shoot.setMinHeight(0);
        shoot.setMinimumHeight(0);
        shoot.setElevation(0f);
        shoot.setStateListAnimator(null);
        shoot.setOnClickListener(v -> listener.onShoot());
        LinearLayout.LayoutParams shootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        footer.addView(shoot, shootLp);
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private View buildEmpty() {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(36), dp(8), dp(36), dp(8));

        View orb = new View(getContext());
        orb.setBackgroundResource(R.drawable.lib_orb);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(dp(112), dp(112));
        orbLp.bottomMargin = dp(18);
        box.addView(orb, orbLp);

        TextView t = new TextView(getContext());
        t.setText("还没有作品");
        t.setTextSize(16);
        t.setTextColor(0xFFFEFEFE);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView s = new TextView(getContext());
        s.setText("按下快门，第一张全景会出现在这里");
        s.setTextSize(12);
        s.setTextColor(getContext().getColor(R.color.lib_text_tertiary));
        s.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(10);
        box.addView(s, sLp);

        box.setVisibility(View.GONE);
        return box;
    }

    // ---------------- 数据 ----------------

    void refresh() {
        io.execute(() -> {
            List<Item> items = query();
            post(() -> bind(items));
        });
    }

    private List<Item> query() {
        List<Item> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.DATE_ADDED
            };
            String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            String[] args = {"%" + Environment.DIRECTORY_PICTURES + "/PanoCam%"};
            try (android.database.Cursor c = getContext().getContentResolver().query(
                    base, proj, sel, args, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext() && out.size() < MAX_ITEMS) {
                    long id = c.getLong(0);
                    Item it = new Item();
                    it.name = c.getString(1);
                    it.w = c.getInt(2);
                    it.h = c.getInt(3);
                    it.date = c.getLong(4) * 1000L;
                    it.uri = ContentUris.withAppendedId(base, id);
                    out.add(it);
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            File dir = new File(getContext().getExternalFilesDir(null), "pictures");
            File[] files = dir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : files) {
                    if (out.size() >= MAX_ITEMS || !f.getName().toLowerCase(Locale.US).endsWith(".jpg")) {
                        continue;
                    }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                    Item it = new Item();
                    it.name = f.getName();
                    it.file = f;
                    it.date = f.lastModified();
                    it.w = o.outWidth;
                    it.h = o.outHeight;
                    out.add(it);
                }
            }
        }
        return out;
    }

    private void bind(List<Item> items) {
        if (colA == null) {
            return;
        }
        colA.removeAllViews();
        colB.removeAllViews();
        heightA = 0;
        heightB = 0;

        boolean empty = items.isEmpty();
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        scroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        countView.setText(empty ? "还没有保存的全景照片" : ("共 " + items.size() + " 张 · 点击查看"));

        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int h = Math.round(colWidth * RATIOS[i % RATIOS.length]);
            View card = buildCard(it, h);
            LinearLayout target = heightA <= heightB ? colA : colB;
            if (target == colA) {
                heightA += h + cardGap;
            } else {
                heightB += h + cardGap;
            }
            target.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h));
        }
    }

    private View buildCard(Item item, int height) {
        FrameLayout f = new FrameLayout(getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF17171A);
        bg.setCornerRadius(dp(14));
        f.setBackground(bg);
        f.setClipToOutline(true);

        ImageView iv = new ImageView(getContext());
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageDrawable(placeholder);
        f.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tag = new TextView(getContext());
        String stamp = fmt.format(new Date(item.date));
        tag.setText(item.w > 0 ? (stamp + " · " + item.w + "×" + item.h) : stamp);
        tag.setTextSize(9);
        tag.setTextColor(0xD8FFFFFF);
        tag.setBackgroundResource(R.drawable.lib_chip);
        tag.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        tagLp.leftMargin = dp(8);
        tagLp.bottomMargin = dp(8);
        f.addView(tag, tagLp);

        f.setOnClickListener(v -> preview(item));
        loadThumb(iv, item);
        return f;
    }

    private void loadThumb(ImageView iv, Item item) {
        int px = Math.max(colWidth, 320);
        io.execute(() -> {
            Bitmap bm = decode(item, px);
            if (bm == null) {
                return;
            }
            post(() -> {
                if (iv.getParent() != null) {
                    iv.setImageBitmap(bm);
                }
            });
        });
    }

    private Bitmap decode(Item item, int targetPx) {
        try {
            if (item.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getContext().getContentResolver()
                        .loadThumbnail(item.uri, new Size(targetPx, targetPx), null);
            }
            if (item.file != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(item.file.getAbsolutePath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= targetPx) {
                    sample *= 2;
                }
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                return BitmapFactory.decodeFile(item.file.getAbsolutePath(), d);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------- 全屏预览 ----------------

    private void preview(Item item) {
        FrameLayout scrim = new FrameLayout(getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF5000000);
        scrim.setBackground(bg);

        ImageView big = new ImageView(getContext());
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scrim.addView(big, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tip = new TextView(getContext());
        tip.setText("点击任意处返回");
        tip.setTextSize(11);
        tip.setTextColor(getContext().getColor(R.color.lib_text_secondary));
        tip.setBackgroundResource(R.drawable.lib_chip);
        tip.setPadding(dp(12), dp(6), dp(12), dp(6));
        FrameLayout.LayoutParams tipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tipLp.bottomMargin = dp(28);
        scrim.addView(tip, tipLp);

        scrim.setOnClickListener(v -> removeView(scrim));
        addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        io.execute(() -> {
            Bitmap bm = decode(item, Math.max(getResources().getDisplayMetrics().widthPixels, 1600));
            if (bm != null) {
                post(() -> big.setImageBitmap(bm));
            }
        });
    }

    // ---------------- 工具 ----------------

    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class Item {
        Uri uri;
        File file;
        String name;
        int w;
        int h;
        long date;
    }
}
