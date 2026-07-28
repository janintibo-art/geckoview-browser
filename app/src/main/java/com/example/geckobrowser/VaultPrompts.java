package com.example.geckobrowser;

import android.content.Context;
import android.widget.TextView;

import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

/**
 * Complete le delegue de boites de dialogue avec les invites de connexion.
 */
public final class VaultPrompts extends Prompts {

    public interface PrivateChecker {
        boolean isPrivate(GeckoSession session);
    }

    private final Context context;
    private final PasswordVault vault;
    private final PrivateChecker privateChecker;

    public VaultPrompts(Context context, FilePicker picker, PasswordVault vault,
                        PrivateChecker privateChecker) {
        super(context, picker);
        this.context = context;
        this.vault = vault;
        this.privateChecker = privateChecker;
    }

    @Override
    public GeckoResult<PromptResponse> onLoginSave(
            GeckoSession session,
            AutocompleteRequest<Autocomplete.LoginSaveOption> request) {
        if (request == null || request.options == null || request.options.length == 0
                || vault == null || !vault.isSaveEnabled()
                || (privateChecker != null && privateChecker.isPrivate(session))) {
            return GeckoResult.fromValue(request == null ? null : request.dismiss());
        }

        Autocomplete.LoginSaveOption option = request.options[0];
        Autocomplete.LoginEntry login = option == null ? null : option.value;
        if (login == null || vault.isBlocked(login.origin)) {
            return GeckoResult.fromValue(request.dismiss());
        }

        GeckoResult<PromptResponse> result = new GeckoResult<>();
        boolean update = vault.hasLogin(login);
        String user = login.username == null || login.username.isEmpty()
                ? "Sans identifiant" : login.username;
        String host = PasswordVault.displayHost(login.origin);

        Menus.dialog(context)
                .setTitle(update ? "Mettre a jour le mot de passe ?"
                        : "Enregistrer le mot de passe ?")
                .setMessage(host + "\n" + user)
                .setPositiveButton(update ? "Mettre a jour" : "Enregistrer",
                        (dialog, which) -> result.complete(request.confirm(option)))
                .setNeutralButton("Jamais pour ce site", (dialog, which) -> {
                    vault.blockOrigin(login.origin);
                    result.complete(request.dismiss());
                })
                .setNegativeButton("Pas maintenant",
                        (dialog, which) -> result.complete(request.dismiss()))
                .setOnCancelListener(dialog -> result.complete(request.dismiss()))
                .show();
        return result;
    }

    @Override
    public GeckoResult<PromptResponse> onLoginSelect(
            GeckoSession session,
            AutocompleteRequest<Autocomplete.LoginSelectOption> request) {
        if (request == null || request.options == null || request.options.length == 0) {
            return GeckoResult.fromValue(request == null ? null : request.dismiss());
        }

        GeckoResult<PromptResponse> result = new GeckoResult<>();
        String[] labels = new String[request.options.length];
        for (int i = 0; i < request.options.length; i++) {
            Autocomplete.LoginSelectOption option = request.options[i];
            Autocomplete.LoginEntry login = option == null ? null : option.value;
            if (login == null) {
                labels[i] = "Compte inconnu";
            } else {
                String user = login.username == null || login.username.isEmpty()
                        ? "Sans identifiant" : login.username;
                labels[i] = user + "\n" + PasswordVault.displayHost(login.origin);
            }
        }

        Menus.dialog(context)
                .setTitle("Choisir un compte")
                .setItems(labels, (dialog, which) ->
                        result.complete(request.confirm(request.options[which])))
                .setNegativeButton("Annuler",
                        (dialog, which) -> result.complete(request.dismiss()))
                .setOnCancelListener(dialog -> result.complete(request.dismiss()))
                .show();
        return result;
    }
}
