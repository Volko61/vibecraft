# fixer-alias-conflict (regression for ai-coder-command-collision)

With module `shop` (../ai-coder-6-shop/shop: /shop + alias /boutique) loaded:

* `../ai-coder-command-collision/shop-bis` (registers /shop) -> reload `ok:false`, error
  `command /shop is already used by module 'shop': pick another command name`, nothing of shop-bis runs,
  `/shop`, `/boutique` and `/vibe:shop` still answer with module shop.
* `shop-alias/` (this dir: /shopalias + alias /boutique) -> reload `ok:true` with a WARNING diagnostic
  `alias /boutique of /shopalias ignored: already used by module 'shop'`; `/boutique` still goes to shop.

Observed on the fixer sandbox (2026-10-13): exactly that.
