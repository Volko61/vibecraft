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
| `examples/` | modules d'exemple (non chargés) : `hello`, `welcome`, `double-jump`, `pig-rain`, `magic-wand`, `boss-bar`, `scoreboard` |
| `CLAUDE.md` | instructions + référence de l'API lues par chaque session IA |
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
