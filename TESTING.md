# Tests

## Base de test locale

MariaDB 13 est installé sur la machine de dev, dans `C:\Program Files\MariaDB 13.0`, **sans service
Windows** : il faut le démarrer à la main avant les tests.

```bash
"/c/Program Files/MariaDB 13.0/bin/mariadbd.exe" --defaults-file="C:/Program Files/MariaDB 13.0/data/my.ini" --console
```

Bases et utilisateur déjà créés (identifiants de développement uniquement, jamais en production) :

| Élément | Valeur |
|---|---|
| Bases | `arcadia_test`, `arcadia_test2` (simule un second serveur sur la base partagée) |
| Utilisateur | `arcadia` / `arcadia_dev` |
| Hôte | `localhost:3306` |

Pour pointer vers une autre base, définir `AB_TEST_HOST`, `AB_TEST_PORT`, `AB_TEST_DB`, `AB_TEST_USER`,
`AB_TEST_PASSWORD`.

## Lancer les tests

```bash
./gradlew test
```

Si la base n'est pas joignable, les tests d'intégration sont ignorés au lieu d'échouer.

## Couverture actuelle

`VerificationFlowTest` — le cœur de sécurité du mod :

- un code valide crée la liaison, puis **ne peut plus resservir** (rejeu impossible) ;
- un code expiré est refusé ;
- un compte Minecraft déjà lié ne peut pas être re-lié par un autre Discord ;
- un compte Discord ne peut pas prendre un second compte Minecraft ;
- quatre `/verify` simultanés avec le même code : **un seul gagne** ;
- suppression de liaison et purge des codes expirés.

## Reste à tester (nécessite un bot Discord et un client Minecraft)

- chat dans les deux sens, blocage des mentions `@everyone`
- affichage du code à la connexion, clic pour copier, expulsion en fin de délai
- `/verify` sur Discord de bout en bout
- bans, tempbans, mutes, expirations réelles, propagation entre deux serveurs
- bans faits directement sur Discord et répercutés en jeu
