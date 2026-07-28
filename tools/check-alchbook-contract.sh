#!/usr/bin/env bash
#
# Verify the reflective contract AlchemyBook.java depends on.
#
# The Alchemy Book is server-shipped bytecode (resource ui/alchbook), so javac
# cannot check any of the field and method names AlchemyBook reflects on. When
# the server changes the book, the hook does not fail loudly -- it just stops
# finding anything. This asserts every reflected member still exists.
#
# Run it after a client or game update, and whenever the book reports empty.
#
#   tools/extract-alchbook.py            # refresh from the resource cache
#   tools/check-alchbook-contract.sh     # assert the contract
#
# Exit 0 = contract intact. Exit 1 = AlchemyBook needs updating.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSDIR="${1:-$ROOT/build/alchbook}"
CLIENT_JAR="$ROOT/build/hafen.jar"
JAVAP="${JAVAP:-/mnt/c/Program Files/Java/jdk-21/bin/javap.exe}"

if [[ ! -d "$CLASSDIR/haven/res/ui/alchbook" ]]; then
  echo "no extracted classes at $CLASSDIR -- run tools/extract-alchbook.py first" >&2
  exit 1
fi

# javap here is the Windows JDK binary and cannot resolve /mnt/c/... paths, so
# absolute paths must be converted. Harmless no-op outside WSL.
if command -v wslpath >/dev/null 2>&1; then
  CLASSDIR=$(wslpath -w "$CLASSDIR")
  CLIENT_JAR=$(wslpath -w "$CLIENT_JAR")
fi

fail=0 pass=0

want() {
  local cp="$1" cls="$2" regex="$3" desc="$4" out
  out=$("$JAVAP" -p -classpath "$cp" "$cls" 2>&1)
  if grep -qE "$regex" <<<"$out"; then
    pass=$((pass + 1)); printf '  ok   %s\n' "$desc"
  else
    fail=$((fail + 1)); printf '  FAIL %s\n         want /%s/ in %s\n' "$desc" "$regex" "$cls"
  fi
}

book() { want "$CLASSDIR" "haven.res.ui.alchbook.$1" "$2" "$3"; }

echo "== alchemy book reflective contract =="

book Book         'public final haven\.res\.ui\.alchbook\.RecipeList rl;'  'Book.rl -> RecipeList'
book Book         'public final haven\.res\.ui\.alchbook\.EffectList el;'  'Book.el -> EffectList'
book RecipeList   'public final java\.util\.Map<.*> recipes;'              'RecipeList.recipes -> Map'
book EffectList   'public final java\.util\.Map<.*> knowledge;'            'EffectList.knowledge -> Map'
book KnownEffects 'public final haven\.res\.ui\.alchbook\.Input input;'    'KnownEffects.input -> Input'
book KnownEffects 'public final java\.util\.List<.*> effs;'                'KnownEffects.effs -> List<EffectInfo>'
book Input        'public final haven\.ItemSpec type;'                     'Input.type -> ItemSpec'
book Input        'public final java\.util\.List<.*> sub;'                 'Input.sub -> List<Input> (contributors)'
book Recipe       'public final haven\.ItemSpec rcp;'                      'Recipe.rcp -> ItemSpec'
book Recipe       'public final java\.util\.List<.*> inputs;'              'Recipe.inputs -> List<Input>'
book Recipe       'public final java\.util\.List<.*> effects;'             'Recipe.effects -> List<EffectInfo>'
book Recipe       'public final java\.util\.List<.*> mmeffects;'           'Recipe.mmeffects -> List (Negatives column)'
book EffectInfo   'java\.lang\.String desc\(\);'                           'EffectInfo.desc() -> String'

# client-side, compiled from this tree
want "$CLIENT_JAR" haven.ItemSpec 'public final haven\.ResData res;'    'ItemSpec.res -> ResData'
want "$CLIENT_JAR" haven.ItemSpec 'public java\.lang\.String name\(\);' 'ItemSpec.name() -> String'

echo
if (( fail )); then
  echo "RED: $fail contract violation(s), $pass ok -- AlchemyBook.java needs updating"
  exit 1
fi
echo "GREEN: all $pass members present"
