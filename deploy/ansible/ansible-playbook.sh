#!/bin/sh
# ansible-playbook from a throwaway container, for a machine without Ansible -
# Windows through WSL, or a Linux whose Python cannot make a venv. It needs
# Docker and nothing else, and takes ansible-playbook's own arguments:
#
#   ./ansible-playbook.sh orkbox.yml -K --ask-vault-pass
#
# This directory and the jar are copied into the container rather than used
# where they are mounted: Ansible ignores an ansible.cfg in a world-writable
# directory, which is what every folder under /mnt/c is. ~/.ssh is copied for
# the same reason - ssh refuses a key whose permissions it cannot check.
set -e

usage() {
  cat <<'EOF'
Usage: ./ansible-playbook.sh PLAYBOOK [ansible-playbook options]

Runs ansible-playbook in a throwaway python:3.12-slim container, so the machine
it is started on needs Docker and nothing else - not Ansible, not Python. On
Windows, start it from WSL. Every argument goes to ansible-playbook as it is.

  ./ansible-playbook.sh orkbox.yml --ask-vault-pass        install or upgrade
  ./ansible-playbook.sh orkbox.yml -K --ask-vault-pass     ... when sudo asks for a password
  ./ansible-playbook.sh orkbox.yml --check                 see what would change
  ./ansible-playbook.sh orkbox-remove.yml -K               take orkbox off again

What the container is given, read-only:
  this directory          the playbooks, inventory.ini, group_vars/, host_vars/
  ~/.ssh                  keys, config and known_hosts, to reach the hosts
  ../../orkbox-server/target/orkbox.jar
                          the jar ./mvnw package builds, when it is there

Ansible is installed afresh on every run, which takes about a minute.
See README.md for the inventory, the variables and the token.
EOF
}

case "$1" in
  ''|-h|--help) usage; exit 0 ;;
esac

here=$(cd "$(dirname "$0")" && pwd)
jar="$here/../../orkbox-server/target/orkbox.jar"
[ -f "$jar" ] || { echo "No $jar: run ./mvnw package in the repository first, or set orkbox_jar_url." >&2; }

# A terminal when there is one, for -K and --ask-vault-pass to prompt on.
tty=; [ -t 0 ] && tty=-t
exec docker run --rm -i $tty \
  -v "$here":/src:ro \
  $( [ -f "$jar" ] && printf -- '-v %s:/jar:ro' "$jar" ) \
  -v "$HOME/.ssh":/ssh:ro \
  python:3.12-slim sh -c '
    set -e
    echo "Installing Ansible in the container..."
    apt-get update -qq >/dev/null 2>&1
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openssh-client >/dev/null 2>&1
    pip install -q --root-user-action=ignore --disable-pip-version-check "ansible-core>=2.15,<2.25"
    mkdir -p /repo/deploy /repo/orkbox-server/target
    cp -r /src /repo/deploy/ansible && chmod -R go-w /repo/deploy/ansible
    [ -f /jar ] && cp /jar /repo/orkbox-server/target/orkbox.jar
    cp -r /ssh /root/.ssh && chmod 700 /root/.ssh && chmod 600 /root/.ssh/* 2>/dev/null || true
    cd /repo/deploy/ansible
    exec ansible-playbook "$@"' sh "$@"
