#!/bin/bash
# --------------------------------------------
# Hafen launcher script for Linux/macOS
# Equivalent to the Windows .bat version
# JVM flags single source: Play.bat carries the Windows static fallback and its -Xmx
# is the number this script mirrors. Both floor at 8192m as of 2026-09-06 - before
# that date this script floored at 4096m and silently gave Linux and macOS half the
# heap the same change had already given Windows.
#   floor 8192m always; the 6144m (>=16G) and 8192m (>=24G) headroom tiers are
#   clamped to that floor, so they only bite again if the floor is lowered.
#   Headroom = TotalRAM - Count*HEAP - 4G OS reserve.
#   Count via NOV_CLIENT_COUNT env (default 1).
# Detection: /proc/meminfo MemTotal, fallback to getconf _PHYS_PAGES.
# --------------------------------------------

# Optional: print where Java is being run from
echo "Launching Hafen..."
echo "Using Java from: $(which java)"
echo

# Heap auto-scaling
get_total_mb() {
  if [ -r /proc/meminfo ]; then
    mem_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null)
    if [ -n "$mem_kb" ] && [ "$mem_kb" -gt 0 ] 2>/dev/null; then
      echo $((mem_kb / 1024))
      return
    fi
  fi
  if command -v getconf >/dev/null 2>&1; then
    pages=$(getconf _PHYS_PAGES 2>/dev/null)
    page_size=$(getconf PAGE_SIZE 2>/dev/null)
    if [ -n "$pages" ] && [ -n "$page_size" ] && [ "$pages" -gt 0 ] 2>/dev/null; then
      echo $((pages * page_size / 1024 / 1024))
      return
    fi
  fi
  echo 0
}

OS_RESERVE=4096
FLOOR=8192
MID=6144
HIGH=8192
COUNT=${NOV_CLIENT_COUNT:-1}
# sanitize COUNT to integer >=1
if ! echo "$COUNT" | grep -Eq '^[0-9]+$' || [ "$COUNT" -lt 1 ] 2>/dev/null; then COUNT=1; fi
TOTAL_MB=$(get_total_mb)
HEAP=$FLOOR
if [ "$TOTAL_MB" -gt 0 ] 2>/dev/null; then
  has_headroom() {
    local cand=$1
    local need=$((cand * COUNT + OS_RESERVE))
    [ "$TOTAL_MB" -ge "$need" ]
  }
  HAS_MID=false; has_headroom $MID && HAS_MID=true
  HAS_HIGH=false; has_headroom $HIGH && HAS_HIGH=true
  # Strict thresholds + headroom (avoid 8G on 16G boxes even if headroom passes)
  if [ "$TOTAL_MB" -ge 24576 ] && $HAS_HIGH; then
    HEAP=$HIGH
  elif [ "$TOTAL_MB" -ge 16384 ] && $HAS_MID; then
    HEAP=$MID
  fi
  # The ladder is only ever allowed to scale UP. With FLOOR at 8192 a 16-24G box
  # matches MID and would otherwise come back with 6144 - less heap than the 8G box
  # that never entered the ladder at all.
  if [ "$HEAP" -lt "$FLOOR" ] 2>/dev/null; then HEAP=$FLOOR; fi
fi

# ZGC is the default (opt out: NOV_ZGC=0 / NO_ZGC=1 / G1=1 -> G1); footprint 3632M vs 1515M on G1.
# -XX:+IgnoreUnrecognizedVMOptions stays first so JDK 24+ tolerates +ZGenerational.
ZGC_OPTS=()
if [ "${NOV_ZGC:-1}" != "0" ] && [ "${NO_ZGC:-0}" != "1" ] && [ "${G1:-0}" != "1" ]; then
  ZGC_OPTS=(-XX:+UseZGC -XX:+ZGenerational)
fi

# Run the Java application (keep -Xms1024m floor; guard order preserved if present)
java \
  -XX:+IgnoreUnrecognizedVMOptions \
  "${ZGC_OPTS[@]}" \
  -XX:+UseCompactObjectHeaders \
  -Dsun.java2d.uiScale.enabled=false \
  -Dsun.java2d.win.uiScaleX=1.0 \
  -Dsun.java2d.win.uiScaleY=1.0 \
  -Xss8m \
  -Xms1024m \
  -Xmx${HEAP}m \
  --add-exports java.base/java.lang=ALL-UNNAMED \
  --add-exports java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
  -DrunningThroughSteam=false \
  -jar hafen.jar "$@"
