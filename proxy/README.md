# Filet de sécurité VibeCraft : Velocity + limbo

Pendant la conférence, le serveur principal (Paper + plugin VibeCraft) peut planter, être tué ou
redémarré. Sans protection, tous les joueurs sont déconnectés et doivent se reconnecter à la main.
Ce dossier ajoute devant le serveur un proxy **Velocity** et un petit serveur d'attente, le
**limbo**. Quand le serveur principal tombe, les joueurs restent connectés : ils sont déplacés dans
le limbo (titre « Le serveur redémarre… ») puis **renvoyés automatiquement** sur le serveur
principal dès qu'il répond de nouveau.

```
 joueurs ──► Velocity  0.0.0.0:25577  ──►  main  = serveur principal  127.0.0.1:25565  (server/)
             + plugin VibeReconnect    └─►  limbo = Paper minimal      127.0.0.1:25567  (proxy/limbo/)
```

- **Velocity 4.2.0** (clients 26.1 à 26.3), *modern forwarding* avec un secret partagé.
- **limbo** : Paper 26.3 copié du serveur principal, monde plat vide (barrière + verre), mode
  aventure, distance de vue 2, 768 Mo de RAM max, ViaVersion + ViaBackwards (les clients plus
  anciens acceptés par le serveur principal tiennent aussi dans le limbo).
- **VibeReconnect** (`vibe-reconnect/`) : plugin Velocity qui surveille le serveur principal
  (ping toutes les 2 s), envoie les joueurs au limbo et les ramène.

## Ce qui est protégé

| Situation sur le serveur principal | Ce que voient les joueurs |
|---|---|
| JVM tuée / crash (`kill`, OOM, crash Java) | 0,1 à 6 s après (le temps que Windows ferme les sockets de la JVM morte) : limbo + titre. Retour automatique 1 à 5 s après que le serveur a fini de démarrer (« Done »). |
| Arrêt propre (`stop`, `/restart`, redémarrage pour mettre à jour le plugin) | Moins de 1,5 s : limbo. Même retour automatique. |
| Serveur **figé** (JVM vivante, thread principal bloqué > 10 s) | Évacuation vers le limbo après 10 s de silence total (sinon Velocity/le client coupent à 30 s). Retour automatique dès que le serveur repart ; au-delà de 60 s, le watchdog de Paper tue la JVM et `run-main-forever.ps1` la relance (voir « Comment ça marche », point 4). |
| Joueur qui se connecte pendant la panne | Arrive directement dans le limbo, puis rejoint le serveur principal quand il revient. |
| Serveur principal qui redémarre en boucle | Les joueurs attendent dans le limbo ; les retours ratés sont réessayés avec un délai croissant (max 30 s). |

## Ce qui n'est PAS protégé

- **Velocity lui-même** (ou le PC, ou le réseau) : s'il tombe, tout le monde est déconnecté.
  Velocity est volontairement minuscule (aucune logique VibeCraft dedans) pour que ça n'arrive pas.
- **Le limbo éteint** : si le limbo ne tourne pas au moment de la panne, Velocity déconnecte les
  joueurs comme d'habitude (`vibeproxy status` et le log Velocity le signalent : « Limbo HORS LIGNE »).
- **Les kicks volontaires** : `/kick`, ban, liste blanche, AFK... déconnectent vraiment le joueur
  (liste `deliberate-kick-patterns` dans la config de VibeReconnect).
- **L'état du monde** : un crash/kill perd ce qui n'était pas encore sauvegardé (autosave) ; les
  joueurs reviennent avec l'état relu sur disque. Tout ce qui vivait en mémoire dans le serveur
  principal (modules chargés, jobs IA en cours...) redémarre avec lui.
- **Pendant l'attente** : dans le limbo, le chat et les commandes vont au limbo, pas au serveur
  principal (seule `/vibeproxy status` est utile).
- **Un gel de moins de 10 s** : les joueurs voient juste un lag, rien n'est fait.
- Le proxy ne redémarre pas le serveur principal : c'est le rôle de `run-main-forever.ps1`.

## Installation (une fois)

```powershell
powershell -ExecutionPolicy Bypass -File proxy\setup-proxy.ps1
```

