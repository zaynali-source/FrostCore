#!/system/bin/sh
BASE=/data/adb/frostcore
PKGS=$BASE/packages
WHITE=$BASE/whitelist
OWNED=$BASE/owned.cgroups
LOG=$BASE/daemon.log
mkdir -p "$BASE"
touch "$PKGS" "$WHITE" "$OWNED"
log(){ echo "$(date '+%F %T') $*" >> "$LOG"; }
is_white(){ grep -qxF "$1" "$WHITE" 2>/dev/null; }
screen_off(){
  dumpsys power 2>/dev/null | grep -qE 'mWakefulness=Asleep|Display Power: state=OFF|mScreenOn=false'
}
get_pids(){
  # Include the package main process and Android secondary processes (package:remote).
  ps -A -o PID,NAME 2>/dev/null | awk -v p="$1" '$2==p || index($2,p ":")==1 {print $1}'
}
get_cgroup(){
  awk -F: '$1=="0" {print $3}' "/proc/$1/cgroup" 2>/dev/null
}
safe_path(){
  p="$1"
  case "$p" in /apps/uid_[0-9]*/pid_[0-9]*) ;; *) return 1;; esac
  case "$p" in *..*) return 1;; esac
  [ -f "/sys/fs/cgroup$p/cgroup.freeze" ] && [ -f "/sys/fs/cgroup$p/cgroup.events" ]
}
freeze_pkg(){
  pkg="$1"
  is_white "$pkg" && return
  pids=$(get_pids "$pkg")
  [ -n "$pids" ] || return
  for pid in $pids; do
    [ -r "/proc/$pid/cgroup" ] || continue
    cg=$(get_cgroup "$pid")
    safe_path "$cg" || continue
    dir="/sys/fs/cgroup$cg"
    # Confirm the PID still belongs to this cgroup and cgroup is populated.
    grep -qx "$pid" "$dir/cgroup.procs" 2>/dev/null || continue
    events=$(cat "$dir/cgroup.events" 2>/dev/null)
    echo "$events" | grep -q 'populated 1' || continue
    echo "$events" | grep -q 'frozen 1' && continue
    # Re-check identity immediately before writing to reduce PID-reuse risk.
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
    if [ -f "$dir/cgroup.freeze" ]; then
      echo 0 > "$dir/cgroup.freeze" 2>/dev/null
      log "THAW $pkg $cg"
    fi
  done < "$OWNED"
  mv "$OWNED.tmp" "$OWNED"
}
thaw_pkg(){
  target="$1"
  [ -f "$OWNED" ] || return
  : > "$OWNED.tmp"
  while IFS='|' read -r pkg cg; do
    [ -n "$cg" ] || continue
    case "$cg" in /apps/uid_[0-9]*/pid_[0-9]*) ;; *) continue;; esac
    if [ "$pkg" = "$target" ]; then
      dir="/sys/fs/cgroup$cg"
      [ -f "$dir/cgroup.freeze" ] && echo 0 > "$dir/cgroup.freeze" 2>/dev/null
      log "THAW $pkg $cg"
    else
      echo "$pkg|$cg" >> "$OWNED.tmp"
    fi
  done < "$OWNED"
  mv "$OWNED.tmp" "$OWNED"
}
thaw_all(){
  thaw_owned
  return
}
status(){
  echo "mode=$(cat "$BASE/mode" 2>/dev/null)"
  echo "configured_packages=$(wc -l < "$PKGS")"
  echo "owned_cgroups=$(wc -l < "$OWNED")"
  echo "cgroup_v2=$(test -f /sys/fs/cgroup/cgroup.controllers && echo yes || echo no)"
  echo "log=$LOG"
}
daemon(){
  log "daemon started"
  while true; do
    mode=$(cat "$BASE/mode" 2>/dev/null)
    case "$mode" in
      manual)
        # Manual mode is controlled through explicit freeze/thaw commands; no background action.
        ;;
      auto)
        if screen_off; then
          while IFS= read -r pkg; do
            [ -n "$pkg" ] && freeze_pkg "$pkg"
          done < "$PKGS"
        else
          thaw_owned
        fi
        ;;
      *) log "unknown mode=$mode; fail-safe thaw"; thaw_owned ;;
    esac
    sleep 3
  done
}
case "$1" in
  daemon) daemon ;;
  status) status ;;
  thaw-all) thaw_all ;;
  freeze)
    pkg="$2"; case "$pkg" in *[!A-Za-z0-9._]*|'') echo "invalid package"; exit 2;; esac
    freeze_pkg "$pkg" ;;
  thaw) thaw_owned ;;
  thaw-pkg)
    pkg="$2"; case "$pkg" in *[!A-Za-z0-9._]*|'') echo "invalid package"; exit 2;; esac
    thaw_pkg "$pkg" ;;
  reload) echo "reloaded; mode=$(cat "$BASE/mode" 2>/dev/null)" ;;
  *) echo "Usage: $0 {daemon|status|freeze PACKAGE|thaw|thaw-all|reload}" ;;
esac
