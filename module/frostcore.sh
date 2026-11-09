#!/system/bin/sh
BASE=/data/adb/frostcore
PKGS=$BASE/packages
WHITE=$BASE/whitelist
OWNED=$BASE/owned.cgroups
DEEP_STATE=$BASE/deep-state
LOG=$BASE/daemon.log
DEEP_DELAY_FILE=$BASE/deep_delay
mkdir -p "$BASE" "$DEEP_STATE"
touch "$PKGS" "$WHITE" "$OWNED"
[ -f "$DEEP_DELAY_FILE" ] || echo 8 > "$DEEP_DELAY_FILE"
log(){ echo "$(date '+%F %T') $*" >> "$LOG"; }
valid_pkg(){ case "$1" in *[!A-Za-z0-9._]*|'') return 1;; esac; }
is_white(){ grep -qxF "$1" "$WHITE" 2>/dev/null; }
screen_off(){ dumpsys power 2>/dev/null | grep -qE 'mWakefulness=Asleep|Display Power: state=OFF|mScreenOn=false'; }
get_pids(){ ps -A -o PID,NAME 2>/dev/null | awk -v p="$1" '$2==p || index($2,p ":")==1 {print $1}'; }
get_cgroup(){ awk -F: '$1=="0" {print $3}' "/proc/$1/cgroup" 2>/dev/null; }
safe_path(){
  p="$1"
  case "$p" in /apps/uid_[0-9]*/pid_[0-9]*) ;; *) return 1;; esac
  case "$p" in *..*) return 1;; esac
  [ -f "/sys/fs/cgroup$p/cgroup.freeze" ] && [ -f "/sys/fs/cgroup$p/cgroup.events" ]
}
freeze_pkg(){
  pkg="$1"; is_white "$pkg" && return
  pids=$(get_pids "$pkg"); [ -n "$pids" ] || return
  for pid in $pids; do
    [ -r "/proc/$pid/cgroup" ] || continue
    cg=$(get_cgroup "$pid"); safe_path "$cg" || continue
    dir="/sys/fs/cgroup$cg"
    grep -qx "$pid" "$dir/cgroup.procs" 2>/dev/null || continue
    events=$(cat "$dir/cgroup.events" 2>/dev/null)
    echo "$events" | grep -q 'populated 1' || continue
    echo "$events" | grep -q 'frozen 1' && continue
    [ -d "/proc/$pid" ] || continue
    [ "$(get_cgroup "$pid")" = "$cg" ] || continue
    if echo 1 > "$dir/cgroup.freeze" 2>/dev/null; then
      sleep 0.15
      if grep -q 'frozen 1' "$dir/cgroup.events" 2>/dev/null; then
        grep -qxF "$pkg|$cg" "$OWNED" 2>/dev/null || echo "$pkg|$cg" >> "$OWNED"
        log "FROZEN $pkg pid=$pid cg=$cg"
      fi
    fi
  done
}
thaw_owned(){
  [ -f "$OWNED" ] || return
  : > "$OWNED.tmp"
  while IFS='|' read -r pkg cg; do
    [ -n "$cg" ] || continue
    case "$cg" in /apps/uid_[0-9]*/pid_[0-9]*) ;; *) continue;; esac
    dir="/sys/fs/cgroup$cg"
    if [ -f "$dir/cgroup.freeze" ]; then echo 0 > "$dir/cgroup.freeze" 2>/dev/null; log "THAW $pkg $cg"; fi
  done < "$OWNED"
  mv "$OWNED.tmp" "$OWNED"
}
thaw_pkg(){
  target="$1"; [ -f "$OWNED" ] || return
  : > "$OWNED.tmp"
  while IFS='|' read -r pkg cg; do
    [ -n "$cg" ] || continue
    case "$cg" in /apps/uid_[0-9]*/pid_[0-9]*) ;; *) continue;; esac
    if [ "$pkg" = "$target" ]; then
      dir="/sys/fs/cgroup$cg"
      [ -f "$dir/cgroup.freeze" ] && echo 0 > "$dir/cgroup.freeze" 2>/dev/null
      log "THAW $pkg $cg"
    else echo "$pkg|$cg" >> "$OWNED.tmp"; fi
  done < "$OWNED"
  mv "$OWNED.tmp" "$OWNED"
}
foreground_pkg(){
  line=$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity')
  pkg=$(echo "$line" | sed -n 's/.* u[0-9][0-9]* \([^/ ]*\)\/.*/\1/p')
  if [ -z "$pkg" ]; then
    line=$(dumpsys window windows 2>/dev/null | grep -m1 'mCurrentFocus')
    pkg=$(echo "$line" | sed -n 's/.* u[0-9][0-9]* \([^/ ]*\)\/.*/\1/p')
  fi
  echo "$pkg"
}
deep_scan(){
  fg=$(foreground_pkg)
  # Fail closed: never force-stop anything if foreground detection is unavailable.
  [ -n "$fg" ] || { log "DEEP_SKIP foreground_unknown"; return; }
  now=$(date +%s)
  delay=$(cat "$DEEP_DELAY_FILE" 2>/dev/null); case "$delay" in ''|*[!0-9]*) delay=8;; esac
  [ "$delay" -lt 3 ] && delay=3
  while IFS= read -r pkg; do
    valid_pkg "$pkg" || continue
    is_white "$pkg" && continue
    stamp="$DEEP_STATE/$pkg.since"
    stopped="$DEEP_STATE/$pkg.stopped"
    if [ "$pkg" = "$fg" ]; then
      rm -f "$stamp" "$stopped"
      continue
    fi
    [ -f "$stopped" ] && continue
    if [ ! -f "$stamp" ]; then
      echo "$now" > "$stamp"
      continue
    fi
    started=$(cat "$stamp" 2>/dev/null); case "$started" in ''|*[!0-9]*) started=$now;; esac
    if [ $((now-started)) -ge "$delay" ]; then
      if am force-stop --user current "$pkg" >/dev/null 2>&1; then
        : > "$stopped"
        log "DEEP_STOP $pkg foreground=$fg delay=$delay"
      else
        log "DEEP_STOP_FAILED $pkg"
      fi
    fi
  done < "$PKGS"
}
status(){
  echo "version=2.1"
  echo "mode=$(cat "$BASE/mode" 2>/dev/null)"
  echo "configured_packages=$(wc -l < "$PKGS")"
  echo "owned_cgroups=$(wc -l < "$OWNED")"
  echo "deep_stopped=$(find "$DEEP_STATE" -type f -name '*.stopped' 2>/dev/null | wc -l)"
  echo "deep_delay=$(cat "$DEEP_DELAY_FILE" 2>/dev/null)s"
  echo "foreground=$(foreground_pkg)"
  echo "cgroup_v2=$(test -f /sys/fs/cgroup/cgroup.controllers && echo yes || echo no)"
  echo "log=$LOG"
}
daemon(){
  log "v2.1 daemon started"
  while true; do
    mode=$(cat "$BASE/mode" 2>/dev/null)
    case "$mode" in
      manual) ;;
      auto)
        if screen_off; then
          while IFS= read -r pkg; do [ -n "$pkg" ] && freeze_pkg "$pkg"; done < "$PKGS"
        else thaw_owned; fi
        ;;
      deep) deep_scan ;;
      *) log "unknown mode=$mode; fail-safe thaw"; thaw_owned ;;
    esac
    sleep 2
  done
}
case "$1" in
  daemon) daemon ;;
  status) status ;;
  thaw-all)
    thaw_owned
    rm -f "$DEEP_STATE"/*.since "$DEEP_STATE"/*.stopped
    echo "All FrostCore-owned cgroups thawed; Deep Freeze timers cleared." ;;
  freeze) pkg="$2"; valid_pkg "$pkg" || { echo "invalid package"; exit 2; }; freeze_pkg "$pkg" ;;
  thaw) thaw_owned ;;
  thaw-pkg) pkg="$2"; valid_pkg "$pkg" || { echo "invalid package"; exit 2; }; thaw_pkg "$pkg" ;;
  add) pkg="$2"; valid_pkg "$pkg" || { echo "invalid package"; exit 2; }; grep -qxF "$pkg" "$PKGS" 2>/dev/null || echo "$pkg" >> "$PKGS"; echo "Added $pkg" ;;
  remove) pkg="$2"; valid_pkg "$pkg" || { echo "invalid package"; exit 2; }; grep -vxF "$pkg" "$PKGS" > "$PKGS.tmp" 2>/dev/null; mv "$PKGS.tmp" "$PKGS"; rm -f "$DEEP_STATE/$pkg".*; echo "Removed $pkg" ;;
  reload) echo "reloaded; mode=$(cat "$BASE/mode" 2>/dev/null)" ;;
  *) echo "Usage: $0 {daemon|status|freeze PACKAGE|thaw|thaw-pkg PACKAGE|thaw-all|add PACKAGE|remove PACKAGE|reload}" ;;
esac
