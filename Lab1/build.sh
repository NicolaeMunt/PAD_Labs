#!/usr/bin/env sh
# Builds all three modules natively (no Docker):  sh Lab1/build.sh
# A module whose toolchain is missing is skipped with a message; the others still build.

root=$(cd "$(dirname "$0")" && pwd)
failed=0

has() { command -v "$1" >/dev/null 2>&1; }
step() { printf '\n=== %s\n' "$1"; }
result() { if [ "$1" -eq 0 ]; then echo OK; else echo FAILED; failed=1; fi; }

if has java && has mvn; then
  step "Broker (Java / Maven)"
  (cd "$root/Broker" && mvn -q -B package -DskipTests); result $?
else
  step "Broker skipped: install JDK 17+ and Maven"
fi

if has npm; then
  step "Publisher (Node.js / TypeScript)"
  (cd "$root/Publisher" && npm ci --no-audit --no-fund && npm run build); result $?
else
  step "Publisher skipped: install Node.js 18+"
fi

if has dotnet; then
  step "Receiver (.NET 8)"
  (cd "$root/Receiver/src" && dotnet publish Receiver.csproj -c Release -o "$root/Receiver/out" --nologo); result $?
else
  step "Receiver skipped: install the .NET 8 SDK"
fi

cat <<EOF

Artifacts:
  Broker    -> Broker/target/Broker-1.0-SNAPSHOT.jar
  Publisher -> Publisher/dist/index.js
  Receiver  -> Receiver/out/Receiver.dll
EOF
exit $failed