Idempotent. Télécharge Velocity (SHA-256 vérifié) et ViaVersion/ViaBackwards dans
`proxy\downloads\`, copie Paper depuis `server\` vers `proxy\limbo\` (le vrai serveur est
seulement **lu**), crée le secret `proxy\velocity\forwarding.secret` et compile VibeReconnect avec
le JDK portable (`runtime\jdk-25`). Prérequis : avoir démarré une fois `server\` (EULA acceptée,
`config\paper-global.yml` généré). `start-proxy.ps1` le lance tout seul s'il manque quelque chose.

Tous les scripts marchent avec Windows PowerShell 5.1 et PowerShell 7 (`pwsh`). Si l'exécution de
scripts est bloquée : `powershell -ExecutionPolicy Bypass -File proxy\<script>.ps1 ...`.

## Passer le vrai serveur en mode proxy... et revenir

> Ces deux scripts modifient `server\server.properties` et `server\config\paper-global.yml`.
> Ils ne prennent effet qu'au **prochain démarrage** du serveur : arrêtez-le d'abord (ou redémarrez-le
> ensuite). Pour essayer sans risque, utilisez une copie : `-ServerDir D:\copie\server`.

**Activer** :

```powershell
proxy\enable-proxy-mode.ps1                    # le serveur garde le port 25565, joueurs sur 25577
proxy\enable-proxy-mode.ps1 -MainPort 25566     # variante : libère 25565 pour Velocity (voir plus bas)
```

Ce que ça change :

| Fichier | Clé | Valeur en mode proxy |
|---|---|---|
| `server.properties` | `server-ip` | `127.0.0.1` (seul Velocity, sur le même PC, peut le joindre) |
| | `server-port` | inchangé, ou `-MainPort` |
| | `online-mode` | `false` (l'authentification se fait sur Velocity) |
| | `network-compression-threshold` | `-1` (Velocity compresse déjà) |
| `config\paper-global.yml` | `proxies.velocity.enabled` | `true` |
| | `proxies.velocity.online-mode` | `false` (ou `true` avec `-OnlineMode`) |
| | `proxies.velocity.secret` | contenu de `proxy\velocity\forwarding.secret` |

Les valeurs d'origine sont sauvegardées une seule fois dans `server\vibeproxy-backup.json`.
Relancer le script est sans danger (la sauvegarde d'origine est conservée).

Conséquence importante : en mode proxy, **Paper refuse les connexions directes**. Tout ce qui se
connectait à `localhost:25565` comme un joueur (bots mineflayer de `tests/e2e`, client de test...)
doit passer par Velocity (`localhost:25577`). RCON (25575, désormais sur 127.0.0.1) et l'API
VibeCraft (127.0.0.1:8765) ne changent pas.

**Désactiver** (retour exact à l'état d'avant, octet pour octet) :

```powershell
proxy\stop-proxy.ps1                 # libère le port du proxy
proxy\disable-proxy-mode.ps1         # restaure les valeurs de vibeproxy-backup.json puis le supprime
```

Sans sauvegarde, `disable-proxy-mode.ps1` remet `server-port=25565`, `server-ip=` vide et
`proxies.velocity.enabled=false`.

## Démarrer

Deux fenêtres PowerShell :

```powershell
# Fenêtre 1 : limbo (caché) + Velocity (console dans cette fenêtre : "vibeproxy status", "end"...)
proxy\start-proxy.ps1
# ou tout en arrière-plan (logs dans proxy\run\) :
proxy\start-proxy.ps1 -Background

