package com.example.geckobrowser;

import android.app.Activity;
import android.view.MenuItem;
import android.widget.Toast;

import org.mozilla.geckoview.BasicSelectionActionDelegate;
import org.mozilla.geckoview.GeckoSession;

import java.util.Arrays;

/**
 * Barre de selection enrichie basee sur le delegate natif de GeckoView.
 *
 * Les actions Android habituelles restent presentes. Les actions GeckoBrowser
 * ne sont jamais proposees dans un champ de mot de passe.
 */
public final class SmartSelectionDelegate extends BasicSelectionActionDelegate {

    private static final String ACTION_TRANSLATE = "geckobrowser.selection.TRANSLATE";
    private static final String ACTION_SEARCH = "geckobrowser.selection.SEARCH";
    private static final String ACTION_SPEAK = "geckobrowser.selection.SPEAK";
    private static final String ACTION_SHARE = "geckobrowser.selection.SHARE";
    private static final String ACTION_SAVE = "geckobrowser.selection.SAVE";
    private static final String ACTION_MARKDOWN = "geckobrowser.selection.MARKDOWN";

    private static final String[] CUSTOM_ACTIONS = {
            ACTION_TRANSLATE,
            ACTION_SEARCH,
            ACTION_SPEAK,
            ACTION_SHARE,
            ACTION_SAVE,
            ACTION_MARKDOWN
    };

    public interface SearchUrlBuilder {
        String build(String text);
    }

    public interface ValueProvider {
        String get();
    }

    private final Activity activity;
    private final SearchUrlBuilder searchUrlBuilder;
    private final Runnable translateAction;
    private final ValueProvider titleProvider;
    private final ValueProvider urlProvider;

    public SmartSelectionDelegate(Activity activity,
                                  SearchUrlBuilder searchUrlBuilder,
                                  Runnable translateAction,
                                  ValueProvider titleProvider,
                                  ValueProvider urlProvider) {
        super(activity, true);
        this.activity = activity;
        this.searchUrlBuilder = searchUrlBuilder;
        this.translateAction = translateAction;
        this.titleProvider = titleProvider;
        this.urlProvider = urlProvider;
        // Evite de remplir la barre avec les actions de toutes les applications.
        // Partager et lire sont deja fournis ici de facon coherente.
        enableExternalActions(false);
    }

    @Override
    protected String[] getAllActions() {
        String[] standard = super.getAllActions();
        String[] all = Arrays.copyOf(standard, standard.length + CUSTOM_ACTIONS.length);
        System.arraycopy(CUSTOM_ACTIONS, 0, all, standard.length, CUSTOM_ACTIONS.length);
        return all;
    }

    private static boolean isCustom(String id) {
        for (String action : CUSTOM_ACTIONS) {
            if (action.equals(id)) return true;
        }
        return false;
    }

    private String selectedText() {
        GeckoSession.SelectionActionDelegate.Selection selection = getSelection();
        if (selection == null || selection.text == null) return "";
        return selection.text.trim();
    }

    private boolean selectionIsSafe() {
        GeckoSession.SelectionActionDelegate.Selection selection = getSelection();
        if (selection == null) return false;
        if ((selection.flags & FLAG_IS_PASSWORD) != 0) return false;
        return selection.text != null && !selection.text.trim().isEmpty();
    }

    @Override
    protected boolean isActionAvailable(String id) {
        if (!isCustom(id)) return super.isActionAvailable(id);
        return selectionIsSafe();
    }

    @Override
    protected void prepareAction(String id, MenuItem item) {
        if (!isCustom(id)) {
            super.prepareAction(id, item);
            return;
        }

        switch (id) {
            case ACTION_TRANSLATE:
                item.setTitle("Traduire");
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                break;
            case ACTION_SEARCH:
                item.setTitle("Rechercher");
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                break;
            case ACTION_SPEAK:
                item.setTitle("Lire");
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                break;
            case ACTION_SHARE:
                item.setTitle("Partager");
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                break;
            case ACTION_SAVE:
                item.setTitle("Enregistrer");
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                break;
            case ACTION_MARKDOWN:
                item.setTitle("Markdown");
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                break;
            default:
                break;
        }
    }

    @Override
    protected boolean performAction(String id, MenuItem item) {
        if (!isCustom(id)) return super.performAction(id, item);

        String text = selectedText();
        if (text.isEmpty()) return false;

        try {
            switch (id) {
                case ACTION_TRANSLATE:
                    // translate.js relit la selection de la page ; ne pas la supprimer ici.
                    translateAction.run();
                    return true;

                case ACTION_SEARCH:
                    String target = searchUrlBuilder.build(text);
                    if (mSession != null && target != null && !target.isEmpty()) {
                        mSession.loadUri(target);
                    }
                    clearSelection();
                    return true;

                case ACTION_SPEAK:
                    SelectionNotebook.speak(activity, text);
                    clearSelection();
                    return true;

                case ACTION_SHARE:
                    SelectionNotebook.share(activity, text);
                    clearSelection();
                    return true;

                case ACTION_SAVE:
                    SelectionNotebook.save(activity, text,
                            safeValue(titleProvider), safeValue(urlProvider));
                    Toast.makeText(activity, "Citation enregistree", Toast.LENGTH_SHORT).show();
                    clearSelection();
                    return true;

                case ACTION_MARKDOWN:
                    SelectionNotebook.copyMarkdown(activity, text,
                            safeValue(titleProvider), safeValue(urlProvider));
                    Toast.makeText(activity, "Citation Markdown copiee", Toast.LENGTH_SHORT).show();
                    clearSelection();
                    return true;

                default:
                    return false;
            }
        } catch (Throwable error) {
            Toast.makeText(activity, "Action indisponible", Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    private static String safeValue(ValueProvider provider) {
        if (provider == null) return "";
        try {
            String value = provider.get();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
