package com.example.geckobrowser;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

/**
 * Boites de dialogue demandees par les pages : alert, confirm, prompt,
 * authentification HTTP, listes de choix, selection de fichier, quitter la page.
 * Sans ce delegue, GeckoView ignore silencieusement toutes ces demandes.
 */
public class Prompts implements GeckoSession.PromptDelegate {

    public interface FilePicker {
        void pickFile(FilePrompt prompt, GeckoResult<PromptResponse> result);
    }

    private final Context ctx;
    private final FilePicker picker;

    public Prompts(Context ctx, FilePicker picker) {
        this.ctx = ctx;
        this.picker = picker;
    }

    private AlertDialog.Builder base(String title) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        if (title != null && !title.isEmpty()) b.setTitle(title);
        return b;
    }

    // -----------------------------------------------------------------------
    //  alert()
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session, AlertPrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        base(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton("OK", (d, w) -> res.complete(prompt.dismiss()))
            .setOnCancelListener(d -> res.complete(prompt.dismiss()))
            .show();
        return res;
    }

    // -----------------------------------------------------------------------
    //  confirm()
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session, ButtonPrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        base(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton("OK", (d, w) -> res.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)))
            .setNegativeButton("Annuler", (d, w) -> res.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
            .setOnCancelListener(d -> res.complete(prompt.dismiss()))
            .show();
        return res;
    }

    // -----------------------------------------------------------------------
    //  prompt()
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onTextPrompt(GeckoSession session, TextPrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final EditText input = new EditText(ctx);
        input.setSingleLine();
        if (prompt.defaultValue != null) input.setText(prompt.defaultValue);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        if (prompt.message != null && !prompt.message.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(prompt.message);
            tv.setPadding(0, 0, 0, 16);
            box.addView(tv);
        }
        box.addView(input);

        base(prompt.title)
            .setView(box)
            .setPositiveButton("OK", (d, w) -> res.complete(prompt.confirm(input.getText().toString())))
            .setNegativeButton("Annuler", (d, w) -> res.complete(prompt.dismiss()))
            .setOnCancelListener(d -> res.complete(prompt.dismiss()))
            .show();
        return res;
    }

    // -----------------------------------------------------------------------
    //  Authentification HTTP
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession session, AuthPrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean passwordOnly =
            (prompt.authOptions.flags & AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0;

        final EditText user = new EditText(ctx);
        user.setHint("Identifiant");
        user.setSingleLine();

        final EditText pass = new EditText(ctx);
        pass.setHint("Mot de passe");
        pass.setSingleLine();
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        if (prompt.message != null && !prompt.message.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(prompt.message);
            tv.setPadding(0, 0, 0, 16);
            box.addView(tv);
        }
        if (!passwordOnly) box.addView(user);
        box.addView(pass);

        base(prompt.title == null || prompt.title.isEmpty() ? "Authentification" : prompt.title)
            .setView(box)
            .setPositiveButton("Se connecter", (d, w) -> {
                if (passwordOnly) {
                    res.complete(prompt.confirm(pass.getText().toString()));
                } else {
                    res.complete(prompt.confirm(user.getText().toString(), pass.getText().toString()));
                }
            })
            .setNegativeButton("Annuler", (d, w) -> res.complete(prompt.dismiss()))
            .setOnCancelListener(d -> res.complete(prompt.dismiss()))
            .show();
        return res;
    }

    // -----------------------------------------------------------------------
    //  <select> et listes de choix
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final ChoicePrompt.Choice[] choices = prompt.choices;
        final String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i].label == null ? "" : choices[i].label;
        }

        AlertDialog.Builder b = base(prompt.title);

        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            final boolean[] checked = new boolean[choices.length];
            for (int i = 0; i < choices.length; i++) checked[i] = choices[i].selected;

            b.setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
             .setPositiveButton("Valider", (d, w) -> {
                 int n = 0;
                 for (boolean c : checked) if (c) n++;
                 String[] ids = new String[n];
                 int k = 0;
                 for (int i = 0; i < checked.length; i++) if (checked[i]) ids[k++] = choices[i].id;
                 res.complete(prompt.confirm(ids));
             })
             .setNegativeButton("Annuler", (d, w) -> res.complete(prompt.dismiss()));
        } else {
            b.setItems(labels, (d, which) -> res.complete(prompt.confirm(choices[which].id)))
             .setNegativeButton("Annuler", (d, w) -> res.complete(prompt.dismiss()));
        }

        b.setOnCancelListener(d -> res.complete(prompt.dismiss())).show();
        return res;
    }

    // -----------------------------------------------------------------------
    //  <input type="file">
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session, FilePrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        picker.pickFile(prompt, res);
        return res;
    }

    // -----------------------------------------------------------------------
    //  Quitter la page avec des modifications non enregistrees
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession session,
                                                            BeforeUnloadPrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        base("Quitter la page ?")
            .setMessage("Les modifications non enregistrees seront perdues.")
            .setPositiveButton("Quitter", (d, w) -> res.complete(prompt.confirm(AllowOrDeny.ALLOW)))
            .setNegativeButton("Rester", (d, w) -> res.complete(prompt.confirm(AllowOrDeny.DENY)))
            .setOnCancelListener(d -> res.complete(prompt.confirm(AllowOrDeny.DENY)))
            .show();
        return res;
    }

    // -----------------------------------------------------------------------
    //  Renvoi de formulaire
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession session,
                                                             RepostConfirmPrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        base("Renvoyer les donnees ?")
            .setMessage("Cette page necessite de renvoyer les informations du formulaire.")
            .setPositiveButton("Renvoyer", (d, w) -> res.complete(prompt.confirm(AllowOrDeny.ALLOW)))
            .setNegativeButton("Annuler", (d, w) -> res.complete(prompt.confirm(AllowOrDeny.DENY)))
            .setOnCancelListener(d -> res.complete(prompt.confirm(AllowOrDeny.DENY)))
            .show();
        return res;
    }

    // -----------------------------------------------------------------------
    //  Fenetres surgissantes
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession session, PopupPrompt prompt) {
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        base("Fenetre surgissante")
            .setMessage("Ce site souhaite ouvrir :\n" + prompt.targetUri)
            .setPositiveButton("Autoriser", (d, w) -> res.complete(prompt.confirm(AllowOrDeny.ALLOW)))
            .setNegativeButton("Bloquer", (d, w) -> res.complete(prompt.confirm(AllowOrDeny.DENY)))
            .setOnCancelListener(d -> res.complete(prompt.confirm(AllowOrDeny.DENY)))
            .show();
        return res;
    }
}