# Fenêtre 2 : serveur principal, redémarré automatiquement quand il s'arrête
proxy\run-main-forever.ps1                 # -Xmx 4G et options JVM de scripts\start-server.ps1 ; -Xmx 6G, -ServerDir ... possibles
```

`start-proxy.ps1` lit l'adresse du serveur principal dans `server\server.properties`
(`-ServerDir`, `-MainHost`, `-MainPort` pour forcer) et **avertit si le serveur n'est pas en mode
proxy** (sinon les joueurs resteraient coincés dans le limbo). Options : `-ProxyPort` (25577),
`-BindAddress` (0.0.0.0), `-LimboPort` (25567), `-LimboRconPort` (25568), `-OnlineMode`,
`-VelocityXmx` (512M), `-LimboXmx` (768M).

`run-main-forever.ps1` : la console du serveur est la fenêtre elle-même (commandes tapées
normalement). Quand le serveur s'arrête (crash, `stop`, `/restart`, JVM tuée), il est relancé après
2 s ; protection anti-boucle : 5 arrêts de moins de 30 s d'affilée → pause de 30 s.
`scripts\start-server.ps1` fonctionne aussi en mode proxy, mais sans redémarrage automatique (et
son message « localhost (port 25565) » ne vaut plus pour les joueurs, qui passent par Velocity).

**Arrêter :**

| Quoi | Comment |
|---|---|
| Serveur principal, pour de bon | `Ctrl+C` dans sa fenêtre (arrêt propre, pas de redémarrage), ou créer `proxy\run\stop-main.flag` puis `stop` |
| Serveur principal, pour le redémarrer | `stop` (ou `/restart`) : les joueurs attendent dans le limbo et reviennent seuls |
| Proxy + limbo | `end` dans la console Velocity / `Ctrl+C`, ou `proxy\stop-proxy.ps1` (mode `-Background`) |

`stop-proxy.ps1` ne touche qu'aux processus dont le PID est dans `proxy\run\*.pid` (PID + heure de
démarrage, jamais un autre java).

## Comment les joueurs se connectent

- **Par défaut** : `<IP du PC>:25577` (le port Velocity). Le serveur principal reste sur 25565
  mais n'écoute plus que sur 127.0.0.1 : impossible de le contourner depuis une autre machine.
- **Garder le port habituel 25565** (rien à changer côté joueurs) : déplacer le serveur principal
  et mettre Velocity à sa place.

  ```powershell
  proxy\enable-proxy-mode.ps1 -MainPort 25566
  proxy\start-proxy.ps1 -ProxyPort 25565
  proxy\run-main-forever.ps1
  ```

  (`start-proxy.ps1` relit 25566 dans `server.properties`.) Le pare-feu Windows doit autoriser le
  port choisi pour `java.exe` du JDK.

## Commandes et état

- `/vibeproxy status` (tout le monde, aussi depuis le limbo) ou `vibeproxy status` dans la console
  Velocity : état du serveur principal et du limbo, joueurs en attente, statistiques.
- `vibeproxy return [joueur|all]` (console / permission `vibeproxy.admin`) : renvoie tout de suite
  depuis le limbo.
- `vibeproxy reload` : relit la configuration.
- `proxy\velocity\plugins\vibereconnect\status.json` : même état en JSON, réécrit toutes les 2 s
  (lisible par un dashboard ou un script).

## Configuration de VibeReconnect

`proxy\velocity\plugins\vibereconnect\config.properties` (créé au premier démarrage à partir de
`vibe-reconnect\src\main\resources\config.properties`, puis `vibeproxy reload`) :

| Clé | Défaut | Rôle |
|---|---|---|
| `ping-interval-ms` / `ping-timeout-ms` | 2000 / 1500 | surveillance du serveur principal |
| `required-successful-pings` | 1 | pings OK avant de le déclarer « en ligne » |
| `min-limbo-ms` | 2000 | temps minimal dans le limbo |
| `force-return-after-ms` | 15000 | renvoi tenté même si le serveur n'a jamais été vu hors ligne (kick inattendu, gel) |
| `return-stagger-ms` | 250 | délai entre deux joueurs renvoyés (pas de ruée sur un serveur qui démarre) |
| `return-backoff-max-ms` | 30000 | délai max entre deux tentatives ratées |
| `freeze-guard` / `freeze-threshold-ms` | true / 10000 | détection de serveur figé |
| `deliberate-kick-patterns` | kick, ban, whitelist, AFK... | raisons de kick qui déconnectent vraiment |
| `title`, `subtitle`, `actionbar-*`, `back-*`, `*-message` | textes français | messages MiniMessage |

Velocity et le limbo sont configurés par les modèles `config\velocity.toml.tmpl` et
`config\limbo-server.properties.tmpl`, rendus à chaque `start-proxy.ps1` : modifier les modèles,
pas les fichiers générés. Réglage clé de Velocity : `failover-on-unexpected-server-disconnect = true`.

## Comment ça marche

1. **Panne** : Velocity perd la connexion au serveur principal (kick « Server closed » à l'arrêt
   propre, connexion coupée pour un crash). VibeReconnect intercepte `KickedFromServerEvent` et
   redirige le joueur vers le limbo, au lieu de le déconnecter.
2. **Attente** : titre « Le serveur redémarre… », barre d'action avec le temps d'attente. Le
   serveur principal est pingé toutes les 2 s.
3. **Retour** : un joueur repart quand le serveur principal a été vu **hors ligne puis en ligne**
   (pas de retour prématuré vers un serveur en train de s'arrêter), les joueurs étant échelonnés de
   250 ms. Une tentative ratée laisse le joueur dans le limbo et est refaite plus tard.
4. **Serveur figé** : un serveur dont le thread principal est bloqué répond encore aux pings mais
   n'envoie plus rien aux joueurs. VibeReconnect compte les octets reçus du serveur principal pour
   chaque joueur (handler Netty via l'API interne de Velocity ; si elle change, la détection se
   désactive avec un avertissement et le reste fonctionne). Si **aucun** joueur n'a rien reçu depuis
   10 s, tout le monde est évacué vers le limbo. Comme le serveur figé répond toujours aux pings, le
   retour est tenté `force-return-after-ms` (15 s) après l'évacuation : cette connexion reste en
   attente tant que le serveur est figé (le joueur, lui, est toujours dans le limbo) et aboutit dès
   qu'il repart. Si le gel dure encore 30 s (read-timeout de Velocity), la tentative échoue
   silencieusement et est refaite. À 60 s de gel, le watchdog de Paper (`spigot.yml`
   `timeout-time: 60`) arrête la JVM (code 70) : `run-main-forever.ps1` la relance et les joueurs
   reviennent comme après un crash. (Le moteur VibeCraft a en plus son propre garde, `guard.*` dans
   la config du plugin, prévu pour interrompre bien avant le code de module trop long.)

## Tests

Le test ne touche jamais `server\` : il utilise `proxy\test-main\`, une copie jetable créée par :

```powershell
pwsh proxy\test\setup-test-main.ps1          # 127.0.0.1:25581, RCON 25591, mode proxy, plugin de test VibeFreezeTest
cd proxy\test; npm install                   # mineflayer
node failover-test.mjs --start-proxy --stop-proxy           # Velocity 25577, limbo 25582 (RCON 25592)
node failover-test.mjs --start-proxy --stop-proxy --supervisor   # le redémarrage est fait par run-main-forever.ps1
```

Options : `--rounds 3 --bots 3 --modes kill,stop --freeze-s 25 --idle-s 15 --no-extras --max-return-s 10`
(`npm test` et `npm run test:supervisor` dans `proxy\test\` lancent les deux variantes).

Des bots mineflayer (client 26.1, traduit par ViaVersion) jouent à travers Velocity pendant que
le test, à chaque tour, **tue la JVM** du serveur de test (uniquement le PID qu'il a lancé) puis
l'**arrête proprement** (`stop` par RCON) et la relance. Il vérifie : aucun `end`/`kicked` côté bot,
arrivée dans le limbo (`list` du limbo), titre reçu, `/vibeproxy status` depuis le limbo, retour
sur le serveur principal ≤ 10 s après « Done », message reçu du serveur principal après le retour,
exactement 2 changements de serveur par bot (pas de ping-pong), et dans le log de Velocity : un
seul passage au limbo et un seul retour par bot, aucun kick du limbo, aucun retour raté. Avant les
tours : 15 s d'inactivité sur le serveur principal sans fausse détection de gel. Puis :
connexion pendant la panne, `/kick` volontaire (vraie déconnexion), serveur figé
(`/vibefreeze <s>`). Rapport JSON dans `proxy\test\reports\`.

**Résultats du 8 octobre 2026** (Windows 11, Velocity 4.2.0, Paper 26.3 build 159, ports de test) :

| Lancement | Vérifications | Bascule vers le limbo (kill / stop) | Retour après « Done » |
|---|---|---|---|
| 3 tours kill + stop, 3 bots | 90/90 | 0,2-1,5 s / 0,3-0,5 s | 1,4-1,8 s |
| `--supervisor`, 3 tours kill + stop, 2 bots | 91/91 | 0,1-1,3 s / 0,2-0,4 s | 0,9-1,5 s |
| `--supervisor --freeze-s 90`, 3 tours, 2 bots | 91/91 | 0,1-2,0 s / 0,3-0,6 s | 1,0-3,1 s |
| même test plus tôt, PC chargé à 100 % CPU | 89/89 | jusqu'à 5,7 s | jusqu'à 4,6 s (redémarrage du serveur : 40-65 s) |

Aucun bot n'a jamais été déconnecté. Joueur arrivé pendant la panne : rejoint le serveur principal
1,9-6,3 s après « Done ». Gels mesurés : 25 s → évacuation à 11 s, retour à 25-28 s ; 40 s →
évacuation à 11 s, retour à 40,7 s (la tentative en attente aboutit dès la fin du gel) ; 90 s →
évacuation à 11 s, JVM arrêtée par le watchdog de Paper à ~62 s (code 70), relancée par
`run-main-forever.ps1`, joueurs de retour à 80-89 s. Pendant les tests rapides du superviseur, la
protection anti-boucle s'est déclenchée (5 vies de moins de 30 s) : 30 s de pause, joueurs
patients dans le limbo, puis retour normal.

Note mineflayer : les serveurs 26.x n'acceptent qu'un paquet de position par tick client, tick
délimité par le paquet `tick_end` que mineflayer 4.39 n'envoie jamais ; sans correctif, le bot est
expulsé (`invalid_player_movement`) ~1 s après son arrivée. `test\lib\tick-end-fix.mjs` envoie
`tick_end` après chaque paquet de mouvement (les vrais clients le font déjà). Les messages
« moved too quickly! » dans le log du limbo au moment du changement de serveur sont aussi un
artefact de mineflayer (le bot renvoie son ancienne position ; il est simplement re-téléporté).

## Fichiers

```
proxy/
  README.md                       ce fichier
  setup-proxy.ps1                 installation (idempotente)
  start-proxy.ps1 / stop-proxy.ps1
  run-main-forever.ps1            superviseur du serveur principal
  enable-proxy-mode.ps1 / disable-proxy-mode.ps1
  lib/common.ps1                  fonctions partagées (PID, RCON, édition server.properties / paper-global.yml)
  config/*.tmpl                   modèles velocity.toml et server.properties du limbo
  vibe-reconnect/                 sources du plugin Velocity (+ build.ps1 / build.sh)
  test/                           failover-test.mjs, setup-test-main.ps1, lib/, freeze-plugin/ (plugin de TEST)
  -- générés, ignorés par git --
  velocity/                       velocity.jar, velocity.toml rendu, forwarding.secret (SECRET), plugins/
  limbo/                          Paper du limbo, monde limbo/, .rcon-password (SECRET)
  downloads/  run/ (logs + PID)  test-main/  test/node_modules/ test/logs/ test/reports/
```

## Dépannage

- **Les joueurs restent dans le limbo** : `vibeproxy status`. « HORS LIGNE » → le serveur
  principal ne tourne pas ou pas à l'adresse attendue (`proxy\velocity\velocity.toml`, section
  `[servers]`). « EN LIGNE » mais retours ratés → le plus souvent le serveur n'est pas en mode proxy
  ou n'a pas le même secret (« If you wish to use IP forwarding... ») : `enable-proxy-mode.ps1` puis
  redémarrer le serveur.
- **« Le port 25577 est déjà utilisé »** : un autre Velocity tourne (`proxy\stop-proxy.ps1`).
- **Connexion directe refusée sur 25565** : normal en mode proxy, passer par Velocity.
- **Lancement par un autre programme** (agent, CI, node...) : utiliser `-Background` /
  `-Hidden`. Les JVM reçoivent alors une entrée standard vide (`proxy\run\empty.stdin`) : si elles
  héritaient d'un stdin en pipe partagé avec le PowerShell parent, Windows bloquait le démarrage de
  Velocity jusqu'à la fin de ce PowerShell (« Velocity n'a pas démarré »). En mode console normal
  (fenêtre PowerShell), rien ne change : on tape les commandes dans la console.
- **Logs** : `proxy\run\velocity.log`, `proxy\run\limbo.log` (mode `-Background`),
  `proxy\velocity\logs\latest.log`, `proxy\limbo\logs\latest.log`.
