# Workspace VibeCraft

Ce dossier est l'espace de travail des IA qui codent en direct sur le serveur Minecraft.
Chaque fonctionnalité est un **module** Java rechargé à chaud : on enregistre, le serveur recompile et
remplace le code en une seconde environ, sans déconnecter personne. Si le code est cassé, l'ancienne
version continue de tourner.

| Dossier / fichier | Rôle |
|---|---|
| `modules/<id>/` | un module = une fonctionnalité = une conversation IA |
| `modules/<id>/.vibe/` | écrit par le serveur : `build.log` (erreurs de compilation), `errors.log`, `status.json` |
| `data/<id>/` | données persistantes des modules (ne pas toucher) |
| `examples/` | modules d'exemple (non chargés) : `hello`, `welcome`, `double-jump`, `pig-rain`, `magic-wand`, `boss-bar`, `scoreboard`, et contenu « comme un mod » : `laser-gun`, `robot-mob`, `custom-gui`, `hover-car`, `ai-npc` |
| `modules/<id>/assets/` | textures, modèles 3D, polices... du module (pack de ressources envoyé à chaud aux joueurs) |
| `CLAUDE.md` | instructions + référence de l'API lues par chaque session IA |
| `ASSETS.md` | guide des textures / modèles 3D / interfaces / mobs / véhicules pour les IA |
| `.claude/settings.json` | permissions : lecture du workspace, écriture dans `modules/`, `curl` vers l'API locale |

Prérequis : le serveur Paper avec le plugin VibeCraft tourne. Tableau de bord : http://127.0.0.1:8765/
(statut des modules, jobs IA, idées du public).

## Plusieurs chats Claude Code en parallèle (un module par chat)

1. Dans l'application Claude (onglet Code), ouvrir **ce dossier `workspace`** (pas la racine du dépôt,
   sinon `CLAUDE.md` et les permissions ne sont pas chargés). Désactiver l'isolation en worktree s'il y en a
   une : les fichiers doivent arriver dans ce dossier-ci, c'est lui que le serveur surveille.
2. Ouvrir une nouvelle session par fonctionnalité et commencer par l'id du module, par exemple :
   - chat 1 : « Module `pig-rain` : une commande /pigrain qui fait pleuvoir des cochons. »
   - chat 2 : « Module `lava-floor` : le sol devient de la lave quand on tape /lava. »
   - chat 3 : « Module `welcome` : un titre de bienvenue quand un joueur se connecte. »
3. Chaque IA écrit uniquement dans `modules/<son-id>/`, recharge via l'API, teste, puis répond en une ou
   deux phrases en français (« Tapez /pigrain 20 ! »). Pour une suite (« plus de cochons »), rester
   dans le même chat.

Équivalent en ligne de commande : un terminal par module, `cd workspace` puis `claude`.

## Depuis le jeu

Réservé aux ops (`vibecraft.admin`) :

- `/vibe ai <id> <demande>` : lance un job Claude Code en arrière-plan sur `modules/<id>/` (créé si besoin).
  La progression s'affiche dans le chat, le module se recharge tout seul.
  Exemple : `/vibe ai pig-rain fais pleuvoir des cochons quand on tape /pigrain`
- `/vibe jobs`, `/vibe stop <id>`, `/vibe reset <id>` (oublier la conversation de ce module)
- `/vibe list`, `/vibe info <id>`, `/vibe reload <id|all>`, `/vibe disable <id>`, `/vibe enable <id>`,
  `/vibe errors <id>`, `/vibe new <id> [description]`, `/vibe dashboard`

Pour le public (tout le monde) : `/idea <texte>` propose une idée ; un op l'accepte avec
`/vibe ideas` puis `/vibe accept <n> [id]` (ce qui lance le job IA).

## Essayer sans IA

Copier un exemple dans `modules/` (par exemple `examples/pig-rain` vers `modules/pig-rain`) : il est
chargé à chaud dans la seconde, puis `/pigrain 10` en jeu. Modifier `config.yml` d'un module (essayez
`double-jump`) le recharge aussi en direct.

## Contenu « comme un mod » (clients vanilla)

