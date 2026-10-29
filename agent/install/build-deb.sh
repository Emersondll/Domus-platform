#!/usr/bin/env bash
# Gera o pacote .deb do agente local a partir do jar já construído (mvn -f agent/pom.xml package).
# Uso: agent/install/build-deb.sh [versão]   (padrão: a versão do pom, sem -SNAPSHOT -> ~snapshot)
# O pacote NÃO liga nem inicia o serviço: o agente precisa de /etc/portaria-agent/agent.env (segredos) antes.
# Requer: dpkg-deb. Java 26 precisa estar instalado na máquina (não existe como pacote da distribuição em todas as versões).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
POM_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/agent/pom.xml" | sed -n 1p)"
VERSION="${1:-${POM_VERSION/-SNAPSHOT/~snapshot}}"
JAR="$(ls "$ROOT"/agent/target/agent-*.jar | grep -v '/original-' | sed -n 1p)"
[ -f "$JAR" ] || { echo "jar não encontrado: mvn -f agent/pom.xml package" >&2; exit 1; }
OUT="${DEB_OUT_DIR:-$ROOT/agent/target}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
chmod 755 "$STAGE"

install -d -m 755 "$STAGE/DEBIAN" "$STAGE/opt/portaria-agent" "$STAGE/usr/share/portaria-agent" \
  "$STAGE/lib/systemd/system" "$STAGE/usr/share/doc/portaria-agent"
install -m 755 "$ROOT/agent/install/portaria-agent-run.sh" "$STAGE/opt/portaria-agent/portaria-agent-run.sh"
install -m 644 "$ROOT/agent/install/portaria-agent.service" "$STAGE/lib/systemd/system/portaria-agent.service"
install -m 644 "$ROOT/agent/install/agent.env.example" "$STAGE/usr/share/portaria-agent/agent.env.example"
install -m 644 "$JAR" "$STAGE/usr/share/portaria-agent/agent.jar"
install -m 644 "$ROOT/LICENSE" "$STAGE/usr/share/doc/portaria-agent/copyright"

cat > "$STAGE/DEBIAN/control" <<EOF
Package: portaria-agent
Version: $VERSION
Section: admin
Priority: optional
Architecture: all
Depends: systemd
Recommends: default-jre-headless
Maintainer: Domus <emerson.elima@gmail.com>
Description: Agente local de contingência da portaria virtual Domus
 Mantém a portaria funcionando de forma degradada e controlada quando a internet
 ou a central caem. Exige Java 26 e o arquivo /etc/portaria-agent/agent.env
 (modelo em /usr/share/portaria-agent/agent.env.example). Não inicia sozinho.
EOF

cat > "$STAGE/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if [ "$1" = "configure" ]; then
  getent group portaria-agent >/dev/null || addgroup --system portaria-agent
  getent passwd portaria-agent >/dev/null || adduser --system --ingroup portaria-agent --home /var/lib/portaria-agent \
    --no-create-home --shell /usr/sbin/nologin portaria-agent
  install -d -o portaria-agent -g portaria-agent -m 750 /var/lib/portaria-agent /var/lib/portaria-agent/bin
  install -d -o root -g portaria-agent -m 750 /etc/portaria-agent
  # o jar executado fica em /var/lib (é o que a atualização segura substitui); só instala o inicial se ainda não houver um
  if [ ! -f /var/lib/portaria-agent/bin/agent.jar ]; then
    install -o portaria-agent -g portaria-agent -m 640 /usr/share/portaria-agent/agent.jar /var/lib/portaria-agent/bin/agent.jar
  fi
  if [ -d /run/systemd/system ]; then systemctl daemon-reload || true; fi
  echo "portaria-agent instalado. Crie /etc/portaria-agent/agent.env (chmod 600) a partir de /usr/share/portaria-agent/agent.env.example"
  echo "e depois: systemctl enable --now portaria-agent"
fi
EOF
cat > "$STAGE/DEBIAN/prerm" <<'EOF'
#!/bin/sh
set -e
if [ "$1" = "remove" ] || [ "$1" = "upgrade" ]; then
  if [ -d /run/systemd/system ]; then systemctl stop portaria-agent || true; fi
fi
EOF
cat > "$STAGE/DEBIAN/postrm" <<'EOF'
#!/bin/sh
set -e
if [ -d /run/systemd/system ]; then systemctl daemon-reload || true; fi
# /var/lib/portaria-agent (fila local cifrada) e /etc/portaria-agent (segredos) NÃO são apagados nem no purge: são dados do condomínio.
EOF
chmod 755 "$STAGE/DEBIAN/postinst" "$STAGE/DEBIAN/prerm" "$STAGE/DEBIAN/postrm"

mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$STAGE" "$OUT/portaria-agent_${VERSION}_all.deb" >/dev/null
echo "$OUT/portaria-agent_${VERSION}_all.deb"
