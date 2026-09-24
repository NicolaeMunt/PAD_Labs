#!/usr/bin/env sh
# Starts the whole system in Docker for a demo (macOS / Linux / Git Bash):
#   - broker + publisher web UI (http://localhost:3000)
#   - the receivers listed in RECEIVERS below: alice, bob, carol and dave
# and follows all their logs in this terminal. Ctrl+C stops everything.
#
# Usage:  sh Lab1/start.sh               build, then start
#         sh Lab1/start.sh --skip-build  start without rebuilding

cd "$(dirname "$0")" || exit 1
UI_URL=http://localhost:3000

# Receivers to start, as <id>:<comma-separated topics>. Add, remove or edit entries to change the demo.
RECEIVERS="alice:news,sports bob:news carol:weather dave:news,sports,weather"

receiver_names() { for entry in $RECEIVERS; do echo "${entry%%:*}"; done; }
remove_receivers() { for name in $(receiver_names); do docker rm -f "pad-receiver-$name" >/dev/null 2>&1; done; }

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop (or the Docker daemon) and try again."
  exit 1
fi

if [ "$1" != "--skip-build" ]; then
  echo "Building images (the first build takes a few minutes)..."
  docker compose --profile clients build || exit 1
fi

cleanup() {
  echo
  echo "Stopping receivers, broker and publisher UI..."
  remove_receivers
  docker compose --profile clients down --remove-orphans
  exit 0
}
trap cleanup INT TERM

remove_receivers
docker compose up -d broker publisher-ui || exit 1
for entry in $RECEIVERS; do
  name=${entry%%:*}
  topics=${entry#*:}
  docker compose run -d --name "pad-receiver-$name" receiver -t "$topics" -c "$name" -w 1 >/dev/null || exit 1
done

i=0
until curl -fs "$UI_URL" >/dev/null 2>&1 || [ $i -ge 30 ]; do i=$((i + 1)); sleep 1; done
if command -v open >/dev/null 2>&1; then open "$UI_URL"
elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$UI_URL" >/dev/null 2>&1
fi

echo
echo "Everything is running:"
echo "  Publisher UI   $UI_URL"
echo "  Broker         localhost:5000"
echo "  Receivers"
for entry in $RECEIVERS; do printf '                 %-6s %s\n' "${entry%%:*}" "$(echo "${entry#*:}" | sed 's/,/, /g')"; done
cat <<EOF

In the UI, add publisher-news / news, publisher-sports / sports and publisher-weather / weather.

Offline replay demo (in another terminal):
  docker stop pad-receiver-bob     # send a few news messages in the UI meanwhile
  docker start pad-receiver-bob && docker logs -f pad-receiver-bob

Following logs below. Press Ctrl+C to stop everything.

EOF

# A read loop instead of sed, so lines appear immediately (sed buffers when not writing to a terminal).
prefix() { while IFS= read -r line; do printf '%s | %s\n' "$1" "$line"; done; }
docker compose logs -f --no-log-prefix broker 2>&1 | prefix "broker" &
for name in $(receiver_names); do
  docker logs -f "pad-receiver-$name" 2>&1 | prefix "$(printf '%-6s' "$name")" &
done
wait
