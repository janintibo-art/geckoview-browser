#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ajoute le centre de telechargements Android a GeckoBrowser."""

from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/geckobrowser"
MAIN = JAVA / "MainActivity.java"
DOWNLOADS = JAVA / "Downloads.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
MARKER = "DOWNLOAD_CENTER_V1"


def fail(message: str) -> None:
    print(f"ERREUR: {message}", file=sys.stderr)
    sys.exit(1)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"ancre {label!r} trouvee {count} fois au lieu d'une")
    return text.replace(old, new, 1)


def backup(path: Path, suffix: str) -> None:
    target = path.with_name(path.name + suffix)
    if not target.exists():
        shutil.copy2(path, target)


for path in (MAIN, DOWNLOADS, MANIFEST):
    if not path.is_file():
        fail(f"fichier introuvable: {path}")

if not (JAVA / "DownloadCenter.java").is_file():
    fail("DownloadCenter.java manque dans l'overlay")
if not (JAVA / "DownloadCompleteReceiver.java").is_file():
    fail("DownloadCompleteReceiver.java manque dans l'overlay")

main = MAIN.read_text(encoding="utf-8")
downloads = DOWNLOADS.read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")

if MARKER in main:
    print("Centre de telechargements deja installe.")
    sys.exit(0)

for required in ("MEDIA_HUB_V1", "TAB_WORKSPACE_V1", "SmartSelectionDelegate"):
    if required not in main:
        fail(f"MainActivity.java ne contient pas la version attendue: {required}")

backup(MAIN, ".before-download-center")
backup(DOWNLOADS, ".before-download-center")
backup(MANIFEST, ".before-download-center")

# ---------------------------------------------------------------------------
# Entree dans le menu principal.
# ---------------------------------------------------------------------------
main = replace_once(
    main,
    '''            .sub("\\u25B6", "Multimedia", mediaHub.summary(),
                 () -> mediaHub.showMenu(this::showMenu))''',
    '''            .sub("\\u25B6", "Multimedia", mediaHub.summary(),
                 () -> mediaHub.showMenu(this::showMenu))
            // DOWNLOAD_CENTER_V1 — file systeme, progression et historique.
            .sub("\\u21E9", "Telechargements", DownloadCenter.summary(this),
                 () -> DownloadCenter.show(this, this::showMenu))''',
    "menu principal",
)

# ---------------------------------------------------------------------------
# Les flux livres directement par Gecko restent ecrits par Downloads.java,
# mais leur resultat apparait maintenant dans l'historique unifie.
# ---------------------------------------------------------------------------
downloads = replace_once(
    downloads,
    '''                long written = write(ctx, response, name, mime);
                message = "Enregistre : " + name + " (" + human(written) + ")";
            } catch (Exception e) {
                message = "Echec du telechargement : " + e.getMessage();
            }''',
    '''                long written = write(ctx, response, name, mime);
                DownloadCenter.recordDirectCompleted(ctx, name, mime, written,
                        "", response.uri, false);
                message = "Enregistre : " + name + " (" + human(written) + ")";
            } catch (Exception e) {
                DownloadCenter.recordDirectFailed(ctx, name, response.uri,
                        e.getMessage(), false);
                message = "Echec du telechargement : " + e.getMessage();
            }''',
    "flux Gecko",
)

# Les URL ordinaires passent par DownloadManager. Le chemin Tor existant est
# conserve, car DownloadManager ne sait pas utiliser le proxy SOCKS d'Orbot.
downloads = replace_once(
    downloads,
    '''        final android.os.Handler ui = new android.os.Handler(ctx.getMainLooper());

        toast(ctx, ui, urls.length + " fichier(s) en telechargement"
                + (tor ? " via Tor" : ""));''',
    '''        final android.os.Handler ui = new android.os.Handler(ctx.getMainLooper());

        if (!tor) {
            int queued = DownloadCenter.enqueueUrls(ctx, urls, referer);
            toast(ctx, ui, queued + " fichier(s) ajoute(s) au centre de telechargements");
            return;
        }

        toast(ctx, ui, urls.length + " fichier(s) en telechargement via Tor");''',
    "routage DownloadManager",
)

downloads = replace_once(
    downloads,
    '''                    fetchToDownloads(ctx, url, referer, tor);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    ko.incrementAndGet();
                }''',
    '''                    fetchToDownloads(ctx, url, referer, tor);
                    DownloadCenter.recordDirectCompleted(ctx,
                            nameFrom(url, null, null), null, -1, "", url, true);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    DownloadCenter.recordDirectFailed(ctx,
                            nameFrom(url, null, null), url, e.getMessage(), true);
                    ko.incrementAndGet();
                }''',
    "historique Tor",
)

# Les exports texte deviennent eux aussi visibles dans le centre.
downloads = replace_once(
    downloads,
    '''                writeStream(ctx, new ByteArrayInputStream(data),
                        sanitize(name), "text/plain");
                msg = "Enregistre : " + name;''',
    '''                long written = writeStream(ctx, new ByteArrayInputStream(data),
                        sanitize(name), "text/plain");
                DownloadCenter.recordDirectCompleted(ctx, sanitize(name),
                        "text/plain", written, "", "", false);
                msg = "Enregistre : " + name;''',
    "exports texte",
)

# ---------------------------------------------------------------------------
# Recepteur de fin de telechargement Android.
# ---------------------------------------------------------------------------
receiver = '''
        <receiver
            android:name=".DownloadCompleteReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.DOWNLOAD_COMPLETE" />
            </intent-filter>
        </receiver>
'''

if ".DownloadCompleteReceiver" not in manifest:
    pattern = re.compile(
        r'(\s*<receiver\s+android:name="\.MediaActionReceiver"\s+' 
        r'android:exported="false"\s*/>)',
        re.S,
    )
    manifest, count = pattern.subn(r'\1\n' + receiver, manifest, count=1)
    if count != 1:
        fail("ancre du recepteur multimedia introuvable dans AndroidManifest.xml")

MAIN.write_text(main, encoding="utf-8")
DOWNLOADS.write_text(downloads, encoding="utf-8")
MANIFEST.write_text(manifest, encoding="utf-8")

print("Centre de telechargements installe.")
print("Sauvegardes creees avec le suffixe .before-download-center")
