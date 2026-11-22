# Remote — télécommande custom du lecteur Tidal

Première brique : piloter le **vrai lecteur Tidal** (qui tourne en fond) depuis ta propre interface, sans ouvrir Tidal.
+ un écran **Diagnostic** qui dit si Tidal accepte qu'on lance un titre précis — c'est ce test qui décide de la suite.

## Installer sur ton Samsung

1. Installe **Android Studio** (PC ou Mac) → https://developer.android.com/studio
2. Dézippe le dossier, puis **File → Open** → choisis le dossier `Remote`.
   Laisse-le synchroniser (il télécharge Gradle + les dépendances la 1re fois, quelques minutes).
3. Sur le téléphone : active le **mode développeur**
   (Paramètres → À propos du téléphone → Informations sur le logiciel → tape 7× sur « Numéro de version »),
   puis **Options de développement → Débogage USB**.
4. Branche le téléphone en USB, accepte la popup, et clique sur ▶ **Run** dans Android Studio.

> Installer via Android Studio évite le blocage « paramètre restreint » d'Android 13+.
> Si tu installes un APK à la main et que l'autorisation est grisée :
> Paramètres → Applis → Remote → ⋮ → **Autoriser les paramètres restreints**.

## Premier lancement

1. « Ouvrir les réglages » → active **Remote** dans l'accès aux notifications → reviens.
2. Lance un son dans Tidal, reviens dans Remote : pochette, titre, barre de progression et boutons doivent répondre.

## Le test qui compte

Dans la carte **Diagnostic** :

- **Test 1** : tape « titre + artiste » → *Lancer dans Tidal*.
- **Test 2** : dans Tidal, ⋯ sur un titre → Partager → Copier le lien → colle-le → *Lancer dans Tidal*.

Regarde le **Journal** : si une ligne « Titre → … » apparaît avec le bon son, c'est gagné.

| Résultat | Ce que ça veut dire pour l'appli finale |
|---|---|
| Test 2 marche | Le top : recherche/biblio via l'API Tidal officielle → on lance le titre exact par son lien, sans jamais ouvrir Tidal |
| Seul le test 1 marche | Ça marche aussi, mais on lance « le meilleur résultat » d'une recherche, moins précis |
| Aucun ne marche | On garde la télécommande + on ouvre Tidal une fraction de seconde juste pour démarrer un titre |

Envoie-moi une capture du Diagnostic après les tests, et je construis la suite en fonction.

## Fichiers

- `TidalRemote.kt` — le pont vers la session média de Tidal (état + commandes + tests)
- `MediaListenerService.kt` — juste là pour obtenir l'autorisation Android (ne lit pas tes notifs)
- `ui/RemoteScreen.kt` — l'interface (verre dépoli, fond flouté avec la pochette)

Le nom « Remote » et l'icône sont provisoires.
