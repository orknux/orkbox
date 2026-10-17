# Installing orkbox with Ansible

An `orkbox` role and two playbooks: `orkbox.yml` installs orkbox on a Linux
host and runs it as a systemd service, and `orkbox-remove.yml` takes it off
again. Running `orkbox.yml` a second time is how orkbox is upgraded: a new jar,
a changed setting or a changed unit restarts the service, and nothing else does.

What the role does on each host:

- checks, before installing anything, that `orkbox_port` is free and that a
  first install has `orkbox_min_free_gb` (20 GB) free where Docker keeps its
  images, and stops with a sentence saying which when it is not;
- installs **Docker Engine** from Docker's repository and **Java 25** (Temurin)
  from Adoptium's, on Debian 12–13 and Ubuntu 22.04–24.04 (a newer release
  takes Adoptium's newest suite for it until Adoptium has its own);
- creates a system user, `orkbox`, in the `docker` group;
- copies the jar to `/opt/orkbox`, writes the settings to `/etc/orkbox/orkbox.env`
  (readable by root and orkbox only) and a unit to `/etc/systemd/system`;
- pulls the session image, so the first session does not wait for 1.7 GB;
- starts the service, enables it on boot, and waits for `/healthz` to answer.

The `docker` group is as good as root on that host. orkbox needs it to start
session containers, which is one more reason to give it a machine of its own;
see the [system requirements](../../README.md#system-requirements).

## Use

Ansible 2.15 or later on the machine you run it from, and SSH with sudo on the
host. Ansible does not run on Windows: use WSL there, and the commands below as
they are. From this directory:

```sh
cd ../.. && ./mvnw package -DskipTests && cd deploy/ansible   # builds the jar
cp inventory.example.ini inventory.ini                         # name your host
mkdir -p group_vars
ansible-vault encrypt_string 'a-long-random-token' --name orkbox_token \
  >> group_vars/orkbox.yml
ansible-playbook orkbox.yml --ask-vault-pass
```

Add `-K` (`--ask-become-pass`) when sudo on the host asks for a password;
without it the first task fails with `Missing sudo password`.

On Ubuntu 25.10 and later `sudo` is sudo-rs, and Ansible does not recognise
its password prompt: the first task fails with `Timeout (12s) waiting for
privilege escalation prompt`. The original sudo is still installed as
`sudo.ws`; add `ansible_become_exe=sudo.ws` to the host's line in
`inventory.ini`.

Then point orknux-server at `http://<host>:8080` with the same token
(Admin -> Boxes -> Add box).

To remove it, `ansible-playbook orkbox-remove.yml`. Docker, Java, the session
image and the shared dependency caches stay; add `-e orkbox_remove_caches=true`
to delete the caches as well.

## Variables

Set them in `group_vars/orkbox.yml` or `host_vars/<host>.yml`. Defaults are in
[`roles/orkbox/defaults/main.yml`](roles/orkbox/defaults/main.yml).

| variable | default | meaning |
|---|---|---|
| `orkbox_token` | — | Bearer token clients send. This, or `orkbox_user` and `orkbox_password`, is required. |
| `orkbox_user` / `orkbox_password` | — | Sign-in for the built-in UI instead of a token. |
| `orkbox_port` / `orkbox_host` | `8080` / `0.0.0.0` | Listen address. |
| `orkbox_min_free_gb` | `20` | Free space a first install needs where Docker keeps images; `0` skips the check. |
| `orkbox_settings` | `{}` | Any other `ORKBOX_*` setting from the [configuration table](../../README.md#configuration), by name - e.g. `ORKBOX_MAX_SESSIONS: 4`. |
| `orkbox_jar_src` | `../../orkbox-server/target/orkbox.jar` | The jar to copy, on the machine running Ansible. |
| `orkbox_jar_url` / `orkbox_jar_checksum` | — | Download the jar on the host instead, e.g. `sha256:…`. |
| `orkbox_pull_toolchain` | `true` | Pull the session image at install. |
| `orkbox_install_docker` / `orkbox_install_java` | `true` | Turn off on a host that already has them, or that is not Debian or Ubuntu. |
| `orkbox_java` / `orkbox_java_opts` | `/usr/bin/java` / `-Xmx512m` | The java the service runs on and its options. Sessions run in containers, outside this heap. |

orkbox listens on all addresses by default and does not open a firewall; put it
on a network only orknux-server can reach, or set `orkbox_host` and front it
with something that terminates TLS.
