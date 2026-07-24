# Steam Workshop upload

This directory holds the Novocaine-specific Steam Workshop metadata, kept **separate from
the repo-root `workshop-client.properties`** (which still carries Hurricane's own item ID
and must never be uploaded against — doing so would try to overwrite Nightdawg's public
Workshop item).

`tools/make-steam-item.ps1` assembles a Workshop item by copying the built `bin\` client
into `dist\steam-item\` and overlaying this `workshop-client.properties` on top, then
(optionally) runs Loftar's upload tool.

## One-time prerequisites (you, not the build)

1. **Steam running and logged in** on this machine.
2. **Beta access to the game/workshop** — until Haven's Steam launch, request a beta key
   from Loftar (see his Workshop post). After launch this is open to anyone.
3. **Agree to the Steam Workshop Legal Agreement** — Steam client → the game → Workshop
   tab. The upload tool refuses to publish until you have.

## Publishing

```powershell
# Stage only (no upload) — inspect dist\steam-item first:
.\tools\make-steam-item.ps1

# Stage and upload (first run creates the item and PRINTS a new workshop-id):
.\tools\make-steam-item.ps1 -Upload
```

On the **first** successful upload, the tool prints a line like `workshop-id=1234567890`.
Paste that number into the `workshop-id=` line of this file (add the line if it's absent),
commit it, and every future `-Upload` **updates the same item** instead of creating a new
one.

## Visibility

`visibility=friends` — only you and your Steam friends can see the item. Change to
`private` (only you) or `public` (anyone) here if you ever want to.

## Preview image

`preview-image=steamicon.gif` reuses the icon the build already stages into `bin\`. Drop a
custom `Novocaine.png` (or similar) into this folder and point `preview-image` at it for a
branded icon; the staging script copies anything here into the item root.
