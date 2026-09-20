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

## LuckPerms dans l'environnement de test

`run/mods/LuckPerms-NeoForge-5.4.150.jar`, stockage MariaDB (même base que le mod), avec les grades
`default`, `vip`, `moderateur` (mute, warn, history) et `admin` (hérite de modo + ban, unlink, admin).
Le joueur de test `Dev` est modérateur et **n'est pas opérateur**, sans quoi les tests de permission
n'auraient aucune valeur.

## Deux serveurs en parallèle

`./gradlew runServer` (id `test1`, primaire, port 25565, salon `#mc-test`) et `./gradlew runServer2`
(id `test2`, secondaire, port 25566, salon `#mc-test-2`) partagent la même base. Les clients se lancent
avec `runClient` (pseudo `Dev`) et `runClient2` (pseudo `Dev2`).

RCON est actif sur 25575 et 25576 pour piloter les serveurs sans client.

## Couverture (suite)

`ReportsAndChatTest` :

- les signalements répétés sur un même joueur sont regroupés, mais un signalement déjà traité n'absorbe
  pas les suivants ;
- un signalement ne peut être traité qu'une fois, même si deux modérateurs cliquent ensemble ;
- le chat inter-serveurs ne remonte que les messages des autres serveurs, et n'est pas rejoué ;
- la détection d'adresse ne remonte que les comptes **bannis**, pas les colocataires ;
- les empreintes d'adresse sont oubliées après le délai.

`WebhookUrlTest` : les deux formes d'URL de webhook (avec ou sans numéro de version) sont acceptées.

## Reste à tester (nécessite un bot Discord et un client Minecraft)

- chat dans les deux sens, blocage des mentions `@everyone`
- affichage du code à la connexion, clic pour copier, expulsion en fin de délai
- `/verify` sur Discord de bout en bout
- bans, tempbans, mutes, expirations réelles, propagation entre deux serveurs
- bans faits directement sur Discord et répercutés en jeu