Les modules peuvent ajouter des **textures, objets en 3D, interfaces texturées, nouveaux mobs, véhicules**
sans aucun mod côté joueur : les fichiers de `modules/<id>/assets/` de tous les modules actifs sont fusionnés
en un pack de ressources, servi par le serveur (port `resource-pack.port`, 8766 par défaut) et envoyé à chaque
joueur à la connexion et ~1 s après chaque modification. Le client recharge ses ressources sur place (petit
écran de chargement), **sans déconnexion**. Le joueur doit accepter le pack (sinon il voit les objets vanilla).
Essayer : copier `examples/laser-gun` dans `modules/`, puis `/lasergun` ; `robot-mob` (`/robot`),
`custom-gui` (`/menu`), `hover-car` (`/hovercar`), `ai-npc` (`/merlin`, nécessite la clé OpenRouter).
État du pack et problèmes détectés : http://127.0.0.1:8765/api/pack

### Jouer en LAN (ou depuis une autre machine)

Le client télécharge le pack directement depuis le serveur : le port du pack (8766) doit être joignable par
les joueurs. Par défaut (`resource-pack.public-host: auto`), l'URL utilise l'adresse que le joueur a tapée
pour se connecter (`localhost`, l'IP LAN `192.168.x.y`, un nom de domaine...). Si un joueur voit « échec du
téléchargement » : autoriser Java dans le pare-feu Windows (ports 25565 et 8766, réseau privé) ou fixer
`resource-pack.public-host` à l'IP LAN du PC serveur dans `server/plugins/VibeCraft/config.yml`.
Derrière le proxy Velocity, rien à changer (le pack est servi directement par le serveur principal).

## Clé OpenRouter (PNJ IA et génération de textures)

OpenRouter sert à deux choses : les modules peuvent appeler un modèle de langage (PNJ qui parlent, quiz...)
via `dev.vibecraft.api.OpenRouter`, et les IA de code peuvent **générer des textures** avec un modèle d'image
(`POST /api/modules/<id>/textures`, la clé reste sur le serveur). Sans clé, tout le reste fonctionne (les IA
dessinent alors les textures en pixel art).

1. Créer une clé sur https://openrouter.ai/keys (prévoir quelques dollars de crédit).
2. L'écrire dans `server/plugins/VibeCraft/secrets.yml` (créé au premier démarrage, ignoré par git) :
   ```yaml
   openrouter: "sk-or-v1-..."
   ```
   Pris en compte **sans redémarrage**. Alternative : variable d'environnement `OPENROUTER_API_KEY` avant de
   lancer le serveur. Vérifier : http://127.0.0.1:8765/api/openrouter (`available: true` ; la clé n'est jamais affichée).
3. Réglages dans `config.yml`, section `openrouter:` : modèle de texte (`model`), modèle d'image (`image-model`),
   limite de requêtes par minute (protège le crédit pendant la démo).

La clé n'apparaît jamais dans les logs, le tableau de bord ni l'API (une ligne de log de module qui la
contiendrait est masquée par `***`). Les jobs IA (`/vibe ai`) la reçoivent dans la variable `OPENROUTER_API_KEY`.
Autres secrets pour les modules : ajouter `nom: "valeur"` dans `secrets.yml`, lus avec `Secrets.get("nom")`.

## Serveurs MCP pour les jobs IA (`/vibe ai`)

Les jobs IA lancés depuis le jeu ou le tableau de bord n'utilisent par défaut aucun serveur MCP (démarrage plus
rapide). Pour leur en donner (générateur d'images, recherche web...) :

1. Écrire un fichier de configuration MCP au format Claude Code, par exemple `server/plugins/VibeCraft/mcp.json` :
   ```json
   {
     "mcpServers": {
       "mon-serveur": { "command": "npx", "args": ["-y", "paquet-du-serveur-mcp"], "env": { "API_KEY": "..." } }
     }
   }
   ```
2. Dans `config.yml` : `ai.mcp-config: "mcp.json"` (chemin absolu, ou relatif au dossier du serveur ou à
   `plugins/VibeCraft/`), puis redémarrer le serveur. Les outils de chaque serveur listé sont autorisés
   automatiquement (`mcp__mon-serveur`). Le log de démarrage affiche `[AI] MCP servers for AI jobs: [...]`.
3. Variables d'environnement supplémentaires pour les jobs : `ai.env` (ex. `ai.env: { MA_VAR: "x" }`).

Pour les sessions Claude Code ouvertes à la main dans ce dossier, ajouter les serveurs MCP comme d'habitude
(`claude mcp add ...` ou un fichier `.mcp.json` dans `workspace/`).
