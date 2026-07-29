package com.example.geckobrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * Deux GeckoView visibles en meme temps, chacun rattache a un onglet existant.
 *
 * Les sessions ne sont jamais dupliquees ni fermees ici. Le gestionnaire ne fait
 * que deplacer leur surface entre les deux volets. MainActivity reste responsable
 * du cycle de vie des onglets et de leur persistance.
 */
public final class SplitScreenManager {

    public interface Host {
        void onPaneFocused(GeckoSession session);
        String titleFor(GeckoSession session);
    }

    public static final int ORIENTATION_AUTO = 0;
    public static final int ORIENTATION_HORIZONTAL = 1;
    public static final int ORIENTATION_VERTICAL = 2;

    private static final String PREFS = "geckobrowser";
    private static final String KEY_RATIO = "splitScreenRatio";
    private static final String KEY_ORIENTATION = "splitScreenOrientation";

    private final Activity activity;
    private final GeckoView primaryView;
    private final Host host;
    private final SharedPreferences prefs;

    private LinearLayout originalParent;
    private ViewGroup.LayoutParams originalLayoutParams;
    private int originalIndex = -1;

    private LinearLayout splitRoot;
    private FrameLayout firstPane;
    private FrameLayout secondPane;
    private GeckoView secondView;
    private View divider;
    private TextView firstLabel;
    private TextView secondLabel;

    private GeckoSession firstSession;
    private GeckoSession secondSession;
    private boolean active;
    private boolean focusFirst = true;
    private float ratio;
    private int orientationMode;

