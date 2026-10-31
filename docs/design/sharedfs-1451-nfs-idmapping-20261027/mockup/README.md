# #1451 design-only Vue3/AntD mockup

Uses actual Mold ResourceLayout, Breadcrumb, MoldDialog and MoldDialogActions from the canonical WSL source. No Cloud API connection.

Build: NODE_OPTIONS=--openssl-legacy-provider node build.cjs

Serve public/ on localhost. Parameters: view=list/details/nfs, theme=dark/light, dialog=settings/client, mode=NAME_DOMAIN/NUMERIC, scenario=preview, status=legacy.

Generated public/ is excluded from Git; images are captured separately.
