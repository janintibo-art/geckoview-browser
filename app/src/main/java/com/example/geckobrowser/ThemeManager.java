package com.example.geckobrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoView;

import java.util.Arrays;

/**
 * Themes visuels de l'interface native du navigateur.
 *
 * Les pages web ne sont volontairement pas recolorees : un theme ne doit pas
 * modifier le contenu d'un site. En revanche, toute l'interface du navigateur
 * (barre d'adresse, boutons, menus, dialogues, ecran de demarrage et couleurs
 * systeme) suit la palette choisie.
 */
public final class ThemeManager {

    private ThemeManager() { }

    public static final String PREF_KEY = "uiTheme";

    public static final class Palette {
        public final String id;
        public final String name;
        public final String description;
        public final int background;
        public final int surface;
        public final int surfaceAlt;
        public final int text;
        public final int muted;
        public final int accent;
        public final int accentSoft;
        public final int outline;
        public final boolean light;

        Palette(String id, String name, String description,
                String background, String surface, String surfaceAlt,
                String text, String muted, String accent, String accentSoft,
                String outline, boolean light) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.background = Color.parseColor(background);
            this.surface = Color.parseColor(surface);
            this.surfaceAlt = Color.parseColor(surfaceAlt);
            this.text = Color.parseColor(text);
            this.muted = Color.parseColor(muted);
            this.accent = Color.parseColor(accent);
            this.accentSoft = Color.parseColor(accentSoft);
            this.outline = Color.parseColor(outline);
            this.light = light;
        }
    }

    private static final Palette[] THEMES = {
        new Palette("gecko", "Gecko graphite", "Graphite profond et vert naturel",
                "#101317", "#1A1F26", "#242B34", "#F1F5F7", "#98A2AD",
                "#70C96B", "#A7E88D", "#33404A", false),

        new Palette("forest", "Foret emeraude", "Vert sapin, mousse et menthe",
                "#07140E", "#0E2418", "#173724", "#F0FFF6", "#9BC6AA",
                "#58D68D", "#B6F2C8", "#28543C", false),

        new Palette("ocean", "Ocean neon", "Bleu nuit, cyan et reflets glaces",
                "#06131F", "#0C2235", "#12334D", "#EFF9FF", "#94B7CD",
                "#38C6F4", "#8BE8FF", "#26536D", false),

        new Palette("cosmos", "Cosmos violet", "Violet sombre et lumiere lilas",
                "#11091D", "#211133", "#321A4C", "#FBF3FF", "#BCA1CE",
                "#B877FF", "#E7B4FF", "#4D2D68", false),

        new Palette("ember", "Braise cuivre", "Bordeaux, cuivre et orange chaud",
                "#190B09", "#2C1512", "#422119", "#FFF5EF", "#CCA69A",
                "#FF7A4D", "#FFC06F", "#68392B", false),

        new Palette("sakura", "Sakura nocturne", "Prune profonde et rose tendre",
                "#1A0C15", "#2D1625", "#452139", "#FFF5FA", "#D0A5BA",
                "#FF78B7", "#FFB7D7", "#663451", false),

        new Palette("ivory", "Ivoire zen", "Clair, chaud et tres lisible",
                "#F4EFE6", "#FFF9EF", "#E9E0D3", "#292B29", "#70736D",
                "#2D8C7B", "#67B7A8", "#D3C9B9", true),

        new Palette("amoled", "AMOLED absolu", "Noir pur et vert electrique",
                "#000000", "#080808", "#131313", "#FFFFFF", "#A0A0A0",
                "#7CFF6B", "#C7FFBE", "#2C2C2C", false)
    };

    public static Palette[] all() {
        return THEMES.clone();
    }

    public static Palette current(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("geckobrowser", Context.MODE_PRIVATE);
        String id = prefs.getString(PREF_KEY, "gecko");
        for (Palette p : THEMES) if (p.id.equals(id)) return p;
        return THEMES[0];
    }

    public static String currentName(Context context) {
        return current(context).name;
    }

    public static int browserBackground(Context context) {
        return current(context).background;
    }

    public static void save(Context context, String id) {
        context.getSharedPreferences("geckobrowser", Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, id).apply();
    }

    /** Couleurs des barres Android et contraste des icones systeme. */
    public static void applyWindow(Activity activity) {
        Palette p = current(activity);
        Window window = activity.getWindow();
        window.setStatusBarColor(p.background);
        window.setNavigationBarColor(p.background);
        if (Build.VERSION.SDK_INT >= 28) window.setNavigationBarDividerColor(p.outline);

        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (p.light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /** Applique la palette a toute l'interface de MainActivity. */
    public static void apply(Activity activity) {
        Palette p = current(activity);
        applyWindow(activity);

        View root = activity.findViewById(R.id.root_container);
        View column = activity.findViewById(R.id.browser_column);
        View toolbar = activity.findViewById(R.id.toolbar);
        View findBar = activity.findViewById(R.id.find_bar);
        View splash = activity.findViewById(R.id.splash);

        if (root != null) root.setBackgroundColor(p.background);
        if (column != null) column.setBackgroundColor(p.background);
        if (toolbar != null) {
            toolbar.setBackground(gradient(p.surface, p.surfaceAlt, 0f, p.outline));
            toolbar.setElevation(dp(activity, 6));
        }
        if (findBar != null) {
            findBar.setBackground(gradient(p.surfaceAlt, p.surface, 0f, p.outline));
        }
        if (splash != null) {
            splash.setBackground(gradient(p.background, p.surface, 0f, p.outline));
        }

        EditText url = activity.findViewById(R.id.url_bar);
        if (url != null) {
            url.setTextColor(p.text);
            url.setHintTextColor(p.muted);
            url.setBackground(ripple(activity,
                    rounded(p.surfaceAlt, dp(activity, 18), p.outline, dp(activity, 1)),
                    p.accent));
            url.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
            url.setMinHeight(dp(activity, 44));
        }

        TextView shield = activity.findViewById(R.id.shield);
        if (shield != null) {
            shield.setTextColor(p.accent);
            shield.setBackground(ripple(activity,
                    rounded(withAlpha(p.accent, 28), dp(activity, 16), p.accent, dp(activity, 1)),
                    p.accent));
        }

        TextView tabs = activity.findViewById(R.id.tab_button);
        if (tabs != null) {
            tabs.setTextColor(p.text);
            tabs.setBackground(ripple(activity,
                    rounded(p.surfaceAlt, dp(activity, 10), p.accent, dp(activity, 1)),
                    p.accent));
        }

        ImageButton menu = activity.findViewById(R.id.menu_button);
        if (menu != null) {
            menu.setColorFilter(p.text);
            menu.setBackground(ripple(activity,
                    rounded(Color.TRANSPARENT, dp(activity, 20), Color.TRANSPARENT, 0),
                    p.accent));
        }

        ImageButton go = activity.findViewById(R.id.go_button);
        if (go != null) {
            go.setColorFilter(onAccent(p.accent));
            go.setBackground(ripple(activity,
                    rounded(p.accent, dp(activity, 20), p.accentSoft, dp(activity, 1)),
                    p.accentSoft));
        }

        EditText findInput = activity.findViewById(R.id.find_input);
        if (findInput != null) {
            findInput.setTextColor(p.text);
            findInput.setHintTextColor(p.muted);
            findInput.setBackground(ripple(activity,
                    rounded(p.surfaceAlt, dp(activity, 14), p.outline, dp(activity, 1)),
                    p.accent));
            findInput.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        }

        TextView findCount = activity.findViewById(R.id.find_count);
        if (findCount != null) findCount.setTextColor(p.muted);

        tintImage(activity, R.id.find_prev, p.text, p.accent);
        tintImage(activity, R.id.find_next, p.text, p.accent);
        tintImage(activity, R.id.find_close, p.text, p.accent);

        ProgressBar progress = activity.findViewById(R.id.progress);
        if (progress != null) {
            progress.setProgressTintList(ColorStateList.valueOf(p.accent));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(p.surfaceAlt));
        }

        ProgressBar splashProgress = activity.findViewById(R.id.splash_progress);
        if (splashProgress != null && splashProgress.getIndeterminateDrawable() != null) {
            splashProgress.setIndeterminateTintList(ColorStateList.valueOf(p.accent));
        }

        GeckoView gecko = activity.findViewById(R.id.geckoview);
        if (gecko != null) gecko.setBackgroundColor(p.background);
    }

    private static void tintImage(Activity activity, int id, int color, int rippleColor) {
        ImageButton button = activity.findViewById(id);
        if (button == null) return;
        button.setColorFilter(color);
        button.setBackground(ripple(activity,
                rounded(Color.TRANSPARENT, dp(activity, 18), Color.TRANSPARENT, 0),
                rippleColor));
    }

    /** Recolore les lignes du menu natif. */
    public static void styleMenuRow(View row, Context context) {
        Palette p = current(context);
        TextView icon = row.findViewById(R.id.m_icon);
        TextView title = row.findViewById(R.id.m_title);
        TextView value = row.findViewById(R.id.m_value);
        TextView chevron = row.findViewById(R.id.m_chevron);

        if (icon != null) { icon.setTag("theme_accent"); icon.setTextColor(p.accent); }
        if (title != null) { title.setTag("theme_text"); title.setTextColor(p.text); }
        if (value != null) { value.setTag("theme_muted"); value.setTextColor(p.muted); }
        if (chevron != null) { chevron.setTag("theme_muted"); chevron.setTextColor(p.muted); }
        row.setBackground(ripple(context,
                rounded(Color.TRANSPARENT, dp(context, 12), Color.TRANSPARENT, 0),
                p.accent));
    }

    /** Affiche puis recolore une boite de dialogue. */
    public static AlertDialog show(AlertDialog dialog, Context context) {
        dialog.setOnShowListener(ignored -> styleDialog(dialog, context));
        dialog.show();
        return dialog;
    }

    /** Recolore fond, texte, boutons, champs et controles d'un dialogue. */
    public static void styleDialog(AlertDialog dialog, Context context) {
        Palette p = current(context);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(rounded(p.surface, dp(context, 22),
                    p.outline, dp(context, 1)));
        }

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (positive != null) positive.setTextColor(p.accent);
        if (negative != null) negative.setTextColor(p.accent);
        if (neutral != null) neutral.setTextColor(p.accent);

        if (window != null) tintTree(window.getDecorView(), p);
        ListView list = dialog.getListView();
        if (list != null) {
            list.setDividerHeight(0);
            list.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private static void tintTree(View view, Palette p) {
        Object role = view.getTag();
        if (view instanceof TextView && "theme_accent".equals(role)) {
            ((TextView) view).setTextColor(p.accent);
        } else if (view instanceof TextView && "theme_muted".equals(role)) {
            ((TextView) view).setTextColor(p.muted);
        } else if (view instanceof TextView && "theme_text".equals(role)) {
            ((TextView) view).setTextColor(p.text);
        } else if (view instanceof EditText) {
            EditText e = (EditText) view;
            e.setTextColor(p.text);
            e.setHintTextColor(p.muted);
            e.setBackgroundTintList(ColorStateList.valueOf(p.accent));
        } else if (view instanceof Button) {
            ((Button) view).setTextColor(p.accent);
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(p.text);
        }

        if (view instanceof CompoundButton) {
            ((CompoundButton) view).setButtonTintList(new ColorStateList(
                    new int[][] { new int[] { android.R.attr.state_checked }, new int[] { } },
                    new int[] { p.accent, p.muted }));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintTree(group.getChildAt(i), p);
            }
        }
    }

    /**
     * Selecteur avec apercu de chaque palette. Le changement est immediat et
     * ne recharge ni la page ni les onglets.
     */
    public static void showPicker(Activity activity, Runnable afterApply, Runnable back) {
        Palette active = current(activity);
        ArrayAdapter<Palette> adapter = new ArrayAdapter<Palette>(
                activity, android.R.layout.simple_list_item_1, Arrays.asList(THEMES)) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ThemeRow holder;
                if (convertView == null || !(convertView.getTag() instanceof ThemeRow)) {
                    convertView = makeThemeRow(activity);
                    holder = (ThemeRow) convertView.getTag();
                } else {
                    holder = (ThemeRow) convertView.getTag();
                }

                Palette item = getItem(position);
                if (item == null) return convertView;

                Palette ui = current(activity);
                holder.title.setText(item.name);
                holder.description.setText(item.description);
                holder.check.setVisibility(item.id.equals(active.id) ? View.VISIBLE : View.INVISIBLE);
                holder.title.setTextColor(ui.text);
                holder.description.setTextColor(ui.muted);
                holder.check.setTextColor(ui.accent);
                holder.swatch.setBackground(gradient(item.background, item.accent,
                        dp(activity, 12), item.outline));
                convertView.setBackground(ripple(activity,
                        rounded(Color.TRANSPARENT, dp(activity, 12), Color.TRANSPARENT, 0),
                        ui.accent));
                return convertView;
            }
        };

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.GeckoDialog)
                .setTitle("Choisir un theme")
                .setAdapter(adapter, (d, which) -> {
                    Palette selected = THEMES[which];
                    save(activity, selected.id);
                    if (afterApply != null) afterApply.run();
                })
                .setNegativeButton("Retour", (d, w) -> {
                    if (back != null) back.run();
                })
                .create();
        show(dialog, activity);
    }

    private static final class ThemeRow {
        final View swatch;
        final TextView title;
        final TextView description;
        final TextView check;

        ThemeRow(View swatch, TextView title, TextView description, TextView check) {
            this.swatch = swatch;
            this.title = title;
            this.description = description;
            this.check = check;
        }
    }

    private static View makeThemeRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12));
        row.setMinimumHeight(dp(context, 68));

        View swatch = new View(context);
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(
                dp(context, 58), dp(context, 38));
        swatchLp.setMarginEnd(dp(context, 14));
        row.addView(swatch, swatchLp);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setTag("theme_text");
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        TextView description = new TextView(context);
        description.setTag("theme_muted");
        description.setTextSize(12);
        description.setPadding(0, dp(context, 2), 0, 0);
        labels.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        labels.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView check = new TextView(context);
        check.setTag("theme_accent");
        check.setText("✓");
        check.setTextSize(22);
        check.setGravity(Gravity.CENTER);
        row.addView(check, new LinearLayout.LayoutParams(dp(context, 34), dp(context, 42)));

        row.setTag(new ThemeRow(swatch, title, description, check));
        return row;
    }

    private static Drawable ripple(Context context, Drawable content, int color) {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(color, 72)), content, null);
    }

    private static GradientDrawable rounded(int fill, float radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    private static GradientDrawable gradient(int start, int end, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[] { start, end });
        d.setCornerRadius(radius);
        if (Color.alpha(stroke) > 0) d.setStroke(1, stroke);
        return d;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int onAccent(int accent) {
        double luma = 0.299 * Color.red(accent)
                + 0.587 * Color.green(accent)
                + 0.114 * Color.blue(accent);
        return luma > 165 ? Color.rgb(18, 22, 20) : Color.WHITE;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