    public SplitScreenManager(Activity activity, GeckoView primaryView, Host host) {
        this.activity = activity;
        this.primaryView = primaryView;
        this.host = host;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.ratio = clamp(prefs.getFloat(KEY_RATIO, 0.5f));
        this.orientationMode = prefs.getInt(KEY_ORIENTATION, ORIENTATION_AUTO);
        if (orientationMode < ORIENTATION_AUTO || orientationMode > ORIENTATION_VERTICAL) {
            orientationMode = ORIENTATION_AUTO;
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean contains(GeckoSession session) {
        return active && session != null
                && (session == firstSession || session == secondSession);
    }

    /** Visible, meme si le volet n'a pas le focus clavier. */
    public boolean isVisible(GeckoSession session) {
        return contains(session);
    }

    public String summary() {
        if (!active) return "inactif";
        return orientationName() + " \u00b7 " + ratioName();
    }

    public String orientationName() {
        switch (orientationMode) {
            case ORIENTATION_HORIZONTAL:
                return "cote a cote";
            case ORIENTATION_VERTICAL:
                return "haut / bas";
            default:
                return "automatique";
        }
    }

    public String ratioName() {
        int left = Math.round(ratio * 100f);
        return left + "/" + (100 - left);
    }

    public int orientationMode() {
        return orientationMode;
    }

    public boolean start(GeckoSession first, GeckoSession second) {
        if (first == null || second == null || first == second) return false;
        if (active) exit();

        ViewParentInfo info = parentInfo();
        if (info == null) {
            Toast.makeText(activity, "Disposition incompatible avec l'ecran partage",
                    Toast.LENGTH_LONG).show();
            return false;
        }

        originalParent = info.parent;
        originalIndex = info.index;
        originalLayoutParams = copyLinearParams(primaryView.getLayoutParams());

        firstSession = first;
        secondSession = second;
        focusFirst = true;
        active = true;

        try {
            originalParent.removeView(primaryView);
            buildViews();
            originalParent.addView(splitRoot,
                    Math.min(originalIndex, originalParent.getChildCount()),
                    originalLayoutParams);
            attach(primaryView, firstSession);
            attach(secondView, secondSession);
            applyOrientation();
            applyTheme(ThemeManager.browserBackground(activity));
            refreshLabels();
            setFocusedPane(true, true);
            return true;
        } catch (Throwable error) {
            active = false;
            restorePrimaryAfterFailure(first);
            Toast.makeText(activity, "Ecran partage indisponible : "
                    + safeMessage(error), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /**
     * Selection d'un onglet depuis MainActivity.
     * - s'il est deja dans un volet, ce volet prend le focus ;
     * - sinon il remplace le contenu du volet actuellement actif.
     */
    public void selectSession(GeckoSession selected) {
        if (!active || selected == null) return;
        if (selected == firstSession) {
            setFocusedPane(true, false);
            return;
        }
        if (selected == secondSession) {
            setFocusedPane(false, false);
            return;
        }

        if (focusFirst) {
            attach(primaryView, selected);
            firstSession = selected;
        } else {
            attach(secondView, selected);
            secondSession = selected;
        }
        refreshLabels();
    }

    public void swap() {
        if (!active) return;
        GeckoSession first = firstSession;
        GeckoSession second = secondSession;
        try {
            release(primaryView);
            release(secondView);
            attach(primaryView, second);
            attach(secondView, first);
            firstSession = second;
            secondSession = first;
            refreshLabels();
            GeckoSession focused = focusFirst ? firstSession : secondSession;
            if (focused != null) host.onPaneFocused(focused);
        } catch (Throwable error) {
            Toast.makeText(activity, "Permutation impossible", Toast.LENGTH_SHORT).show();
        }
    }

    public void setRatio(float value) {
        ratio = clamp(value);
        prefs.edit().putFloat(KEY_RATIO, ratio).apply();
        applyWeights();
    }

    public void setOrientationMode(int mode) {
        if (mode < ORIENTATION_AUTO || mode > ORIENTATION_VERTICAL) return;
        orientationMode = mode;
        prefs.edit().putInt(KEY_ORIENTATION, mode).apply();
        applyOrientation();
    }

    public void onConfigurationChanged(Configuration configuration) {
        if (active && orientationMode == ORIENTATION_AUTO) applyOrientation();
    }

    public void refreshLabels() {
        if (!active || firstLabel == null || secondLabel == null) return;
        firstLabel.setText(label("A", firstSession));
        secondLabel.setText(label("B", secondSession));
        styleLabels();
    }

    public void applyTheme(int background) {
        primaryView.setBackgroundColor(background);
        if (secondView != null) secondView.setBackgroundColor(background);

        ThemeManager.Palette palette = ThemeManager.current(activity);
        if (splitRoot != null) splitRoot.setBackgroundColor(palette.background);
        if (divider != null) divider.setBackgroundColor(palette.accent);
        styleLabels();

        setClearColor(firstSession, background);
        setClearColor(secondSession, background);
    }

    public void exit() {
        if (!active) return;
        GeckoSession keep = focusFirst ? firstSession : secondSession;
        active = false;

        try {
            release(primaryView);
            release(secondView);

            if (firstPane != null) firstPane.removeView(primaryView);
            if (secondPane != null && secondView != null) secondPane.removeView(secondView);
            if (originalParent != null && splitRoot != null) originalParent.removeView(splitRoot);

            if (originalParent != null) {
                originalParent.addView(primaryView,
                        Math.min(Math.max(0, originalIndex), originalParent.getChildCount()),
                        originalLayoutParams);
            }
            if (keep != null) attach(primaryView, keep);
        } catch (Throwable ignored) {
            restorePrimaryAfterFailure(keep);
        }

        clearSecondaryViews();
        firstSession = null;
        secondSession = null;
        applyTheme(ThemeManager.browserBackground(activity));
        if (keep != null) host.onPaneFocused(keep);
    }

    public void release() {
        exit();
    }

    private void buildViews() {
        splitRoot = new LinearLayout(activity);
        splitRoot.setOrientation(resolveOrientation());

        firstPane = pane();
        secondPane = pane();

        secondView = new GeckoView(activity);
        secondView.setId(View.generateViewId());
        secondView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        primaryView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        firstPane.addView(primaryView);
        secondPane.addView(secondView);

        firstLabel = chip();
        secondLabel = chip();
        firstPane.addView(firstLabel, chipParams());
        secondPane.addView(secondLabel, chipParams());

        divider = new View(activity);
        divider.setOnTouchListener(this::dragDivider);

        primaryView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                setFocusedPane(true, true);
            }
            return false;
        });
        secondView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                setFocusedPane(false, true);
            }
            return false;
        });
        firstLabel.setOnClickListener(view -> setFocusedPane(true, true));
        secondLabel.setOnClickListener(view -> setFocusedPane(false, true));
    }

    private void applyOrientation() {
        if (!active || splitRoot == null) return;
        int desired = resolveOrientation();
        splitRoot.setOrientation(desired);
        splitRoot.removeAllViews();
        splitRoot.addView(firstPane, paneParams(true, desired));
        splitRoot.addView(divider, dividerParams(desired));
        splitRoot.addView(secondPane, paneParams(false, desired));
        applyWeights();
    }

    private void applyWeights() {
        if (!active || firstPane == null || secondPane == null || splitRoot == null) return;
        int orientation = splitRoot.getOrientation();
        firstPane.setLayoutParams(paneParams(true, orientation));
        secondPane.setLayoutParams(paneParams(false, orientation));
        divider.setLayoutParams(dividerParams(orientation));
        splitRoot.requestLayout();
    }

    private LinearLayout.LayoutParams paneParams(boolean first, int orientation) {
        float weight = first ? ratio : 1f - ratio;
        if (orientation == LinearLayout.HORIZONTAL) {
            return new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, weight);
        }
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private LinearLayout.LayoutParams dividerParams(int orientation) {
        int thickness = dp(8);
        if (orientation == LinearLayout.HORIZONTAL) {
            return new LinearLayout.LayoutParams(thickness,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, thickness);
    }

    private boolean dragDivider(View view, MotionEvent event) {
        if (!active || splitRoot == null) return false;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP) return false;

        int[] location = new int[2];
        splitRoot.getLocationOnScreen(location);
        float next;
        if (splitRoot.getOrientation() == LinearLayout.HORIZONTAL) {
            int width = splitRoot.getWidth();
            if (width <= 0) return true;
            next = (event.getRawX() - location[0]) / width;
        } else {
            int height = splitRoot.getHeight();
            if (height <= 0) return true;
            next = (event.getRawY() - location[1]) / height;
        }
        setRatio(next);
        return true;
    }

    private void setFocusedPane(boolean first, boolean notify) {
        if (!active) return;
        focusFirst = first;
        refreshLabels();
        if (notify) {
            GeckoSession target = first ? firstSession : secondSession;
            if (target != null) host.onPaneFocused(target);
        }
    }

    private void attach(GeckoView view, GeckoSession target) {
        if (view == null || target == null) return;
        if (view.getSession() == target) return;

        if (view != primaryView && primaryView.getSession() == target) release(primaryView);
        if (secondView != null && view != secondView && secondView.getSession() == target) {
            release(secondView);
        }

        release(view);
        view.setSession(target);
    }

    private static void release(GeckoView view) {
        if (view == null || view.getSession() == null) return;
        try { view.releaseSession(); } catch (Throwable ignored) { }
    }

    private void restorePrimaryAfterFailure(GeckoSession keep) {
        try {
            if (primaryView.getParent() instanceof ViewGroup) {
                ((ViewGroup) primaryView.getParent()).removeView(primaryView);
            }
            if (splitRoot != null && splitRoot.getParent() instanceof ViewGroup) {
                ((ViewGroup) splitRoot.getParent()).removeView(splitRoot);
            }
            if (originalParent != null && primaryView.getParent() == null) {
                originalParent.addView(primaryView,
                        Math.min(Math.max(0, originalIndex), originalParent.getChildCount()),
                        originalLayoutParams);
            }
            if (keep != null) attach(primaryView, keep);
        } catch (Throwable ignored) { }
        clearSecondaryViews();
    }

    private void clearSecondaryViews() {
        if (secondView != null) {
            release(secondView);
            secondView.setOnTouchListener(null);
        }
        primaryView.setOnTouchListener(null);
        splitRoot = null;
        firstPane = null;
        secondPane = null;
        secondView = null;
        divider = null;
        firstLabel = null;
        secondLabel = null;
    }

    private ViewParentInfo parentInfo() {
        if (!(primaryView.getParent() instanceof LinearLayout)) return null;
        LinearLayout parent = (LinearLayout) primaryView.getParent();
        int index = parent.indexOfChild(primaryView);
        if (index < 0) return null;
        return new ViewParentInfo(parent, index);
    }

    private FrameLayout pane() {
        FrameLayout frame = new FrameLayout(activity);
        frame.setClipToPadding(false);
        return frame;
    }

    private TextView chip() {
        TextView text = new TextView(activity);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setMaxLines(1);
        text.setTextSize(12);
        text.setPadding(dp(10), dp(6), dp(10), dp(6));
        text.setElevation(dp(6));
        return text;
    }

    private FrameLayout.LayoutParams chipParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        params.setMargins(dp(8), dp(8), dp(8), dp(8));
        return params;
    }

    private void styleLabels() {
        if (firstLabel == null || secondLabel == null) return;
        ThemeManager.Palette palette = ThemeManager.current(activity);
        styleLabel(firstLabel, focusFirst, palette);
        styleLabel(secondLabel, !focusFirst, palette);
    }

    private void styleLabel(TextView label, boolean focused, ThemeManager.Palette palette) {
        label.setTextColor(focused ? palette.text : palette.muted);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(withAlpha(palette.surface, 235));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(focused ? 2 : 1),
                focused ? palette.accent : palette.outline);
        label.setBackground(bg);
    }

    private String label(String pane, GeckoSession session) {
        String title = host.titleFor(session);
        if (title == null || title.trim().isEmpty()) title = "Nouvel onglet";
        title = title.trim().replace('\n', ' ');
        if (title.length() > 34) title = title.substring(0, 33) + "\u2026";
        return pane + "  " + title;
    }

    private int resolveOrientation() {
        if (orientationMode == ORIENTATION_HORIZONTAL) return LinearLayout.HORIZONTAL;
        if (orientationMode == ORIENTATION_VERTICAL) return LinearLayout.VERTICAL;
        return activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
    }

    private static LinearLayout.LayoutParams copyLinearParams(ViewGroup.LayoutParams source) {
        if (source instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams old = (LinearLayout.LayoutParams) source;
            LinearLayout.LayoutParams copy =
                    new LinearLayout.LayoutParams(old.width, old.height, old.weight);
            copy.gravity = old.gravity;
            copy.setMargins(old.leftMargin, old.topMargin, old.rightMargin, old.bottomMargin);
            return copy;
        }
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private void setClearColor(GeckoSession session, int color) {
        if (session == null || !session.isOpen()) return;
        try { session.getCompositorController().setClearColor(color); }
        catch (Throwable ignored) { }
    }

    private static float clamp(float value) {
        return Math.max(0.22f, Math.min(0.78f, value));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().isEmpty()
                ? "erreur inconnue" : value;
    }

    private static final class ViewParentInfo {
        final LinearLayout parent;
        final int index;

        ViewParentInfo(LinearLayout parent, int index) {
            this.parent = parent;
            this.index = index;
        }
    }
}
